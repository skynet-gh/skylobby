(ns skylobby.fx.battle-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battle :as fx]))


(deftest battle-view
  (is (map?
        (fx/battle-view nil))))
