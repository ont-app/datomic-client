(ns ont-app.datomic-client.core
  "Ports datomic.client.api to IGraph protocol with accumulate-only mutability.
  Defines a record DatomicClient
  With parameters [<conn> <db>]
  Where
  <conn> is a datomic connection to some client
  <db> is a DB in <conn> associated with some transaction.
  "
  {
   :voc/mapsTo 'ont-app.datomic-client.ont
   }
  (:require
   [clojure.set :as set]
   ;; 3rd party
   [datomic.client.api :as d]
   ;; ont-app
   [ont-app.datomic-client.ont :as ont]
   [ont-app.graph-log.core :as glog]
   [ont-app.graph-log.levels :refer :all]
   [ont-app.igraph.core :as igraph :refer :all]
   [ont-app.igraph.graph :as graph]
   [ont-app.vocabulary.core :as voc]
   ))

(def ontology
  "An igraph.graph/Graph describing the various elements in
  datomic-client. Primarily documentatry at this point. "  
  @ont/ontology-atom)

(declare get-normal-form)
(declare get-subjects)
(declare query-for-p-o)
(declare query-for-o)
(declare ask-s-p-o)
(declare query-response)
(declare datomic-query)

(defrecord DatomicClient [conn db]
  ;; see ns docstring for description
  igraph/IGraph
  (normal-form [this] (get-normal-form db))
  (subjects [this] (get-subjects db))
  (get-p-o [this s] (query-for-p-o db s))
  (get-o [this s p] (query-for-o db s p))
  (ask [this s p o] (ask-s-p-o db s p o))
  (mutability [this] ::igraph/accumulate-only)
  (query [this query-spec] (datomic-query db query-spec))

  clojure.lang.IFn
  (invoke [g] (igraph/normal-form g))
  (invoke [g s] (igraph/get-p-o g s))
  (invoke [g s p] (igraph/match-or-traverse g s p))
  (invoke [g s p o] (igraph/match-or-traverse g s p o))

  igraph/IGraphAccumulateOnly
  (claim [this to-add] (igraph/add-to-graph this to-add))
  (retract [this to-subtract] (igraph/remove-from-graph this to-subtract))
  )

;;;;;;;;;;;;;;;;;;;;
;; GRAPH CREATION
;;;;;;;;;;;;;;;;;;;;
(declare ensure-igraph-schema)

(defn ^DatomicClient make-graph
  "Returns an instance of DatascriptGraph for `conn` and optional `db`
  Where
  <conn> is a transactor, presumably initialized for domain-specific schemas
  <db> (optional) is a db filter on `conn`, default is the db as-of the
    current basis-t of <conn>
  "
  ([conn]
   (make-graph conn
               (d/as-of (d/db conn) (:t (d/db conn)))))
  ([conn db]
   (->DatomicClient (ensure-igraph-schema conn) db)))

;; TODO consider defining print-method

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IGRAPH SCHEMA DEFINITION AND UTILITIES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def igraph-schema
  [{:db/ident :igraph/kwi
    :db/valueType :db.type/keyword
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Uniquely names a graph element"
    }
   {:db/ident :igraph/edn?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Domain is string-valued property. True if value should be encoded and read as an edn representation of some object."
    }
   ])

(def igraph-rules
  '[
    [(subject ?e ?s)
     ;; pairs ?e with any ident or unique identifier as ?s
     (or-join [?e ?s]
              [?e :db/ident ?s]
              (and 
               [?p :db/unique :db.unique/identity]
               [?e ?p ?s]))]
    [(resolve-refs ?e ?p ?o ?v)
     ;; infers ?v from ?o depending on whether ?p maps to a ref
      (or-join [?e ?p ?o ?v]
               (and [?p :db/valueType :db.type/ref]
                    [?e ?p ?o]
                    (subject ?o ?v))
               (and (not [?p :db/valueType :db.type/ref])
                    [?e ?p ?o]
                    [(identity ?o) ?v]
                    )
               
               )]

     ]) ;; end rules

