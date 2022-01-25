(ns skylobby.fx.mods-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.mods :as fx.mods]))


(set! *warn-on-reflection* true)


(deftest mods-view
  (is (map?
        (fx.mods/mods-view-impl
          {:fx/context (fx/create-context nil)}))))
