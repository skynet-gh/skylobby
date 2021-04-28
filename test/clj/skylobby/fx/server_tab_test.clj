(ns skylobby.fx.server-tab-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.server-tab :as fx]))


(deftest server-tab
  (is (map?
        (fx/server-tab nil))))