(defn ensure-igraph-schema [conn]
  "Side-effect: ensures that `igraph-schema` is installed in `conn`
Returns: `conn`
Where
<igraph-schema> defines all properties referenced in datomic-client,
  notably :igraph/kwi
<conn> is a transactor, presumably initialized with other domain-specific 
  schemas.
"
  (when (empty? (d/q '[:find ?e
                       :where [?e :db/ident :igraph/kwi]]
                     (d/db conn)))
    (d/transact conn {:tx-data igraph-schema}))
  conn)


(def standard-schema-elements
  "The set of elements provided at time of schema creation."
  (set (map (fn [bmap] (:?s bmap))
            (query ontology
                   [[:?s :rdf/type :datomic-client/StandardSchemaElement]]
                   ))))

(def domain-element?
  "True when a graph element is not part of the standard schema."
  (complement standard-schema-elements))

(defn entity-id 
  "Returns <e> for <s> in <db>
Where
<e> is the entity ID (a positive integer) in (:db <g>)
<s> is a subject s.t. [<e> ::id <s>] in (:db <g>)
<g> is an instance of DatascriptGraph
"
  [db s]
  {:pre [(keyword? s)]
   }
  (value-debug
   :entity-id [:log/s s]
   (igraph/unique
    (map first (d/q '[:find ?e
                      :in $ % ?s
                      :where (subject ?e ?s)
                      ]
                    db
                    igraph-rules
                    s)))))

