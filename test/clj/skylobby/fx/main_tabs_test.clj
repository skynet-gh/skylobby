(ns skylobby.fx.main-tabs-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.main-tabs :as fx.main-tabs]))


(set! *warn-on-reflection* true)


(deftest my-channels-view-impl
  (is (map?
        (fx.main-tabs/my-channels-view-impl
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.main-tabs/my-channels-view-impl
          {:fx/context (fx/create-context {:by-server {"server" {:my-channels {"c1" {}}}}})
           :server "sever"}))))


(deftest battle-details
  (is (map?
        (fx.main-tabs/battle-details
          {:fx/context (fx/create-context nil)}))))


(deftest main-tab-view
  (is (map?
        (fx.main-tabs/main-tab-view
          {:fx/context (fx/create-context nil)}))))
