(ns ont-app.datomic-client.core-test
  (:require
   #?(:cljs [cljs.test :refer-macros [async deftest is testing]]
      :clj [clojure.test :refer :all])
   [clojure.string :as str]
   ;;
   [datomic.client.api :as d]
   [ont-app.datomic-client.core :as dg :refer [make-graph]]
   [ont-app.graph-log.core :as glog]
   [ont-app.igraph.core :as igraph :refer [add]]
   [ont-app.igraph-vocabulary.core :as igv :refer [mint-kwi]]
   ))

(def glog-config (add glog/ontology
                      [[:glog/LogGraph :glog/level :glog/DEBUG]
                       ;;[:log/subject :rdf/type :glog/InformsUri]
                       ;;[:log/property :rdf/type :glog/InformsUri]
                       ;;[:log/object :rdf/type :glog/InformsUri]
                       ]))

(def cfg {:server-type :peer-server
          :access-key "myaccesskey"
          :secret "mysecret"
          :endpoint "localhost:8998"
          :validate-hostnames false})

(def client (d/client cfg))
(def conn (d/connect client {:db-name "hello"}))

#_(def igraph-schema
  [{:db/ident :igraph/kwi
    :db/valueType :db.type/keyword
    :db/unique :db.unique/identity
    :db/doc "Names the subject"
    :db/cardinality :db.cardinality/one}
   {:db/ident :igraph/top
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc (str "Indicates entities which are not otherwise elaborated."
              "Use this if you encounter a Nothing found for entity id <x>"
              "error.")
    
    }
   ])

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
                    :db/doc "The year the movie was released in theaters"}])


(defn add-schema [conn]
  (d/transact conn {:tx-data (reduce conj dg/igraph-schema movie-schema)})
  conn)

(def initial-graph (make-graph (add-schema conn)))

(defmethod mint-kwi :movie/Movie
  [head-kwi & args]
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


  
(deftest dummy-test
  (testing "fixme"
    (is (= 1 2))))
