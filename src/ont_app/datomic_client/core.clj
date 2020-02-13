(ns ont-app.datomic-client.core
  "Ports datomic.client.api to IGraph protocol with accumulate-only mutability"
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
   [ont-app.igraph.core :as igraph :refer :all]
   [ont-app.igraph.graph :as graph]
   [ont-app.vocabulary.core :as voc]
   ))

(def ontology @ont/ontology-atom)

(declare graph-union)
(declare graph-difference)
(declare graph-intersection)
(declare get-normal-form)
(declare get-subjects)
(declare query-for-p-o)
(declare query-for-o)
(declare ask-s-p-o)
(declare query-response)
(declare datomic-query)

(defrecord 
  ^{:doc "An IGraph compliant view on a Datascript graphs
With arguments [<db>
Where
<db> is an instance of a Datascript database.
"
    }
    DatomicClient [conn db]
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
    :db/doc "domain is string-valued properties. True if value should be read as edn."
    }
   {:db/ident :igraph/my-map
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :igraph/edn? true
    :db/doc "remove me"
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


(def standard-schema-subjects
  "The set of subjects provided at time of schema creation."
  #{:db/add :db/cardinality :db/cas :db/code :db/doc :db/ensure
    :db/excise :db/fn :db/fulltext :db/ident :db/index :db/isComponent
    :db/lang :db/noHistory :db/retract :db/retractEntity :db/system-tx
    :db/tupleAttrs :db/tupleType :db/tupleTypes :db/txInstant
    :db/unique :db/valueType :db.alter/attribute :db.attr/preds
    :db.bootstrap/part :db.cardinality/many :db.cardinality/one
    :db.entity/attrs :db.entity/preds :db.excise/attrs
    :db.excise/before :db.excise/beforeT :db.fn/cas
    :db.fn/retractEntity :db.install/attribute :db.install/function
    :db.install/partition :db.install/valueType :db.lang/clojure
    :db.lang/java :db.part/db :db.part/tx :db.part/user
    :db.sys/partiallyIndexed :db.sys/reId :db.type/bigdec
    :db.type/bigint :db.type/boolean :db.type/bytes :db.type/double
    :db.type/float :db.type/fn :db.type/instant :db.type/keyword
    :db.type/long :db.type/ref :db.type/string :db.type/symbol
    :db.type/tuple :db.type/uri :db.type/uuid :db.unique/identity
    :db.unique/value :fressian/tag :igraph/kwi})


(def domain-subject?
  "True when a subject ID is not part of the standard schema."
  (complement standard-schema-subjects))

(defn get-entity-id [db s]
  "Returns <e> for <s> in <g>
Where
<e> is the entity ID (a positive integer) in (:db <g>)
<s> is a subject s.t. [<e> ::id <s>] in (:db <g>)
<g> is an instance of DatascriptGraph
"
  (glog/value-debug!
   ::entity-id
   [:log/s s]
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
(def edn-properties (atom {}))
(defn clear-edn-properties-cache! []
  (reset! edn-properties {})
  )
(defn edn-property? [db p]
  "Returns true iff (g `p` :igraph/edn? true) for g of `db`
Where
<p> names a property in <db>
Note: typically used when deciding whether to encode/decode objects as edn.
"
  (when (not (@edn-properties db))
    (letfn [(collect-p [sacc [p is-edn?]]
              (if is-edn?
                (conj sacc p)
                sacc))
            ]
      (swap!  edn-properties
              assoc db
              (reduce collect-p
                      (or (@edn-properties db)
                          #{})
                      (d/q '[:find ?p ?is-edn
                             :where
                             [?_p :igraph/edn? ?is-edn]
                             [?_p :db/ident ?p]]
                           db)))))
  ((@edn-properties db) p))

(defn maybe-encode-edn [db p o]
  (if (edn-property? db p)
    (str o)
    o))

(defn maybe-read-edn [db p o]
  (if (and (string? o)
           (edn-property? db p))
    (clojure.edn/read-string o)
    o))

;;;;;;;;;;;;;;;;;;;;
;; GRAPH CREATION
;;;;;;;;;;;;;;;;;;;;

  (defn make-graph
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ORPHANS
;; Graph elements unconnected to other elements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def insignificant-attributes-atom "Caches insignificant attributes" (atom {}))

(defn insignificant-attributes [conn]
  "Returns #{<insignificant-attribute>, ...} for <conn>
Where
<insignificant attribute> is ignored when determining orphan-dom.
"
  (when (not (@insignificant-attributes-atom conn))
    (glog/value-debug!
     ::resetting-insignificant-attributes
     (swap! insignificant-attributes-atom
            assoc
            conn
            (into #{}
                  (map first
                       (d/q '[:find ?a
                              :where [?a :db/ident :igraph/kwi]
                              ]
                            (d/db conn)))))))
  (@insignificant-attributes-atom conn))
    
(defn orphaned? [db insignificant-attribute? e]
  "Returns true iff <e> has only insiginficant attributes, and is not the subject of any triple in <conn>.
"
  (empty?
   (filter
    (complement insignificant-attribute?)
    (glog/value-debug!
     ::attributes-in-orphaned
     (map first
          (glog/value-debug!
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
   (letfn [(ensure-entity-id [elt]
             ;; returns the int entity id for <elt>
             (if (int? elt)
               elt
               (get-entity-id (:db g) elt)))
           (retract-entity-clause [e]
                        [:db/retractEntity e])
                      ]
     (let [orphans (filter (partial orphaned? (:db g)
                                    (insignificant-attributes
                                     (:conn g)))
                           (map ensure-entity-id candidates))
                    ]
       (if (empty? orphans)
         g
         ;; else we need to remove orphans...
         (let []
           (d/transact
            (:conn g)
            {:tx-data
             (glog/value-debug!
              ::orphans-tx-data
              (into []
                    (map retract-entity-clause orphans)))})
           (make-graph (:conn g))))))))

;;;;;;;;;;;;;;;;;;;;
;; MEMBER ACCESS
;;;;;;;;;;;;;;;;;;;;

(defn get-subjects [db]
  "Returns (<s>, ...) for <db>, a lazy seq
Where
<s> is a unique identifier for some <e>
<db> is a datomic db
<e> <unique-id> <s>, in <db>
<e> is an entity-id
<unique-id> is any <p> s.t. <p>'s datatype is a unique ID.
"
  (filter (fn [s]
            (query-for-p-o db s)) ;; TODO: there's probably a more efficent way
          (map first
               (d/q '[:find ?s
                      :in $ %
                      :where
                      (subject ?e ?s)
                      ]
                    db igraph-rules))))


(defn query-for-p-o [db s]
  "Returns <desc> for <s> in <db>
Where
<desc> := {<p> #{<o>, ...}, ...}
"
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

(defn get-normal-form [db]
  "Returns {<s> {<p> #{<o>, ...}, ...}, ...} for all <s> in <db>
Where
<e> :igraph/kwi <s>"
  (letfn [(collect-descriptions [m s]
            (assoc m s (query-for-p-o db s)))
          ]
    (reduce collect-descriptions
            {}
            (get-subjects db))))

(defn query-for-o [db s p]
  "Returns #{<o>, ...} for <s> and <p> in <db>
Where
<o> is a value for <s> and <p> in <db>
<s> is a subject in <db>
<p> is a predicate with schema definition in <db>
<db> is a datomic db
"
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
          (throw e))))))

(defn ask-s-p-o [db s p o]
  "Returns true iff s-p-o is a triple in <db>"
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
                (throw e))))))))

