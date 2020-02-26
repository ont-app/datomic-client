(defproject ont-app/datomic-client "0.1.0"
  :description "Extends IGraph protocol to Datomic"
  :url "https://github.com/ont-app/datomic-client"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/spec.alpha "0.2.176"]
                 ;; 3rd party libs
                 [com.datomic/client-pro "0.9.43"]
                 [environ "1.1.0"]
                 ;; Ont-app libs
                 [ont-app/graph-log "0.1.0"]
                 [ont-app/igraph "0.1.4"]
                 [ont-app/igraph-vocabulary "0.1.0"] 
                 [ont-app/vocabulary "0.1.0"] 
                 ]
  
  ;; :main ^:skip-aot ont-app.datomic-client.core
  :target-path "target/%s"
  :resource-paths ["resources" "target/cljsbuild"]
  
  :plugins [[lein-codox "0.10.6"]
            ]
  :source-paths ["src"]
  :test-paths ["src" "test"]

  :codox {:output-path "doc"}

  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["src"]
                   }
             })
