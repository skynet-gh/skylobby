(ns skylobby.fx.engines-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.engines :as fx.engines]))


(set! *warn-on-reflection* true)


(deftest engines-view
  (is (map?
        (fx.engines/engines-view-impl
          {:fx/context (fx/create-context nil)}))))