;;;;;;;;;;;;;;;;;;;;;;;;
;; edn-encoded objects
;;;;;;;;;;;;;;;;;;;;;;;;
(def edn-properties-cache
  "Caches the set of properties which store EDN strings for a given DB.
  {<db> #{<property>, ...}, ...}"
  (atom {}))

(defn clear-edn-properties-cache! []
  "Side-effect: all values in edn-properties-cache are removed."
  (debug ::clearing-edn-properties-cache)
  (reset! edn-properties-cache {})
  )

(defn declare-edn-property! 
  "Side-effect: declares `p` as an EDN property in `db`"
  [db p]
  {:pre [(keyword? p)
         ]
   }
  (debug ::declaring-edn-property
                          :log/p p)
  (swap! edn-properties-cache
         assoc db
         (conj (or (@edn-properties-cache db)
                   #{})
               p)))

(defn edn-property? 
  "Returns true iff (g `p` :igraph/edn? true) for g of `db`
Where
<p> names a property in <db>
Note: typically used when deciding whether to encode/decode objects as edn.
"
  [db p]
  (when (not (@edn-properties-cache db))
    (value-debug
     ::populating-edn-properties-cache
     [:log/triggered-by p]
     (letfn [(collect-p [sacc [p is-edn?]]
               (if is-edn?
                 (conj sacc p)
                 sacc))
             ]
       (swap!  edn-properties-cache
               assoc db
               (reduce collect-p
                       (or (@edn-properties-cache db)
                           #{})
                       (d/q '[:find ?p ?is-edn
                              :where
                              [?_p :igraph/edn? ?is-edn]
                              [?_p :db/ident ?p]]
                            db))))))
  ((@edn-properties-cache db) p))

(defn maybe-encode-edn
  "Returns an EDN string for `o` if (edn-property? `p`) else `o`"
  [db p o]
  (if (edn-property? db p)
    (value-debug
     ::encoding-edn
     [:log/o o]
     (str o))
    o))

(defn maybe-read-edn 
  "Returns the clojure object read from `o` if (edn-property? `p`) else `o`"
  [db p o]
  (value-debug
   ::reading-edn
   [:log/o o]
   (if (and (string? o)
            (edn-property? db p))
     (clojure.edn/read-string o)
     o)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ORPHANS
;; Graph elements unconnected to other elements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def orphan-irrelevant-attributes-cache
  "Caches attributes irrelevant to determining orphans for some `conn`
  {<conn> #{<attribute>, ....}}
  "
  (atom {}))

(defn clear-orphan-irrelevant-attributes-cache! 
  "Side-effect: removes all values in orphan-irrelevant-attributes-cache "
  []
  (reset! orphan-irrelevant-attributes-cache {}))

(defn orphan-irrelevant-attributes 
  "Returns #{<insignificant-attribute>, ...} for <conn>
Where
<insignificant attribute> is ignored when determining orphan-dom.
"
  [conn]
  (when (not (@orphan-irrelevant-attributes-cache conn))
    (value-debug
     ::resetting-orphan-irrelevant-attributes
     (swap! orphan-irrelevant-attributes-cache
            assoc
            conn
            (into #{}
                  (map first
                       (d/q '[:find ?a
                              :where [?a :db/ident :igraph/kwi]
                              ]
                            (d/db conn)))))))
  (@orphan-irrelevant-attributes-cache conn))
    
(defn orphaned? [db insignificant-attribute? e]
  {:pre [(not (nil? e))
         (or (debug ::starting-orphaned?
                          :log/e e)
               true)]
   }
  "Returns true iff <e> has only irrelevant attributes, and is not the
subject of any triple in <conn>.
"

  (empty?
   (filter
    (complement insignificant-attribute?)
    (value-debug
     ::attributes-in-orphaned
     (map first
          (value-debug
           ::query-for-attributes
           (d/q '[:find ?a
                  :in $ ?e
                  :where
                  (or-join [?e ?a]
                           [?e ?a ?v]
                           [?x ?a ?e])]
                db
                e)))))))

(defn remove-orphans
  "Returns <g'>, s.t. `candidates` are removed if they are orphans
     i.e. not connected to other elements.
  Side-effect: (:conn g)  will be modified accordingly.
  Where
  <g> is a datomic-client
  <candidates> := #{<elt>, ...}, (or any sequence) default (subjects g)
  <elt> is an element in <g> which we suspect might be an orphan. It may be
    an integer entity ID or a keyword unique identifier.
  "
  ([g]
   (remove-orphans [g (subjects g)]))
  
  ([g candidates]
   (letfn [(get-entity-id [elt]
             ;; returns the numeric entity id for <elt>
             (if (int? elt)
               elt
               (entity-id (:db g) elt)))
           (retract-entity-clause [e]
             [:db/retractEntity e])
           ]
     (let [orphans (filter (partial orphaned? (:db g)
                                    (orphan-irrelevant-attributes
                                     (:conn g)))
                           (map get-entity-id candidates))
                    ]
       (if (empty? orphans)
         g
         ;; else we need to remove orphans...
         (let []
           (d/transact
            (:conn g)
            {:tx-data
             (value-debug
              ::orphans-tx-data
              (into []
                    (map retract-entity-clause orphans)))})
           (make-graph (:conn g))))))))

;;;;;;;;;;;;;;;;;;;;
;; MEMBER ACCESS
;;;;;;;;;;;;;;;;;;;;

(defn get-subjects 
  "Returns (<s>, ...) for <db>, a lazy seq
Where
<s> is a unique identifier for some <e>
<db> is a datomic db
<e> <unique-id> <s>, in <db>
<e> is an entity-id
<unique-id> is any <p> s.t. <p>'s datatype is a unique ID.
"
  [db]
  (filter (fn [s]
            (query-for-p-o db s)) 
          (map first
               (d/q '[:find ?s
                      :in $ %
                      :where
                      (subject ?e ?s)
                      ]
                    db igraph-rules))))


(defn query-for-p-o 
  "Returns <desc> for <s> in <db>
Where
<desc> := {<p> #{<o>, ...}, ...}
"
  [db s]
  (letfn [(collect-desc [desc [p o]]
            (if (= p :igraph/kwi)
              desc
              (let [os (or (desc p) #{})
                    ]
                (assoc desc p (conj os (maybe-read-edn db p o))))))
          ]
    (let [desc
          (reduce collect-desc {}
                  (d/q '[:find ?p ?o
                         :in $ % ?s
                         :where
                         (subject ?e ?s)
                         [?e ?_p ?_o]
                         [?_p :db/ident ?p]
                         (resolve-refs ?e ?_p ?_o ?o)
                         ]
                       db igraph-rules s))
          ]
      (if (not (empty? desc))
        desc))))

(def normal-form-timeout
  "Sets the timeout in ms for normal form query"
  (atom 1000))

(defn get-normal-form
  "Returns <desc> for <s> in <db>
Where
<desc> := {<p> #{<o>, ...}, ...}
"
  [db]
  (letfn [(collect-desc [desc [p o]]
            (if (= p :igraph/kwi)
              desc
              (let [os (conj (or (desc p) #{})
                             (maybe-read-edn db p o))
                    ]
                (if-not (empty? os)
                  (assoc desc p (conj os ))
                  desc))))
          (collect-entry [macc [s p o]]
            (let [desc (collect-desc (or (macc s)
                                         {})
                                     [p o])
                  ]
              (if-not (empty? desc)
                (assoc macc s desc)
                macc)))
          ]
    (try
      (let [entries
          (reduce collect-entry {}
                  (d/q {:query '[:find ?s ?p ?o
                                 :in $ %
                                 :where
                                 (subject ?e ?s)
                                 [?e ?_p ?_o]
                                 [?_p :db/ident ?p]
                                 (resolve-refs ?e ?_p ?_o ?o)
                                 ]
                        :args [db igraph-rules]
                        :timeout @normal-form-timeout
                        }))
            ]
        entries)
      (catch Throwable e
        (throw (ex-info "Normal Form is intractable"
               (merge (:ex-data e)
                      {:type ::igraph/Intractable
                       :normal-form-timeout @normal-form-timeout
                       :db db
                       })))))))
                   

(defn query-for-o 
  "Returns #{<o>, ...} for `s` and `p` in `db`
Where
<o> is a value for <db> s.t. <s> <p>  <o> 
<s> is a subject in <db>
<p> is a predicate with schema definition in <db>
<db> is a datomic DB
"
  [db s p]
  (try
    (let [os
          (reduce conj
                  #{}
                  (map
                   (partial maybe-read-edn db p)
                   (map first
                        (d/q '[:find ?o
                               :in $ % ?s ?p
                               :where
                               (subject ?e ?s)
                               [?e ?p ?_o] 
                               (resolve-refs ?e ?p ?_o ?o)
                               ;; todo do we need this?
                               ]
                             db igraph-rules s p))))]
      (if (not (empty? os))
        os))
    (catch Throwable e
      (let [ed (ex-data e)]
        (if (= (:db/error ed) :db.error/not-an-entity)
          nil
          (throw (ex-info "Query error in query-for-o"
                          (merge (ex-data e)
                                 ::db db
                                 ::s s
                                 ::p p)e)))))))

(defn ask-s-p-o 
  "Returns true iff s-p-o is a triple in <db>"
  [db s p o]
  (not (empty?
        (try 
          (d/q '[:find ?e
                 :in $ % ?s ?p ?o
                 :where
                 (subject ?e ?s)
                 [?e ?p ?_o]
                 (resolve-refs ?e ?p ?_o ?o)
                 ]
               db igraph-rules
               s p (maybe-encode-edn db p o))
          (catch Throwable e
            (let [ed (ex-data e)]
              (if (= (:db/error ed) :db.error/not-an-entity)
                nil
                (throw (ex-info "Query error in ask-s-p-o"
                                (merge (ex-data e)
                                       ::db db
                                       ::s s
                                       ::p p
                                       ::o o))))))))))

;;;;;;;;;;;;
;; Querying
;;;;;;;;;;;;
(defn query-arity 
  "Returns either :arity-1 or :arity-2 depending on the type of `q`
Where
<q> is a query posed to a datomic db.
Maps are arity-1 and vectors are arity-2, with implicit db as 2nd arg"
  [q]
  (cond
    (map? q) :arity-1
    (vector? q) :arity-2))
      
(defmulti datomic-query
  "Args: [db q]. Returns the result of a query `q` posed to `db`
  Where
  <q> is either a map conforming to datomic's arity-1 query or a vector
  conforming to a :query clause, with implicit <db> as 2nd argument to d/q."
  (fn [g q] (query-arity q)))

(defmethod datomic-query :arity-1
  [_ q]
  (d/q q))

(defmethod datomic-query :arity-2
  [db q]
  (d/q q db))


;;;;;;;;;;;;;;;;;;;;
;; CLAIM/RETRACT
;;;;;;;;;;;;;;;;;;;;
(def value-to-value-type-atom
  "From https://docs.datomic.com/cloud/schema/schema-reference.html#db-valuetype"
  (atom
   {
    java.math.BigDecimal :db.type/bigdec
    java.math.BigInteger :db.type/bigint
    java.lang.Boolean :db.type/boolean
    java.lang.Double :db.type/double
    java.lang.Float :db.type/float
    java.util.Date :db.type/instant
    clojure.lang.Keyword :db.type/ref ;; assume keywords are KWIs
    java.lang.Long :db.type/long
    java.lang.String :db.type/string
    clojure.lang.Symbol :db.type/symbol
    java.util.UUID :db.type/uuid
    java.net.URI :db.type/URI
    ;; clojure container classes not listed here.
    }))

(defn map-value-to-value-type 
  "Returns a :db.type/* for <value>, or :edn-string 
Where 
<value> is some graph element being claimed/retracted on some g
:edn-string signals that <value> should be stored as an EDN string.
"
  [value]
  (or
   (@value-to-value-type-atom (type value))
   :edn-string))

(defmethod igraph/add-to-graph [DatomicClient :normal-form]
  [g triples]
  (debug ::starting-add-to-graph
              :log/normal-form triples)
  (when (not= (:t (:db g)) (:t (d/db (:conn g))))
    (warn ::DiscontinuousModification
                :glog/message "Adding to conn with t-basis {{log/conn-t}} from graph with t-basis {{log/g-t}}."
                :log/g-t (:t (:db g))
                :log/conn-t (:t (d/db (:conn g)))))
  
  (letfn [;; See comments for 'pre-process', below for notes on <annotations>
          (maybe-new-p [s p annotations o]
            ;; Declares new properties and the types of their objects
            ;; It is expected that all objects are of the same type
            (debug ::starting-maybe-new-p
                         :log/s s
                         :log/p p
                         :log/o o
                         :log/annotations (annotations))
            (if (empty? (g p))
              (let [value-type (map-value-to-value-type o)
                    ]
                (when (= value-type :edn-string)
                  (declare-edn-property! (:db g) p))
                (add annotations [[:NewProperty :has-instance p]
                                  [p
                                   :has-value-type value-type
                                   ]]))
              ;; else (g p) is not empty
              annotations))

          (ensure-db-id-tx-data [annotations o db-id]
            (let [tx-data (or (unique (annotations :tx-data o))
                              {})
                  ]
              (if (tx-data :db/id)
                annotations
                (assert-unique annotations :tx-data o (assoc tx-data
                                                             :db/id db-id
                                                             :igraph/kwi o)))))
          (maybe-new-ref [p annotations o]
            ;; Declares a new db/id for new references
            (debug ::starting-maybe-new-ref
                         :log/p p
                         :log/o o
                         :value-type (annotations p :has-value-type))
            (if (or (annotations p :has-value-type :db.type/ref)
                    (g p :db/valueType :db.type/ref))
              (let [db-id (subs (str o) 1)]
                (-> annotations
                    (ensure-db-id-tx-data o db-id)
                    ;; annotate the db/id for future reference
                    (assert-unique o :db/id db-id)))
              ;; else no db-id or ref
              annotations))
          
          (collect-assertion [s p annotations o]
            ;; Maps each subject to a tx-data assertion map 
            (let [desc (or (unique (annotations :tx-data s))
                           {
                            :igraph/kwi s
                            })
                  ]
              (assert-unique annotations
                             :tx-data s (value-debug
                                         :adding-annotation
                                         [:log/s s
                                          :log/o o]
                                         (assoc desc p
                                                (maybe-encode-edn
                                                 (:db g)
                                                 p
                                                 o))))))

          (pre-process [annotations s p o]
            ;; a reduce-spo function
            ;; Accumulates 'annotations' graph structured as:
            ;; {:NewProperty {:has-instance #{<p>, ...}}
            ;;  :tx-data  {<subject> #{<assertion-map>}, ...}
            ;;  <p> {:has-value-type #{<value-type>}}
            ;;  <o> {:db/id #{<db-id>}}
            ;;  }
            ;; <subject> a kwi naming an entity in <db>
            ;; <p> a kwi naming a new property for <db>
            ;; <o> an object value (possibly a kwi)
            ;; <assertion-map> {<datalog-property> <value>, ...}
            ;; <value type> := :db.type/* or :edn-string
            ;;  :edn-string signals that the object should be
            ;;  encoded as edn and read into an object on retrieval
            ;; <assertion map> := {<datalog-property> <value>, ...}
            ;;   including a possible :db/id for new <o> refs
            ;;   and :igraph/kwi declaration for <s> and <o>
            ;;   plus schema declarations for new <p>
            ;;   or :igraph/edn? declaration for :edn-string's
            ;; <db-id> a temporary string to be encoded as an integer id
            ;; <value> may be <db-id> for refs or <o> otherwise
            (as-> annotations a
              (maybe-new-p s p a o)
              (maybe-new-ref p a o)
              (collect-assertion s
                                 p
                                 a
                                 (or (unique (a o :db/id))
                                     o))))
          
          (collect-schema-tx-data [annotations vacc p]
            (let [value-type (unique (annotations p :has-value-type))
                  m {:db/ident p
                     :db/cardinality :db.cardinality/many
                     :db/doc (str "Declared automatically while importing")
                     } 
                  ]
              (conj
               vacc
               (if (= value-type :edn-string)
                 (assoc m
                        :db/valueType :db.type/string
                        :igraph/edn? true)
                 ;; else it's not an edn string
                 (assoc m
                        :db/valueType value-type)))))

          (tx-data [annotations]
            (value-debug
             ::tx-data-in-add-to-graph
             [:log/annotations (igraph/normal-form annotations)]
             (reduce conj
                     []
                     ;; (annotations :tx-data) := {<s> #{<s-tx-data>}}
                     ;; <s-tx-data> := {<p> <o>, ...}
                     (map unique (vals (annotations :tx-data))))))
          ]
    ;; main body of function
    (let [annotations 
          (reduce-spo pre-process
                      (graph/make-graph)
                      (graph/make-graph :contents triples))
          ]
      (when (not (empty? (annotations :NewProperty)))
        ;; There are new properties to declare in the schema
        (d/transact (:conn g)
                    {:tx-data
                     (value-debug
                      ::schema-update-tx-data
                      (reduce
                       (partial collect-schema-tx-data annotations)
                       []
                       (annotations :NewProperty :has-instance)))
                     }))
      (d/transact (:conn g)
                  {:tx-data  (tx-data annotations)})
      
      (make-graph (:conn g)))))

(defmethod igraph/add-to-graph [DatomicClient :vector-of-vectors]
  [g triples]
  (if (empty? triples)
    g
    (igraph/add-to-graph
     g
     (igraph/normal-form
      (igraph/add (graph/make-graph) ;; use native igraph as adapter
                  triples)))))

(defmethod igraph/add-to-graph [DatomicClient :vector] [g triple]
  (if (empty? triple)
    g
    (igraph/add-to-graph
     g
     (igraph/normal-form
      (igraph/add (graph/make-graph) ;; use native igraph as adapter
                  (with-meta [triple]
                    {:triples-format :vector-of-vectors}))))))

(defn retract-clauses-for-underspecified-triple 
  "Returns [<retraction-clause>, ...] for `to-remove` from `db`
Where
<retraction-clause> := [<op> <elt> ...]
<g> is a 
<op> is in #{:db/retract :db/retractEntity}
<elt> is an element of <g>
<affected-entity-fn> := fn [elt] -> <entity-id>, typically with the side-effect 
  of registering a possible orphan in the calling function.
<entity-id> is the numeric id of <elt> in <g>
"

  [affected-entity-fn g to-remove]
  ;; underspecified triple is [s] or [s p]
  
  (case (count to-remove)
    1 (let [[s] to-remove
            
            ]
        (assert (keyword? s))
        [[:db/retractEntity (entity-id (:db g) s)]])
    
    2 (let [[s p] to-remove
            objects (query g {:query '[:find ?o
                                        :in $ % ?s ?p
                                        :where 
                                        (subject ?e ?s)
                                        [?e ?p ?o]]
                               :args [(:db g)
                                      igraph-rules
                                      s
                                      p]})
            ]
        (letfn [
                (collect-retraction [vacc o]
                  (conj
                   vacc
                   [:db/retract (affected-entity-fn s)
                    p
                    (or (and (keyword? o)
                             (affected-entity-fn o))
                        o)]))
                ]
          (reduce collect-retraction
                  []
                  (map first objects))))))

(defmethod igraph/remove-from-graph [DatomicClient :vector-of-vectors]
  [g to-remove]
  (debug ::starting-remove-from-graph
               :log/vector-of-vectors to-remove)

  (when (not= (:t (:db g)) (:t (d/db (:conn g))))
    (warn ::DiscontinuousModification
                :glog/message "Retracting from conn with t-basis {{log/conn-t}} from graph with t-basis {{log/g-t}}."
                :log/g-t (:t (:db g))
                :log/conn-t (:t (d/db (:conn g)))))
  (let [db (d/db (:conn g))
        affected (atom #{}) ;; elements which may be orphaned
        ]
    (letfn [(register-affected [e]
              (swap! affected conj e)
              e)
            (e-for-elt [elt]
              (if-let [e (entity-id db elt)]
                (register-affected e)
                elt))
            (collect-p-o [s e vacc [p o]]
              ;; adds a retraction triple for <s> <p> <o> to <vacc>
              ;; where <s>, <p> and <o> are elements from to-remove
              ;; <e> is the ID of <s>
              (debug ::starting-collect-p-o
                     :log/s s
                     :log/p p
                     :log/o o
                     :log/e e)
              (let [_o (ffirst (d/q '[:find ?o
                                      :in $ ?e ?p ?o
                                      :where [?e ?p ?o]
                                      ]
                                    db
                                    e
                                    p
                                    (if (keyword? o)
                                      ;;... TODO check p schema for ref
                                      (e-for-elt o)
                                      o)))
                    ]
                (if (not _o)
                  (let []
                    (warn ::no-object-found-to-retract
                                :glog/message "No object '{{log/o}}' found to retract in [:db/retract {{log/s}} {{log/p}} {{log/o}}. Skipping."
                                :log/s s
                                :log/p p
                                :log/o o)
                    vacc)
                  ;; else there's an _o
                  (reduce conj vacc [p _o]))))
            
            (collect-underspecified-retractions [vacc v]
              ;; collects retraction clauses for underspecified triples
              (reduce conj
                      vacc
                      (retract-clauses-for-underspecified-triple
                       e-for-elt
                       g
                       v)))
                      
            (collect-retraction [vacc v]
              ;; returns [<retract-clause>, ...]
              ;; where <retract-clause> := [:db/retract <s-id> <p> <o-id-or-val>]
              ;; <v> := [<s> <p> <o> & maybe <p>, <o>, ...]
              (if (> (count v) 2)
                (let [e (e-for-elt (first v))
                      retract-clause (reduce
                                      (partial collect-p-o (first v) e)
                                      [:db/retract e]
                                      (partition 2 (rest v)))
                      ]
                  (if (> (count retract-clause) 3)
                    (conj vacc retract-clause)
                    vacc))
                ;; else v is underspecified
                (collect-underspecified-retractions vacc v)))


            ] ;; letfn
      
      ;; main body of function
      (d/transact (:conn g)
                  {:tx-data (reduce collect-retraction [] to-remove)})
      (remove-orphans (make-graph (:conn g)) @affected))))

(defmethod igraph/remove-from-graph [DatomicClient :vector]
  [g to-remove]
  (igraph/remove-from-graph
   g
   (with-meta [to-remove]
     {:triples-format :vector-of-vectors})))


(defmethod igraph/remove-from-graph [DatomicClient :normal-form]
  [g to-remove]
  (letfn [(collect-vector [vacc s p o]
            (conj vacc [s p o]))
          ]
    (igraph/remove-from-graph
     g
     (with-meta (igraph/reduce-spo collect-vector
                                   []
                                   (graph/make-graph
                                    :contents to-remove))
       {:triples-format :vector-of-vectors}))))


(defmethod igraph/remove-from-graph [DatomicClient :underspecified-triple]
  [g to-remove]
  ;; underspecified triple is [s] or [s p]
  (let [affected (atom #{})
        ]
    (letfn [(register-affected [elt]
              (swap! affected conj elt)
              elt)
            (e-for-subject [s]
              (register-affected
               (entity-id (:db g) s)))
            ]
      (d/transact (:conn g)
                  {:tx-data (retract-clauses-for-underspecified-triple
                             e-for-subject ;; also populates @affected
                             g
                             to-remove)
                   })
      (remove-orphans (make-graph (:conn g)) @affected))))

#_(case (count to-remove)
    1 (let [[s] to-remove
            
            ]
        (d/transact (:conn g)
                    {:tx-data [[:db/retractEntity (entity-id (:db g) s)]]}))
    
    2 (let [[s p] to-remove
            affected (atom #{}) ;; possibly orphaned
            ;; object may be edn string, so (g s p) is out...
            objects (query g {:query '[:find ?o
                                        :in $ % ?s ?p
                                        :where 
                                        (subject ?e ?s)
                                        [?e ?p ?o]]
                               :args [(:db g)
                                      igraph-rules
                                      s
                                      p]})
            ]
        (letfn [(get-entity-id [elt]
                  (cond
                    (int? elt) elt
                    (keyword? elt) (entity-id (:db g) elt)
                    :default nil))
                
                (affected-entity [elt]
                  (let [e (get-entity-id elt)]
                    (if e
                      (let []
                        (swap! affected conj e)
                        e))))
                  
                (collect-retraction [vacc o]
                  (conj
                   vacc
                   [:db/retract (affected-entity s)
                    p
                    (or (affected-entity o)
                        o)]))
                ]
          (d/transact (:conn g)
                      {:tx-data (reduce collect-retraction
                                        []
                                        (map first objects))})
          (remove-orphans (make-graph (:conn g)) @affected))))

#_(defmethod igraph/remove-from-graph [DatomicClient :underspecified-triple]
  [g to-remove]
  ;; underspecified triple is [s] or [s p]
  (case (count to-remove)
    1 (let [[s] to-remove
            
            ]
        (d/transact (:conn g)
                    {:tx-data [[:db/retractEntity (entity-id (:db g) s)]]}))
    
    2 (let [[s p] to-remove
            affected (atom #{}) ;; possibly orphaned
            ;; object may be edn string, so (g s p) is out...
            objects (query g {:query '[:find ?o
                                        :in $ % ?s ?p
                                        :where 
                                        (subject ?e ?s)
                                        [?e ?p ?o]]
                               :args [(:db g)
                                      igraph-rules
                                      s
                                      p]})
            ]
        (letfn [(get-entity-id [elt]
                  (cond
                    (int? elt) elt
                    (keyword? elt) (entity-id (:db g) elt)
                    :default nil))
                
                (affected-entity [elt]
                  (let [e (get-entity-id elt)]
                    (if e
                      (let []
                        (swap! affected conj e)
                        e))))
                  
                (collect-retraction [vacc o]
                  (conj
                   vacc
                   [:db/retract (affected-entity s)
                    p
                    (or (affected-entity o)
                        o)]))
                ]
          (d/transact (:conn g)
                      {:tx-data (reduce collect-retraction
                                        []
                                        (map first objects))})
          (remove-orphans (make-graph (:conn g)) @affected)))))
