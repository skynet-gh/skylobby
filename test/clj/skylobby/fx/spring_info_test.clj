(ns skylobby.fx.spring-info-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.spring-info :as fx.spring-info]))


(set! *warn-on-reflection* true)


(deftest spring-info-root
  (is (map?
        (fx.spring-info/spring-info-root
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.spring-info/spring-info-root
          {:fx/context (fx/create-context {:spring-crash-archive-not-found {}})}))))


(deftest spring-info-window
  (is (map?
        (fx.spring-info/spring-info-window
          {:fx/context (fx/create-context nil)
           :screen-bounds {}}))))
