(ns skylobby.spads-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.spads :as spads]))


(deftest parse-spads-message
  (is (= nil
         (spads/parse-spads-message ""))))
