(ns skylobby.fx.map-sync-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.map-sync :as fx.map-sync]))


(set! *warn-on-reflection* true)


(deftest map-sync
  (is (map?
        (fx.map-sync/map-sync-pane-impl
          {:fx/context (fx/create-context nil)}))))
