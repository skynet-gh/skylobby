(ns skylobby.fx.main-tabs-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.main-tabs :as fx.main-tabs]))


(set! *warn-on-reflection* true)


(deftest main-tab-view
  (is (map?
        (fx.main-tabs/main-tab-view
          {:fx/context (fx/create-context nil)}))))
