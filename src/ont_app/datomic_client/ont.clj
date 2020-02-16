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

(def ontology-atom
  "An igraph.graph/Graph describing the various elements in
  datomic-client. Primarily documentatry at this point. "
  (atom (make-graph)))

(defn update-ontology! [to-add]
  (swap! ontology-atom add to-add))

(update-ontology! ;; General Datomic stuff
 [[:datomic-client/Ident
   :rdfs/comment "Refers to an Entity uniquely identified in memory"
   ]
  [:datomic-client/Attribute
   :rdfs/subClassOf :datomic-client/Ident
   :rdfs/comment "Refers to a relation between an integer 'entity' index in some datomic DB and some other element of that DB."]
  [:datomic-client/StandardSchemaElement
   :rdf/type :rdfs/Class
   :rdfs/comment "Refers to graph element which is part of Datomic's standard 
schema. These should be documented at https://docs.datomic.com/"
   ]])
  

(update-ontology! ;; IGraph-specific stuff
 [
  [:igraph/kwi :rdf/type :datomic-client/Attribute
   :rdfs/comment
   "Asserts that <entity> has keyword <kwi> as a unique identifier."
   ]
  [:igraph/edn? :rdf/type :datomic-client/Attribute
   :rdfs/comment
   "Asserts that the entity is an attribute whose value is stored as an EDN 
string, and should be encoded/read accordingly."
   ]
  ]
 )

(update-ontology! ;; Standard schema elements (mostly auto-generated)
 ;; TODO: consider fleshing this out for documentary purposes
 [
  [:db/add :rdf/type :datomic-client/StandardSchemaElement]
  [:db/cardinality :rdf/type :datomic-client/StandardSchemaElement]
  [:db/cas :rdf/type :datomic-client/StandardSchemaElement]
  [:db/code :rdf/type :datomic-client/StandardSchemaElement]
  [:db/doc :rdf/type :datomic-client/StandardSchemaElement]
  [:db/ensure :rdf/type :datomic-client/StandardSchemaElement]
  [:db/excise :rdf/type :datomic-client/StandardSchemaElement]
  [:db/fn :rdf/type :datomic-client/StandardSchemaElement]
  [:db/fulltext :rdf/type :datomic-client/StandardSchemaElement]
  [:db/ident :rdf/type :datomic-client/StandardSchemaElement]
  [:db/index :rdf/type :datomic-client/StandardSchemaElement]
  [:db/isComponent :rdf/type :datomic-client/StandardSchemaElement]
  [:db/lang :rdf/type :datomic-client/StandardSchemaElement]
  [:db/noHistory :rdf/type :datomic-client/StandardSchemaElement]
  [:db/retract :rdf/type :datomic-client/StandardSchemaElement]
  [:db/retractEntity :rdf/type :datomic-client/StandardSchemaElement]
  [:db/system-tx :rdf/type :datomic-client/StandardSchemaElement]
  [:db/tupleAttrs :rdf/type :datomic-client/StandardSchemaElement]
  [:db/tupleType :rdf/type :datomic-client/StandardSchemaElement]
  [:db/tupleTypes :rdf/type :datomic-client/StandardSchemaElement]
  [:db/txInstant :rdf/type :datomic-client/StandardSchemaElement]
  [:db/unique :rdf/type :datomic-client/StandardSchemaElement]
  [:db/valueType :rdf/type :datomic-client/StandardSchemaElement]
  [:db.alter/attribute :rdf/type :datomic-client/StandardSchemaElement]
  [:db.attr/preds :rdf/type :datomic-client/StandardSchemaElement]
  [:db.bootstrap/part :rdf/type :datomic-client/StandardSchemaElement]
  [:db.cardinality/many :rdf/type :datomic-client/StandardSchemaElement]
  [:db.cardinality/one :rdf/type :datomic-client/StandardSchemaElement]
  [:db.entity/attrs :rdf/type :datomic-client/StandardSchemaElement]
  [:db.entity/preds :rdf/type :datomic-client/StandardSchemaElement]
  [:db.excise/attrs :rdf/type :datomic-client/StandardSchemaElement]
  [:db.excise/before :rdf/type :datomic-client/StandardSchemaElement]
  [:db.excise/beforeT :rdf/type :datomic-client/StandardSchemaElement]
  [:db.fn/cas :rdf/type :datomic-client/StandardSchemaElement]
  [:db.fn/retractEntity :rdf/type :datomic-client/StandardSchemaElement]
  [:db.install/attribute :rdf/type :datomic-client/StandardSchemaElement]
  [:db.install/function :rdf/type :datomic-client/StandardSchemaElement]
  [:db.install/partition :rdf/type :datomic-client/StandardSchemaElement]
  [:db.install/valueType :rdf/type :datomic-client/StandardSchemaElement]
  [:db.lang/clojure :rdf/type :datomic-client/StandardSchemaElement]
  [:db.lang/java :rdf/type :datomic-client/StandardSchemaElement]
  [:db.part/db :rdf/type :datomic-client/StandardSchemaElement]
  [:db.part/tx :rdf/type :datomic-client/StandardSchemaElement]
  [:db.part/user :rdf/type :datomic-client/StandardSchemaElement]
  [:db.sys/partiallyIndexed :rdf/type :datomic-client/StandardSchemaElement]
  [:db.sys/reId :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/bigdec :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/bigint :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/boolean :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/bytes :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/double :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/float :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/fn :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/instant :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/keyword :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/long :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/ref :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/string :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/symbol :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/tuple :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/uri :rdf/type :datomic-client/StandardSchemaElement]
  [:db.type/uuid :rdf/type :datomic-client/StandardSchemaElement]
  [:db.unique/identity :rdf/type :datomic-client/StandardSchemaElement]
  [:db.unique/value :rdf/type :datomic-client/StandardSchemaElement]
  [:fressian/tag :rdf/type :datomic-client/StandardSchemaElement]]
 )
