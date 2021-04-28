(ns skylobby.fx.channels-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.channels :as fx]))


(deftest channels-table
  (is (map?
        (fx/channels-table nil))))
