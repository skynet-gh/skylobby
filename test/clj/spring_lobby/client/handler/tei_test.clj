(ns spring-lobby.client.handler.tei-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [spring-lobby.client.handler :as handler]
    spring-lobby.client.handler.tei))


(deftest handle-full-queue-list
  (testing "update all matchmaking queues"
    (let [state (atom {:matchmaking-queues {:bleh {:blah :blah}}})]
      (handler/handle nil state "s.matchmaking.full_queue_list 1v1\t2v2\t3v3\tffa")
      (is (= {:matchmaking-queues
              {"1v1" {}
               "2v2" {}
               "3v3" {}
               "ffa" {}}}
             @state)))))

(deftest handle-your-queue-list
  (testing "update my matchmaking queues"
    (let [state (atom {:matchmaking-queues
                       {"1v1" {:am-in true}
                        "2v2" {:am-in true}
                        "3v3" {:am-in false}
                        "ffa" {}}})]
      (handler/handle nil state "s.matchmaking.your_queue_list 2v2\t3v3")
      (is (= {:matchmaking-queues
              {"1v1" {:am-in false}
               "2v2" {:am-in true}
               "3v3" {:am-in true}
               "ffa" {:am-in false}}}
             @state)))))

(deftest handle-queue-info
  (testing "update matchmaking queue info"
    (let [state (atom {:matchmaking-queues
                       {"1v1" {:am-in false}
                        "2v2" {:am-in true}
                        "3v3" {:am-in true}
                        "ffa" {:am-in false}}})]
      (handler/handle nil state "s.matchmaking.queue_info 2v2 12345 987")
      (is (= {:matchmaking-queues
              {"1v1" {:am-in false}
               "2v2" {:am-in true
                      :current-search-time 12345
                      :current-size 987}
               "3v3" {:am-in true}
               "ffa" {:am-in false}}}
             @state)))))

(deftest handle-ready-check
  (testing "update matchmaking queue ready check"
    (let [state (atom {:matchmaking-queues
                       {"1v1" {:am-in false}
                        "2v2" {:am-in true}
                        "3v3" {:am-in true}
                        "ffa" {:am-in false}}})]
      (handler/handle nil state "s.matchmaking.ready_check 1v1")
      (is (= {:matchmaking-queues
              {"1v1" {:am-in false
                      :ready-check true}
               "2v2" {:am-in true}
               "3v3" {:am-in true}
               "ffa" {:am-in false}}}
             @state)))))

(deftest handle-cancelled
  (testing "update matchmaking queue match cancelled"
    (let [state (atom {:matchmaking-queues
                       {"1v1" {:am-in false
                               :ready-check true}
                        "2v2" {:am-in true}
                        "3v3" {:am-in true
                               :ready-check true}
                        "ffa" {:am-in false}}})]
      (handler/handle nil state "s.matchmaking.match_cancelled 3v3")
      (is (= {:matchmaking-queues
              {"1v1" {:am-in false
                      :ready-check true}
               "2v2" {:am-in true}
               "3v3" {:am-in true
                      :ready-check false}
               "ffa" {:am-in false}}}
             @state)))))
