(ns skylobby.fx.user-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.user :as fx.user]))


(set! *warn-on-reflection* true)


(deftest users-view
  (is (map?
        (fx.user/users-view-impl
          {:fx/context (fx/create-context nil)}))))



(deftest users-table
  (is (map?
        (fx.user/users-table-impl
          {:fx/context (fx/create-context nil)}))))
