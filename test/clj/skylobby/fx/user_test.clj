(ns skylobby.fx.user-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.user :as fx.user]))


(deftest users-table
  (is (map?
        (fx.user/users-table {:fx/context (fx/create-context nil)}))))
