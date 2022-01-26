(ns skylobby.fx.minimap-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.minimap :as fx.minimap]))


(set! *warn-on-reflection* true)


(deftest minimap-pane-impl
  (is (map?
        (fx.minimap/minimap-pane-impl
          {:fx/context (fx/create-context nil)}))))
