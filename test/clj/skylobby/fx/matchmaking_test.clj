(ns skylobby.fx.matchmaking-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.matchmaking :as fx.matchmaking]))


(set! *warn-on-reflection* true)


(deftest matchmaking-view
  (is (map?
        (fx.matchmaking/matchmaking-view
          {:fx/context (fx/create-context nil)}))))


(deftest matchmaking-window
  (is (map?
        (fx.matchmaking/matchmaking-window-impl
          {:fx/context (fx/create-context nil)}))))
