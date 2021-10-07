(ns skylobby.fx.channels-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.channels :as fx.channels]))


(deftest channels-table
  (is (map?
        (fx.channels/channels-table {:fx/context (fx/create-context nil)}))))
