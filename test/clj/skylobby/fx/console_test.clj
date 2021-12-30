(ns skylobby.fx.console-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.console :as fx.console]))


(set! *warn-on-reflection* true)


(deftest console-view
  (is (map?
        (fx.console/console-view
          {:fx/context (fx/create-context nil)}))))
