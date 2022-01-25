(ns skylobby.fx.server-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.server :as fx.server]))


(set! *warn-on-reflection* true)


(deftest servers-window
  (is (map?
        (fx.server/servers-window-impl
          {:fx/context (fx/create-context nil)
           :screen-bounds {}})))
  (is (map?
        (fx.server/servers-window-impl
          {:fx/context (fx/create-context {:show-servers-window true})
           :screen-bounds {}}))))
