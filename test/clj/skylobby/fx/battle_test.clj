(ns skylobby.fx.battle-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battle :as fx.battle]))


(set! *warn-on-reflection* true)


(deftest battle-buttons
  (is (map?
        (fx.battle/battle-buttons
          {:fx/context (fx/create-context nil)}))))


(deftest battle-tabs
  (is (map?
        (fx.battle/battle-tabs
          {:fx/context (fx/create-context nil)}))))


(deftest battle-view
  (is (map?
        (fx.battle/battle-view
          {:fx/context (fx/create-context nil)}))))
