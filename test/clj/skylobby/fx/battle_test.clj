(ns skylobby.fx.battle-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battle :as fx]))


(deftest split-by
  (is (= []
         (fx/split-by (constantly false) [])))
  (is (= [[:x :y :y :y]
          [:x :y :y]
          [:x :y]
          [:x :y :y]]
         (fx/split-by #{:x} [:x :y :y :y :x :y :y :x :y :x :y :y]))))


(deftest modoptions-table
  (is (map?
        (fx/modoptions-table nil)))
  (is (map?
        (fx/modoptions-table
          {:modoptions
           [[:1 {:type "section"}]
            [:2 {:type "number"}]
            [:2 {:type "list"}]]}))))


(deftest modoptions-view
  (is (map?
        (fx/modoptions-view nil)))
  (is (map?
        (fx/modoptions-view
          {:modoptions
           [[:1 {:type "section"}]
            [:2 {:type "number"}]
            [:2 {:type "list"}]]}))))


(deftest battle-buttons
  (is (map?
        (fx/battle-buttons nil)))
  (is (map?
        (fx/battle-buttons {:singleplayer true}))))


(deftest battle-tabs
  (is (map?
        (fx/battle-tabs nil))))


(deftest battle-view
  (is (map?
        (fx/battle-view nil))))
