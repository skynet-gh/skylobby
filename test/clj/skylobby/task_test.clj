(ns skylobby.task-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [skylobby.task :as task]))


(set! *warn-on-reflection* true)


(deftest add-task!
  (testing "kind and dedupe in queue"
    (let [state (atom {})]
      (task/add-task! state {:spring-lobby/task-type :spring-lobby/fake-task})
      (task/add-task! state {:spring-lobby/task-type :spring-lobby/fake-task})
      (task/add-task! state {:spring-lobby/task-type :spring-lobby/refresh-engines})
      (task/add-task! state {:spring-lobby/task-type :spring-lobby/download-and-extract})
      (task/add-task! state {:spring-lobby/task-type :spring-lobby/update-rapid})
      (is (= {:tasks-by-kind
              {:spring-lobby/other-task
               #{{:spring-lobby/task-type :spring-lobby/fake-task}}
               :spring-lobby/index-task
               #{{:spring-lobby/task-type :spring-lobby/refresh-engines}}
               :spring-lobby/download-task
               #{{:spring-lobby/task-type :spring-lobby/download-and-extract}}
               :spring-lobby/rapid-task
               #{{:spring-lobby/task-type :spring-lobby/update-rapid}}}}
             @state)))))
