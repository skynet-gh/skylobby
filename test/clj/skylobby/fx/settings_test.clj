(ns skylobby.fx.settings-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.settings :as fx.settings]))


(set! *warn-on-reflection* true)


(deftest battle-settings
  (is (map?
        (fx.settings/battle-settings
          {:fx/context (fx/create-context nil)}))))


(deftest settings-root
  (is (map?
        (fx.settings/settings-root
          {:fx/context (fx/create-context nil)}))))


(deftest settings-window
  (is (map?
        (fx.settings/settings-window-impl
          {:fx/context (fx/create-context nil)
           :screen-bounds {}}))))
