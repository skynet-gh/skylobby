(ns skylobby.fx.tasks-test
  (:require
    [clojure.test :refer [deftest is]]
    [skylobby.fx.tasks :as fx]))


(deftest tasks-window
  (is (map?
        (fx/tasks-window nil)))
  (is (map?
        (fx/tasks-window {:show-tasks-window true}))))
