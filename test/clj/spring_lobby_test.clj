(ns spring-lobby-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [cheshire.core :as json]
    [spring-lobby]))


(deftest add-task!
  (testing "kind and dedupe in queue"
    (let [state (atom {})]
      (spring-lobby/add-task! state {:spring-lobby/task-type :spring-lobby/fake-task})
      (spring-lobby/add-task! state {:spring-lobby/task-type :spring-lobby/fake-task})
      (spring-lobby/add-task! state {:spring-lobby/task-type :spring-lobby/reconcile-engines})
      (spring-lobby/add-task! state {:spring-lobby/task-type :spring-lobby/update-rapid})
      (is (= {:tasks-by-kind
              {:spring-lobby/other-task
               #{{:spring-lobby/task-type :spring-lobby/fake-task}}
               :spring-lobby/resource-task
               #{{:spring-lobby/task-type :spring-lobby/update-rapid}}
               :spring-lobby/index-task
               #{{:spring-lobby/task-type :spring-lobby/reconcile-engines}}}}
             @state)))))


(deftest handle-task!
  (testing "empty"
    (let [state (atom {:tasks-by-kind {}})
          changes (atom [])]
      (add-watch state :changes (fn [_k _ref _old-state new-state] (swap! changes conj new-state)))
      (try
        (spring-lobby/handle-task! state :spring-lobby/other-task)
        (is (= {:tasks-by-kind {}}
               @state))
        (is (= [{:tasks-by-kind {}}] ; no update
               @changes))
        (finally
          (remove-watch state :changes)))))
  (testing "fake task"
    (let [state (atom {:tasks-by-kind {:spring-lobby/other-task #{{:spring-lobby/task-type ::fake-task}}}})
          changes (atom [])]
      (add-watch state :changes (fn [_k _ref _old-state new-state] (swap! changes conj new-state)))
      (try
        (spring-lobby/handle-task! state :spring-lobby/other-task)
        (is (= {:current-tasks {:spring-lobby/other-task nil}
                :tasks-by-kind {:spring-lobby/other-task #{}}}
               @state))
        (is (= [{:current-tasks {:spring-lobby/other-task {:spring-lobby/task-type :spring-lobby-test/fake-task}}
                 :tasks-by-kind {:spring-lobby/other-task #{}}}
                {:current-tasks {:spring-lobby/other-task nil}
                 :tasks-by-kind {:spring-lobby/other-task #{}}}]
               @changes))
        (finally
          (remove-watch state :changes))))))


(deftest available-name
  (is (= "bot"
         (spring-lobby/available-name [] "bot")))
  (is (= "bot0"
         (spring-lobby/available-name ["bot"] "bot")))
  (is (= "bot2"
         (spring-lobby/available-name ["bot" "bot0" "bot1"] "bot"))))


(deftest parse-rapid-progress
  (is (= {:current 332922
          :total 332922}
         (spring-lobby/parse-rapid-progress "[Progress] 100% [==============================] 332922/332922")))
  (is (= {:current 0
          :total 0}
         (spring-lobby/parse-rapid-progress "[Progress]   0% [         ] 0/0")))
  (is (= {:current 171880
          :total 1024954}
         (spring-lobby/parse-rapid-progress "[Progress]  17% [=====         ] 171880/1024954"))))

(deftest process-bar-replay
  (let [replays (-> (io/resource "test/bar-replays.json")
                    slurp
                    (json/parse-string true)
                    :data)]
    (is (= 24
           (count replays)))
    (is (= {:script-data
            {:game
             {"player0" {:team 0, :username "Mitvit"},
              "player1" {:team 1, :username "Zorro"},
              "player2" {:spectator 1, :username "[Z]ow"},
              "player3" {:spectator 1, :username "Spanker"},
              "player4" {:spectator 1, :username "[Z]haffy"},
              "player5" {:spectator 1, :username "[NP]Jotunheim"},
              "player6" {:spectator 1, :username "Cow"},
              "player7" {:spectator 1, :username "SYNTHETIC"},
              "player8" {:spectator 1, :username "everic"},
              "player9" {:spectator 1, :username "kehvin"},
              "team0" {:allyteam 0, :team 0},
              "team1" {:allyteam 1, :team 1},
              :autohostaccountid "6319",
              :autohostcountrycode "HU",
              :autohostname "[teh]cluster1[11]",
              :autohostport "53111",
              :autohostrank "3",
              :gametype "Beyond All Reason test-16012-b5aa853",
              :hostip "",
              :hostport "53211",
              :hosttype "SPADS",
              :ishost "1",
              :mapname "Red Comet Remake 1.8",
              :numallyteams "2",
              :numplayers "10",
              :numrestrictions "0",
              :numteams "2",
              :startpostype "2"}}}
           (:body (spring-lobby/process-bar-replay (second replays)))))))
