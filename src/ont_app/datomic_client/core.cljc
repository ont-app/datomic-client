(ns ont-app.datomic-client.core
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

(voc/cljc-put-ns-meta!
 'ont-app.datomic-client.core
 {
  :voc/mapsTo 'ont-app.datomic-client.ont
  }
 )

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
  (query [this query-spec] (d/q query-spec db))
  ;; query-spec is map for 1-arity query mode see also
  ;; https://docs.datomic.com/client-api/datomic.client.api.html
  
  #?(:clj clojure.lang.IFn
     :cljs cljs.core/IFn)
  (invoke [g] (igraph/normal-form g))
  (invoke [g s] (igraph/get-p-o g s))
  (invoke [g s p] (igraph/match-or-traverse g s p))
  (invoke [g s p o] (igraph/match-or-traverse g s p o))

  igraph/IGraphAccumulateOnly
  (claim [this to-add] (igraph/add-to-graph this to-add))
  (retract [this to-subtract] (igraph/remove-from-graph this to-subtract))

  igraph/IGraphSet
  (union [g1 g2] (graph-union g1 g2))
  (difference [g1 g2] (graph-difference g1 g2))
  (intersection [g1 g2] (graph-intersection g1 g2))
  )

(def igraph-schema
  [{:db/ident :igraph/kwi
    :db/valueType :db.type/keyword
    :db/unique :db.unique/identity
    :db/doc "Uniquely names a graph element"
    :db/cardinality :db.cardinality/one
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
    

(defn make-graph
  "Returns an instance of DatascriptGraph for `conn` and maybe `db`
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

  
(defn get-subjects [db]
  "Returns (<s>, ...) for <db>, a lazy seq
Where
<s> is a keyword identifier
<e> :igraph/kwi <s>, in <db>
<e> is an entity-id
<db> is a datomic db
"
  (map first
       (d/q '[:find ?s
              :in $ %
              :where
              (subject ?e ?s)]
            db igraph-rules)))

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
                (assoc desc p (conj os o)))))
          ]
    (reduce collect-desc {}
            (d/q '[:find ?p ?o
                   :in $ % ?s
                   :where
                   (subject ?e ?s)
                   [?e ?_p ?_o]
                   [?_p :db/ident ?p]
                   (resolve-refs ?e ?p ?_o ?o)
                   ]
                 db igraph-rules s))))

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
  (reduce conj
          #{}
          (map first
               (d/q '[:find ?o
                      :in $ % ?s ?p
                      :where
                      (subject ?e ?s)
                      [?e ?_p ?_o] 
                      [?_p :db/ident ?p]
                      (resolve-refs ?e ?p ?_o ?o)
                      ]
                    db igraph-rules s p))))

(defn ask-s-p-o [db s p o]
  "Returns true iff s-p-o is a triple in <db>"
  (not (empty?
        (d/q '[:find ?e
               :in $ % ?s ?p ?o
               :where
               (subject ?e ?s)
               [?e ?p ?_o]
               (resolve-refs ?e ?p ?_o ?o)
               ]
             db igraph-rules
             s p o))))




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
    clojure.lang.Keyword :db.type/ref ;; note
    java.lang.Long :db.type/long
    java.lang.String :db.type/string
    clojure.lang.Symbol :db.type/symbol
    java.util.UUID :db.type/uuid
    java.net.URI :db.type/URI
    ;; clojure container classes not listed here.
    }))

(defn map-value-to-value-type [value]
  (or
   (@value-to-value-type-atom (type value))
   (cond
     (vector? value) :db.type/tuple)))
        

