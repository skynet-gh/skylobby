(ns skylobby.fx.main-tabs-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.players-table :as fx]))


(set! *warn-on-reflection* true)


(deftest players-table
  (is (map?
        (fx/players-table nil))))
