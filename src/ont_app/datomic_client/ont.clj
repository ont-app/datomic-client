(ns ont-app.datomic-client.ont
  {
  :vann/preferredNamespacePrefix "datomic-client"
  :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/datomic-client/ont#"
  }
  (:require
   ;;
   [ont-app.igraph.core :as igraph :refer [add]]
   [ont-app.igraph.graph :as g :refer [make-graph]]
   [ont-app.igraph-vocabulary.core :as igv]
   [ont-app.vocabulary.core :as voc]
   )
  )

(def ontology-atom (atom (make-graph)))

;; TODO: IS THERE A PUBLIC ONTOLOGY FOR THIS?

(defn update-ontology [to-add]
  (swap! ontology-atom add to-add))

