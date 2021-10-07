(ns skylobby.fx.tasks-test
  (:require
    [cljfx.api :as fx]
    [clojure.test :refer [deftest is]]
    [skylobby.fx.tasks :as fx.tasks]))


(deftest tasks-window
  (is (map?
        (fx.tasks/tasks-window
          {:fx/context (fx/create-context nil)})))
  (is (map?
        (fx.tasks/tasks-window
          {:fx/context (fx/create-context {:show-tasks-window true})}))))
