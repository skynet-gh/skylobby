(ns skylobby.fx.mod-sync-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.mod-sync :as fx.mod-sync]))


(set! *warn-on-reflection* true)


(deftest mod-sync
  (is (map?
        (fx.mod-sync/mod-sync-pane-impl
          {:fx/context (fx/create-context nil)}))))
