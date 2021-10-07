(ns skylobby.fx.battle-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battle :as fx.battle]))


(deftest split-by
  (is (= []
         (fx.battle/split-by (constantly false) [])))
  (is (= [[:x :y :y :y]
          [:x :y :y]
          [:x :y]
          [:x :y :y]]
         (fx.battle/split-by #{:x} [:x :y :y :y :x :y :y :x :y :x :y :y]))))


(deftest modoptions-table
  (is (map?
        (fx.battle/modoptions-table {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.battle/modoptions-table
          {:fx/context
           (fx/create-context
             {:modoptions
              [[:1 {:type "section"}]
               [:2 {:type "number"}]
               [:2 {:type "list"}]]})}))))


(deftest modoptions-view
  (is (map?
        (fx.battle/modoptions-view {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.battle/modoptions-view
          {:fx/context
           (fx/create-context
             {:modoptions
              [[:1 {:type "section"}]
               [:2 {:type "number"}]
               [:2 {:type "list"}]]})}))))


(deftest battle-buttons
  (is (map?
        (fx.battle/battle-buttons {:fx/context (fx/create-context nil)}))))


(deftest battle-tabs
  (is (map?
        (fx.battle/battle-tabs {:fx/context (fx/create-context nil)}))))


(deftest battle-view
  (is (map?
        (fx.battle/battle-view {:fx/context (fx/create-context nil)}))))
