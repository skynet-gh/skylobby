(ns skylobby.fx.sync-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.sync :as fx.sync]))


(set! *warn-on-reflection* true)


(deftest sync-pane
  (is (map?
        (fx.sync/sync-pane
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.sync/sync-pane
          {:fx/context (fx/create-context nil)
           :issues [{}]}))))
