(ns skylobby.fx.server-tab-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.server-tab :as fx.server-tab]))


(deftest server-tab
  (is (map?
        (fx.server-tab/server-tab {:fx/context (fx/create-context nil)}))))
