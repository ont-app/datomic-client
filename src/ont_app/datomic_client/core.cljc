(ns ont-app.datomic-client.core
    (:require
     [ont-app.datomic-client.ont :as ont]
     ))

(voc/cljc-put-ns-meta!
 'ont-app.datomic-client.core
 {
  :voc/mapsTo 'ont-app.datomic-client.ont
  }
 )

(def ontology ont/ontology)

;; FUN WITH READER MACROS

#?(:cljs
   (enable-console-print!)
   )

#?(:cljs
   (defn on-js-reload [] )
   )

;; NO READER MACROS BEYOND THIS POINT

(defn dummy-fn []
  "The eagle has landed")
    