;;;;;;;;;;;;
;; Querying
;;;;;;;;;;;;
(defn query-arity [q]
  "Returns either :arity-1 or :arity-2 depending on the type of `q`
Where
<q> is a query posed to a datomic db.
Maps are arity-1 and vectors are arity-2, with implicit db as 2nd arg"
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

(defn map-value-to-value-type [value]
  "Returns a :db.type/* for <value>
Where 
<value> is some graph element being claimed/retracted on some g"
  (or
   (@value-to-value-type-atom (type value))
   :edn-string))




(defmethod igraph/add-to-graph [DatomicClient :normal-form]
  [g triples]
  (glog/debug! ::starting-add-to-graph
              :log/normal-form triples)
  (when (not= (:t (:db g)) (:t (d/db (:conn g))))
    (glog/warn! ::DiscontinuousModification
                :glog/message "Adding to conn with t-basis {{log/conn-t}} from graph with t-basis {{log/g-t}}."
                :log/g-t (:t (:db g))
                :log/conn-t (:t (d/db (:conn g)))))
  (letfn [
          (maybe-new-p [s p annotations o]
            ;; Declares new properties and the types of their objects
            ;; It is expected that all objects are of the same type
            (if (empty? (g p))
              (add annotations [[:NewProperty :has-instance p]
                                [p
                                 :has-value-type
                                 (map-value-to-value-type o)]])
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
            (glog/debug! ::starting-maybe-new-ref
                         :log/p p
                         :log/annotations annotations
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
            ;; Maps each subject to an assertion map 
            (let [desc (or (unique (annotations :tx-data s))
                           {
                            :igraph/kwi s
                            })
                  ]
              (assert-unique annotations
                             :tx-data s (glog/value-debug!
                                         :adding-annotation
                                         [:log/s s
                                          :log/o o]
                                         (assoc desc p
                                                (maybe-encode-edn
                                                 (:db g)
                                                 p
                                                 o))))))

          (pre-process [annotations s p o]
            ;; Returns graph with vocabulary :NewProperty :has-instance 
            ;;  :has-value-type :tx-data
            ;; Where
            ;; :NewProperty :hasInstance <p>
            ;; :tx-data <subject> <assertion map>
            ;; <p> :has-value-type <value type>
            ;; <o> :db/id <db-id>
            ;; <value type> := :db.type/*
            ;; <assertion map> := {<datalog-property> <value>, ...}
            ;;   including a possible :db/id for new <o> refs
            ;;   and :igraph/kwi declaration for <s> and <o>
            ;;   plus schema declarations for new <p>
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
                 (let []
                   (clear-edn-properties-cache!)
                   (assoc m
                          :db/valueType :db.type/string
                          :igraph/edn? true)
                   )
                 ;; else it's not an edn string
                 (assoc m
                        :db/valueType value-type)))))


          (tx-data [annotations]
            (glog/value-debug!
             ::tx-data-in-add-to-graph
             [:log/annotations (igraph/normal-form annotations)]
             (reduce conj
                     []
                     ;; (annotations :tx-data) := {<s> #{<s-tx-data>}}
                     ;; <s-tx-data> := {<p> <o>, ...}
                     (map unique (vals (annotations :tx-data))))))
          ]
    
    (let [annotations 
          (reduce-spo pre-process
                      (graph/make-graph)
                      (graph/make-graph :contents triples))
          ]
      (when (not (empty? (annotations :NewProperty)))
        ;; There are new properties to declare in the schema
        (d/transact (:conn g)
                    {:tx-data
                     (glog/value-debug!
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
      (igraph/add (graph/make-graph) ;; use native graph as adapter
                  triples)))))

(defmethod igraph/add-to-graph [DatomicClient :vector] [g triple]
  (if (empty? triple)
    g
    (igraph/add-to-graph
     g
     (igraph/normal-form
      (igraph/add (graph/make-graph) ;; use native graph as adapter
                  (with-meta [triple]
                    {:triples-format :vector-of-vectors}))))))

(defmethod igraph/remove-from-graph [DatomicClient :vector-of-vectors]
  [g to-remove]
  (glog/debug! ::starting-remove-from-graph
              :log/vector-of-vectors to-remove)
  (when (not= (:t (:db g)) (:t (d/db (:conn g))))
    (glog/warn! ::DiscontinuousModification
                :glog/message "Retracting from conn with t-basis {{log/conn-t}} from graph with t-basis {{log/g-t}}."
                :log/g-t (:t (:db g))
                :log/conn-t (:t (d/db (:conn g)))))
  (let [db (d/db (:conn g))
        affected (atom #{}) ;; elements which may be orphaned
        ]
    (letfn [(register-affected [elt]
              (swap! affected conj elt)
              elt)
            (e-for-subject [s]
              (register-affected
               (get-entity-id db s)))
            (collect-p-o [s e vacc [p o]]
              ;; adds a retraction triple for <s> <p> <o> to <vacc>
              ;; where <s>, <p> and <o> are elements from to-remove
              (glog/debug! ::starting-collect-p-o
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
                                      (e-for-subject o)
                                      o)))
                    ]
                (if (not _o)
                  (let []
                    (glog/warn! ::no-object-found
                                :glog/message "No object '{{log/o}}' found to retract in [:db/retract {{log/s}} {{log/p}} {{log/o}}. Skipping."
                                :log/s s
                                :log/p p
                                :log/o o)
                    vacc)
                  ;; else there's an _o
                  (reduce conj vacc [p _o]))))

            (collect-retraction [vacc v]
              ;; returns [<retract-clause>, ...]
              ;; where <retract-clause> := [:db/retract <s-id> p <o-id-or-val>]
              (let [e (e-for-subject (first v))
                    retract-clause (reduce
                                    (partial collect-p-o (first v) e)
                                    [:db/retract e]
                                    (partition 2 (rest v)))
                    ]
                (if (> (count retract-clause) 3)
                  (conj vacc retract-clause)
                  vacc)))


            ]
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
  (case (count to-remove)
    1 (let [[s] to-remove
            
            ]
        (d/transact (:conn g)
                    {:tx-data [[:db/retractEntity (get-entity-id (:db g) s)]]}))
    
    2 (let [[s p] to-remove
            affected (atom #{}) ;; possibly orphaned
            ]
        (letfn [(ensure-entity-id [elt]
                  (cond
                    (int? elt) elt
                    (keyword? elt) (get-entity-id (:db g) elt)
                    :default nil))
                (affected-entity [elt]
                  (let [e (ensure-entity-id elt)]
                    (if e
                      (let []
                        (swap! affected conj e)
                        e))))
                  
                (collect-retraction [vacc o]
                  (conj
                   vacc
                   [:db/retract (affected-entity s)
                    p
                    (or (affected-entity o) o)]))
                ]
          (d/transact (:conn g)
                      {:tx-data (reduce collect-retraction [] (g s p))})
          (remove-orphans (make-graph (:conn g)) @affected)))))

  
;; SET OPERATIONS
#_(defn graph-union [g1 g2]
  "Returns union of <g1> and <g2> using same schema as <g1>
This uses igraph.graph/Graph as scratch, and probably won't scale.
TODO:Redo when you have data to develop for scale.
"
  #_(igraph/add (make-graph (merge (:schema (:db g1))
                                 (:schema (:db g2))))
       (igraph/normal-form (igraph/union
                            (igraph/add (graph/make-graph) (g1))
                            (igraph/add (graph/make-graph) (g2))))))

#_(defn graph-difference [g1 g2]
  "This uses igraph.graph/Graph as scratch, and probably won't scale.
TODO:Redo when you have data to develop for scale.
"
  #_(igraph/add (make-graph (:schema (:db g1)))
              (igraph/normal-form
               (igraph/difference
                (igraph/add (graph/make-graph) (g1))
                (igraph/add (graph/make-graph) (g2))))))

#_(defn graph-intersection [g1 g2]
  "This uses igraph.graph/Graph as scratch, and probably won't scale.
TODO:Redo when you have data to develop for scale.
"
  #_(igraph/add (make-graph (:schema (:db g1)))
              (igraph/normal-form
               (igraph/intersection
                (igraph/add (graph/make-graph) (g1))
                (igraph/add (graph/make-graph) (g2))))))



