(ns ont-app.datomic-client.core-test
  (:require
   #?(:cljs [cljs.test :refer-macros [async deftest is testing]]
      :clj [clojure.test :refer :all])
   [ont-app.datomic-client.core]
   ))

(deftest dummy-test
  (testing "fixme"
    (is (= 1 2))))
