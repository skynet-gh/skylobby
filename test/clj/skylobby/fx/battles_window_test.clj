(ns skylobby.fx.battles-window-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battles-window :as fx.battles-window]))


(set! *warn-on-reflection* true)


(deftest battles-window
  (is (map?
        (fx.battles-window/battles-window-impl
          {:fx/context (fx/create-context nil)}))))
