(ns skylobby.fx.main-tabs-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.players-table :as fx]))


(deftest players-table
  (is (map?
        (fx/players-table nil))))
