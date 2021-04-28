(ns skylobby.fx.user-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.user :as fx]))


(deftest users-table
  (is (map?
        (fx/users-table nil))))