(defmethod igraph/add-to-graph [DatomicClient :normal-form]
  [g triples]
  (glog/info! :log/starting-add-to-graph
              :triples triples)
  (when (not= (:t (:db g)) (:t (d/db (:conn g))))
    (glog/warn! :log/DiscontinuousDiscourse
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
          
          (maybe-new-ref [p annotations o]
            ;; Declares a new db/id for new references
            (glog/debug! :log/starting-maybe-new-ref
                         :p p
                         :annotations annotations
                         :o o
                         :value-type (annotations p :has-value-type))
            (if (and (or (annotations p :has-value-type :db.type/ref)
                         (g p :db/valueType :db.type/ref))
                     (empty? (annotations :tx-data o))
                     )
              (let [db-id (subs (str o) 1)]
                (-> annotations
                    (assert-unique :tx-data o {:db/id db-id
                                               :igraph/kwi o})
                    ;; annotate the db/id for future reference
                    (assert-unique o :db/id db-id)))
              annotations))
          
          (collect-assertion [s p annotations o]
            ;; Maps each subject to an assertion map 
            (let [desc (or (unique (annotations :tx-data s))
                           {
                            :igraph/kwi s
                            })
                  ]
              (assert-unique annotations
                             :tx-data s (assoc desc p o))))



          (pre-process [annotations s p o]
            ;; Returns graph with vocabulary :NewProperty :has-instance 
            ;;  :has-value-type :tx-data
            ;; Where
            ;; :NewProperty :hasInstance <p>
            ;; :tx-data <subject> <assertion map>
            ;; <p> :has-value-type <value type>
            ;; <o> :db/id <db-id>
            ;; <value type> is a datomic value type
            ;; <assertion map> := {<datalog-property> <value>, ...}
            ;;   including a possible :db/id for new <o> refs
            ;;   and :igraph/kwi declaration for <s>
            ;; <value> may be <db-id> or <o> as appropriate
            (as-> annotations a
              ;;(maybe-declare-kwi a s)
              (maybe-new-p s p a o)
              (maybe-new-ref p a o)
              (collect-assertion s
                                 p
                                 a
                                 (or (unique (a o :db/id))
                                     o))))
          
          (collect-schema-tx-data [annotations vacc p]
            (conj
             vacc
             {:db/ident p
              :db/valueType (unique (annotations p :has-value-type))
              :db/cardinality :db.cardinality/many
              :db/doc (str "Declared automatically while importing")
              }))
          (tx-data [annotations]
            (glog/value-debug!
             :log/tx-data-in-add-to-graph
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
        (d/transact (:conn g)
                    {:tx-data (reduce
                               (partial collect-schema-tx-data annotations)
                               []
                               (annotations :NewProperty :has-instance))}))
      (d/transact (:conn g)
                  {:tx-data  (tx-data annotations)})
      
      (make-graph (:conn g)))))
      

;; Declared in igraph.core
(defmethod igraph/add-to-graph [DatomicClient :vector-of-vectors]
  [g triples]
  (if (empty? triples)
    g
    (igraph/add-to-graph
     g
     (igraph/normal-form
      (igraph/add (graph/make-graph)
                  (with-meta triples
                    {:triples-format :vector-of-vectors}))))))

;; Declared in igraph.core
(defmethod igraph/add-to-graph [DatomicClient :vector] [g triple]
  (if (empty? triple)
    g
    (igraph/add-to-graph
     g
     (igraph/normal-form
      (igraph/add (graph/make-graph)
                  (with-meta [triple]
                    {:triples-format :vector-of-vectors}))))))


(defmethod igraph/remove-from-graph [DatomicClient :vector-of-vectors]
  [g to-remove]
  (when (not= (:t (:db g)) (:t (d/db (:conn g))))
    (glog/warn! :log/DiscontinuousDiscourse
                :glog/message "Retracting from conn with t-basis {{log/conn-t}} from graph with t-basis {{log/g-t}}."
                :log/g-t (:t (:db g))
                :log/conn-t (:t (d/db (:conn g)))))
  (let [db (d/db (:conn g))
        ]
    (letfn [(e-for-subject [s]
              (ffirst
               (d/q
                '[:find ?e
                  :in $ % ?s
                  :where (subject ?e ?s)
                  ]
                db
                igraph-rules
                s)))
            (collect-p-o [s e vacc [p o]]
              (glog/debug! :log/starting-collect-p-o
                           :p p
                           :o o
                           :e e)
              (let [_o (ffirst (d/q '[:find ?o
                                      :in $ ?e ?p ?o
                                      :where [?e ?p ?o]
                                      ]
                                    db
                                    e
                                    p
                                    (if (keyword? o)
                                      (e-for-subject o)
                                      o)))
                    ]
                (if (not _o)
                  (let []
                    (glog/warn! ::no-object-found
                                :glog/message "No object '{{log/o}}' found to retract in [:db/retract {{log/s}} {{log/p}} {{log/o}}"
                                :log/s s
                                :log/p p
                                :log/o o)
                    vacc)
                  ;; else there's an _o
                  (reduce collect-p-o vacc [p o]))))

            (collect-retraction [vacc v]
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
      ;;(e-for-subject (first to-remove))
      (reduce collect-retraction [] to-remove)
      
      #_(d/transact (:conn g)
                  (into [] (map retraction to-remove)))
      #_g)))




(defmethod igraph/remove-from-graph [DatomicClient :vector]
  [g to-remove]
  g)



(defmethod igraph/remove-from-graph [DatomicClient :underspecified-triple]
  [g to-remove]
  g)



(defmethod igraph/remove-from-graph [DatomicClient :normal-form]
  [g to-remove]
  g)



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


#_(defn dummy-fn []
  "The eagle has landed")
    
;; TODO: do we need this?
#_(defn get-entity-id [db s]
  "Returns <e> for <s> in <g>
Where
<e> is the entity ID (a positive integer) in (:db <g>)
<s> is a subject s.t. [<e> ::id <s>] in (:db <g>)
<g> is an instance of DatascriptGraph
"
  (glog/debug! ::starting-get-entity-id
               :log/db db
               :log/subject s)
  
  (glog/value-debug!
   :log/get-entity-id
   [:log/subject s]
   (igraph/unique
    (d/q '[:find ?e :where [?e :igraph/kwi s]] db))))


#_(defn old_get-subjects [db]
  "Returns (<s>, ...) for <db>, a lazy seq
Where
<s> is a keyword identifier
<e> :igraph/kwi <s>, in <db>
<e> is an entity-id
<db> is a datomic db
"
  (map first
       (d/q '[:find ?s
              :where [_ :igraph/kwi ?s]] db)))

#_(defn old_query-for-p-o [db s]
  "Returns <desc> for <s> in <db>
Where
<desc> := {<p> #{<o>, ...}, ...}
"
  (letfn [(collect-desc [desc [p o]]
            (if (= p :igraph/kwi)
              desc
              (let [os (or (desc p) #{})
                    ]
                (assoc desc p (conj os o)))))
          ]
    (reduce collect-desc {}
            (d/q '[:find ?p ?o
                   :in $ ?s
                   :where
                   [?e :igraph/kwi ?s]
                   [?e ?_p ?o] 
                   [?_p :db/ident ?p]
                   ]
                 db s))))

#_(defn old-query-for-o [db s p]
  "Returns #{<o>, ...} for <s> and <p> in <db>
Where
<o> is a value for <s> and <p> in <db>
<s> is a subject in <db>
<p> is a predicate with schema definition in <db>
<db> is a datomic db
"
  (reduce conj
          #{}
          (map first
               (d/q '[:find ?o
                      :in $ ?s ?p
                      :where
                      [?e :igraph/kwi ?s]
                      [?e ?_p ?o]
                      [?_p :db/ident ?p]]
                    db s p))))
#_(defmethod igraph/add-to-graph [DatomicClient :normal-form] [g triples]
  (glog/debug! :log/starting-add-to-graph
               :log/graph g
               :log/triples triples)
  (if (empty? triples)
    g
    (let [s->db-id (atom
                    (into {}
                          (map vector
                               (keys triples)
                               (map (comp - inc) (range)))))
          check-o (fn [p o]
                    ;; returns p of o checks out else throws error
                    (when (some (complement keyword?) o)
                      (throw (ex-info
                              (str "No schema declaration for "
                                   p
                                   " and "
                                   o
                                   " contains non-keyword. "
                                   "(Will only auto-declare for refs)")
                              {:type ::no-schema-declaration
                               :p p
                               :o o})))
                    p)
          ;; find p's with no schema decl...
          no-schema (reduce-kv (fn [acc s po]
                                 (reduce-kv (fn [acc p o]
                                              (glog/debug! :log/about-to-check-o
                                                           :log/schema schema
                                                           :log/property p)
                                              (if (schema p)
                                                acc
                                                (conj acc (check-o p o))))
                                            acc
                                            po
                                            ))
                               #{}
                               triples)
          
          ]
      (letfn [(get-id [db s]
                ;; returns nil or {::value ... ::new?...}
                ;; new? means there was a keyword in object
                ;; position not covered by a subject in the
                ;; triples
                (glog/debug! :log/starting-get-id
                             :log/db db
                             :log/subject s)
                (if (not (keyword? s))
                  (glog/value-debug! :log/non-keyword-s-in-get-id
                                     [:log/subject s]
                                     nil)
                  ;; else s is a keyword
                  (if-let [id (get-entity-id db s)]
                    (do
                      {::value id
                       ::new? false})
                    (if-let [id (@s->db-id s)]
                      (do
                        {::value id
                         ::new? false})
                      ;; else this is an object with no id
                      ;; we need to add a new id for <s>
                      (do (swap! s->db-id
                                 (fn [m]
                                   (assoc m s
                                          (- (inc (count m))))))
                          {::value (@s->db-id s)
                           ::new? true})))
                  ))
              
              (collect-datom [db id p acc o]
                ;; returns {e :db/id <id>, <p> <o>}
                (glog/debug! :log/starting-collect-datom
                             :log/id id
                             :log/property p
                             :log/acc acc
                             :log/object o)
                (let [db-id (get-id db o)
                      new? (and (::value db-id) (::new? db-id))
                      o' (or (::value db-id)
                             o)
                      ]
                  (conj (if new?
                          (conj acc {:db/id o' ::id o})
                          acc)
                        {:db/id id p o'})))
              
              (collect-p-o [db id acc p o]
                ;; accumulates [<datom>...]
                (glog/debug! :log/starting-collect-p-o
                             :log/id id
                             :log/acc acc
                             :log/property p
                             :log/object o)
                (reduce (partial collect-datom db id p) acc o))


              (collect-s-po [db acc s po]
                ;; accumulates [<datom>...]
                (glog/debug! :log/starting-collect-s-po
                             :log/acc acc
                             :log/subject s
                             :log/desc po)
                (let [id (::value (get-id db s))
                      ]
                  (reduce-kv (partial collect-p-o db id)
                             (conj acc {:db/id id, ::id s})
                             po)))
              
              (update-schema [db]
                ;; Intalls default schema declaration for new refs
                (-> db
                    (update 
                     :schema
                     (fn [schema]
                       (reduce (fn [s p]
                                 (assoc s p
                                        {:db/type :db.type/ref
                                         :db/cardinality :db.cardinality/many
                                         }))
                               schema
                               no-schema)))
                    (update
                     :rschema
                     (fn [rschema]
                       (reduce (fn [r p]
                                 (->
                                  r
                                  (update :db/index #(conj (or % #{})  p))
                                  (update :db.type/ref #(conj (or % #{}) p))
                                  (update :db.cardinality/many
                                          #(conj (or % #{}) p))))
                               rschema
                               no-schema)))))

              
              ]
        (let [db' (update-schema (:db g))
              ]
          (assoc g
                 :db
                 (d/db-with db'
                            (reduce-kv (partial collect-s-po db')
                                       []
                                       triples))))))))

#_(defmethod igraph/remove-from-graph [DatomicClient :vector-of-vectors]
  [g to-remove]
  (if (empty? to-remove)
    g
    (letfn [(collect-remove-clause [acc v]
              (conj acc 
                    (case (count v)
                      1
                      (let [[s] v
                            ]
                        [:db/retractEntity [::id s]])
                      2
                      (let [[s p] v
                            ]
                        [:db.fn/retractAttribute [::id s] p])
                      3
                      (let [[s p o] v
                            o (if (= (:db/type ((:schema (:db g)) p))
                                     :db.type/ref)
                                [::id o]
                                o)
                            ]
                        [:db/retract [::id s] p o]))))
            ]
      (assoc g :db
             (d/db-with
              (:db g)
              (reduce collect-remove-clause
                      []
                      to-remove))))))

#_(defmethod igraph/remove-from-graph [DatomicClient :vector]
  [g to-remove]
  (if (empty? to-remove)
    g
    (igraph/remove-from-graph g (with-meta
                                  [to-remove]
                                  {:triples-format :vector-of-vectors}))))
#_(defmethod igraph/remove-from-graph [DatomicClient :underspecified-triple]
  [g to-remove]
  (if (empty? to-remove)
    g
    (igraph/remove-from-graph g (with-meta
                                  [to-remove]
                                  {:triples-format :vector-of-vectors}))))

#_(defmethod igraph/remove-from-graph [DatomicClient :normal-form]
  [g to-remove]
  (if (empty? to-remove)
    g
    (letfn [(collect-o [acc o]
              ;; acc is [s p]
              (conj acc o)
              )
            (collect-p [s acc p]
              ;; acc is [s]
              (reduce collect-o
                      (conj acc p)
                      (get-in to-remove [s p]))
              )
            (collect-clause-for-s [acc s]
              ;; accumulates <spo> 
              (conj acc
                    (reduce (partial collect-p s)
                            [s]
                            (keys (get to-remove s)))))

            ]
      (igraph/remove-from-graph
       g
       (with-meta
         (graph/vector-of-triples (igraph/add (graph/make-graph) to-remove))
         {:triples-format :vector-of-vectors})))))
#_(defn query-schema [db ident]
  (d/q '[:find ?ident ?valueType ?cardinality
         :in $ ?ident
         :where
         [?e :db/ident ?ident]
         [?e :db/valueType ?_valueType]
         [?e :db/cardinality ?_cardinality]
         [?_valueType :db/ident ?valueType]
         [?_cardinality :db/ident ?cardinality]
         ]
       db ident))
#_(defn- shared-keys [m1 m2]
  "Returns {<shared key>...} for <m1> and <m2>
Where
<shared key> is a key in both maps <m1> and <m2>
"
  (set/intersection (set (keys m1))
                    (set (keys m2))))

