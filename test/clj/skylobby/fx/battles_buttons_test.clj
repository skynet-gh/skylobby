(ns skylobby.fx.battles-buttons-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.battles-buttons :as fx.battles-buttons]))


(set! *warn-on-reflection* true)


(deftest battles-buttons-view
  (is (map?
        (fx.battles-buttons/battles-buttons-view
          {:fx/context (fx/create-context nil)}))))
