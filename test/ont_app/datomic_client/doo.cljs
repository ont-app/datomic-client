(ns ont-app.datomic-client.doo
  (:require [doo.runner :refer-macros [doo-tests]]
            [ont-app.datomic-client.core-test]
            ))

(doo-tests
 'ont-app.datomic-client.core-test
 )
