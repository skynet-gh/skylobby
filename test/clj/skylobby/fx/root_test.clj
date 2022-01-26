(ns skylobby.fx.root-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.root :as fx.root]))


(set! *warn-on-reflection* true)


(deftest battle-window
  (is (map?
        (fx.root/battle-window
          {:fx/context (fx/create-context nil)
           :screen-bounds {}}))))

(deftest root-view
  (is (map?
        (fx.root/root-view-impl {:fx/context (fx/create-context nil)}))))
