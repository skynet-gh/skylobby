(ns skylobby.fx.scenarios-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.scenarios :as fx.scenarios]))


(set! *warn-on-reflection* true)


(deftest scenarios-root
  (is (map?
        (fx.scenarios/scenarios-root
          {:fx/context (fx/create-context nil)}))))


(deftest scenarios-window
  (is (map?
        (fx.scenarios/scenarios-window-impl
          {:fx/context (fx/create-context nil)
           :screen-bounds {}}))))
