(ns skylobby.fx.console-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.console :as fx]))


(deftest console-view
  (is (map?
        (fx/console-view nil))))
