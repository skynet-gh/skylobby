(ns skylobby.fx.battles-buttons-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battles-buttons :as fx]))


(deftest battles-buttons-view
  (is (map?
        (fx/battles-buttons-view nil))))
