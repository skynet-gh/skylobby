(ns skylobby.fx.engine-sync-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.engine-sync :as fx.engine-sync]))


(set! *warn-on-reflection* true)


(deftest engine-sync
  (is (map?
        (fx.engine-sync/engine-sync-pane-impl
          {:fx/context (fx/create-context nil)}))))
