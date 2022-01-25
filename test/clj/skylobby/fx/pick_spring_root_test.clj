(ns skylobby.fx.pick-spring-root-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.pick-spring-root :as fx.pick-spring-root]))


(set! *warn-on-reflection* true)


(deftest pick-spring-root-view
  (is (map?
        (fx.pick-spring-root/pick-spring-root-view
          {:fx/context (fx/create-context nil)}))))
