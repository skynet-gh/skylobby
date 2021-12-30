(ns skylobby.fx.spring-options-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.spring-options :as fx.spring-options]))


(set! *warn-on-reflection* true)


(deftest split-by
  (is (= []
         (fx.spring-options/split-by (constantly false) [])))
  (is (= [[:x :y :y :y]
          [:x :y :y]
          [:x :y]
          [:x :y :y]]
         (fx.spring-options/split-by #{:x} [:x :y :y :y :x :y :y :x :y :x :y :y]))))

(deftest modoptions-table
  (is (map?
        (fx.spring-options/modoptions-table {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.spring-options/modoptions-table
          {:fx/context
           (fx/create-context
             {:modoptions
              [[:1 {:type "section"}]
               [:2 {:type "number"}]
               [:2 {:type "list"}]]})}))))

(deftest modoptions-view
  (is (map?
        (fx.spring-options/modoptions-view {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.spring-options/modoptions-view
          {:fx/context
           (fx/create-context
             {:modoptions
              [[:1 {:type "section"}]
               [:2 {:type "number"}]
               [:2 {:type "list"}]]})}))))
