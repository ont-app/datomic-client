(ns ont-app.datomic-client.core-test
  (:require
   [clojure.test :refer :all]
   [clojure.string :as str]
   ;; 3rd party
   [datomic.client.api :as d]
   [environ.core :refer [env]]
   ;; ont-app
   [ont-app.datomic-client.core :as dg :refer :all]
   [ont-app.graph-log.core :as glog]
   [ont-app.igraph.core :as igraph :refer :all]
   [ont-app.igraph.core-test :as igraph-test]
   [ont-app.igraph.graph :as g]
   [ont-app.igraph-vocabulary.core :as igv :refer [mint-kwi]]
   ))


(def glog-config (add glog/ontology
                      [[:glog/LogGraph :glog/level :glog/DEBUG]
                       ]))

(def cfg {:server-type :peer-server
          :access-key (env :datomic-access-key) ;;"myaccesskey"
          :secret (env :datomic-secret) ;;"mysecret"
          :endpoint (str (env :datomic-host) ":" (env :datomic-port))
          ;; ... "localhost:8998"
          :validate-hostnames false})

(def client (d/client cfg))
(def conn (d/connect client {:db-name "hello"}))

(defn retract-content [conn]
  "Retracts all non-schema content from the db at head of `conn`."
  (let [g (dg/make-graph conn)]
    (doseq [s (filter (fn [s] (not (g s :db/ident)))
                      (subjects (dg/make-graph conn)))]
      (retract g [s]))))

(def movie-schema [{:db/ident :movie/title
                    :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one
                    :db/doc "The title of the movie"}

                   {:db/ident :movie/genre
                    :db/valueType :db.type/string
                    :db/cardinality :db.cardinality/one
                    :db/doc "The genre of the movie"}

                   {:db/ident :movie/release-year
                    :db/valueType :db.type/long
                    :db/cardinality :db.cardinality/one
                    :db/doc "The year the movie was released in theaters"}
                   ])


#_(defn add-schema [conn]
  (d/transact conn {:tx-data (reduce conj dg/igraph-schema movie-schema)})
  conn)

;; (def initial-graph (make-graph (add-schema conn)))

(defmethod mint-kwi :movie/Movie
  [head-kwi & args]
  ;; Generates unique KWI for <title> made in <year>
  (let [{title :movie/title
         year :movie/release-year
         }
        args
        _ns (namespace head-kwi)
        _name (name head-kwi)
        stringify (fn [x]
                    (cond (string? x) (str/replace x #" " "_")
                          (keyword? x) (name x)
                          :default (str x))) 
        kwi (keyword _ns (str _name "_" (str/join "_"
                                                  [(stringify title)
                                                   (or year "NoDate")])))
        ]
    kwi))



(defn add-movie-spec [vacc [title date genre]]
  (conj vacc
        [(mint-kwi :movie/Movie
                   :movie/title title
                   :movie/date date)
         :movie/title title
         :movie/release-date date
         :movie/genre genre]))

(def first-movies (reduce add-movie-spec
                          []
                          [["The Goonies" 1985 "action/adventure"]
                           ["Commando" 1985 "action/adventure"]
                           ["Repo Man" 1985 "punk dystopia"]
                           ]))

#_(defn add-data [data]
  (d/transact conn {:tx-data data}))



;; See https://docs.datomic.com/cloud/transactions/transaction-data-reference.html
;; for complete spec on the transaction data map


;; (def g (make-graph conn))

(defn add-eg-type-schema [conn]
  (d/transact
   conn
   {:tx-data
    [{:db/ident :ig-ctest/subClassOf
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/many
      :db/doc "Toy subclass-of relation"
      }
     {:db/ident :ig-ctest/isa
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/many
      :db/doc "Toy instance-of relation"
      }
     {:db/ident :ig-ctest/likes
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/many
      :db/doc "Toy likes relation"
      }
     {:db/ident :ig-ctest/has-vector
      :db/valueType :db.type/string
      :igraph/edn? true
      :db/cardinality :db.cardinality/many
      :db/doc "Stores a vector as an edn string"
      }
     ]}))



(defn build-readme-graphs [conn]
  "Side-effects: sets up eg, eg-with-types other-eg eg-for-cardinality-1 graphs in igraph-test module.
"
  (retract-content conn)
  (add-eg-type-schema conn)
  (reset! igraph-test/initial-graph (dg/make-graph conn))
  (reset! igraph-test/eg (igraph/claim (dg/make-graph conn)
                                       igraph-test/eg-data))
  
  (reset! igraph-test/eg-with-types
          (let []
            (igraph/claim (dg/make-graph conn)
                          (glog/value-warn!
                           ::eg-with-types-claims
                           (normal-form
                            (union (add (g/make-graph)
                                        igraph-test/eg-data)
                                   (add (g/make-graph)
                                        igraph-test/types-data)))))))
  
  (reset! igraph-test/eg-for-cardinality-1
          (igraph/claim (dg/make-graph conn)
                        igraph-test/cardinality-1-appendix))
  (retract-content conn)
  (reset! igraph-test/other-eg (igraph/claim (dg/make-graph conn)
                                             igraph-test/other-eg-data)))


(deftest readme
  (testing "igraph readme"
    (build-readme-graphs conn)
    (igraph-test/readme)))



(comment
  (claim initial-graph [::a ::b ::c])
  )
