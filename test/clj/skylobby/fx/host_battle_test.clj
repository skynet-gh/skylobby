(ns skylobby.fx.host-battle-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.host-battle :as fx.host-battle]))


(set! *warn-on-reflection* true)


(deftest host-battle-window
  (is (map?
        (fx.host-battle/host-battle-window-impl
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.host-battle/host-battle-window-impl
          {:fx/context (fx/create-context {:show-host-battle-window true})}))))
