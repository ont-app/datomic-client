(ns ont-app.datomic-client.ont
  (:require
   ;;
   [ont-app.igraph.core :as igraph :refer [add]]
   [ont-app.igraph.graph :as g :refer [make-graph]]
   [ont-app.igraph-vocabulary.core :as igv]
   [ont-app.vocabulary.core :as voc]
   )
  )
(voc/cljc-put-ns-meta!
 'ont-app.validation.ont
 {
  :vann/preferredNamespacePrefix "datomic-client"
  :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/datomic-client/ont#"
  })

(def ontology-ref (atom (make-graph)))

(defn update-ontology [to-add]
  (swap! ontology-ref add to-add))

