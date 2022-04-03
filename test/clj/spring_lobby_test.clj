(ns spring-lobby-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [cheshire.core :as json]
    [spring-lobby]))


(set! *warn-on-reflection* true)


(deftest handle-task!
  (testing "empty"
    (let [state (atom {:tasks-by-kind {}})
          changes (atom [])]
      (add-watch state :changes (fn [_k _ref _old-state new-state] (swap! changes conj new-state)))
      (try
        (spring-lobby/handle-task! state :spring-lobby/other-task)
        (is (= {:tasks-by-kind {}}
               @state))
        (is (= [] ; no update
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
                :tasks-by-kind {:spring-lobby/other-task #{}}
                :task-threads nil}
               @state))
        (is (= [{:current-tasks {:spring-lobby/other-task {:spring-lobby/task-type :spring-lobby-test/fake-task}}
                 :tasks-by-kind {:spring-lobby/other-task #{}}}
                {:current-tasks {:spring-lobby/other-task nil}
                 :tasks-by-kind {:spring-lobby/other-task #{}}
                 :task-threads nil}]
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


(deftest parse-battle-status-message
  (is (= []
         (spring-lobby/parse-battle-status-message "")))
  (is (= [{:ally "1"
           :battle-status {:ally 0
                           :id 0
                           :mode true}
           :clan ""
           :id "1"
           :rank "3"
           :ready ""
           :skill "(20)"
           :skilluncertainty 0
           :user-id "MFAI : normal (nebula3)"
           :username "bot1 (bot)"}
          {:ally "2"
           :battle-status {:ally 1
                           :id 1
                           :mode true}
           :clan ""
           :id "2"
           :rank "3"
           :ready ""
           :skill "(20)"
           :skilluncertainty 0
           :user-id "MFAI : normal (nebula3)"
           :username "bot2 (bot)"}
          {:ally ""
           :battle-status {:ally nil
                           :id nil
                           :mode false}
           :clan ""
           :id ""
           :rank "6"
           :ready ""
           :skill ""
           :skilluncertainty 0
           :user-id "968127"
           :username "nebula3"}]
         (spring-lobby/parse-battle-status-message
           (str
             "\n"
             "Name        Team  Id  Clan  Ready  Rank  Skill  ID\n"
             "----------  ----  --  ----  -----  ----  -----  -----------------------\n"
             "bot1 (bot)  1     1                3     (20)   MFAI : normal (nebula3)\n"
             "bot2 (bot)  2     2                3     (20)   MFAI : normal (nebula3)\n"
             "nebula3                            6            968127\n"
             "=======================================================================")))))


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


(deftest balance-teams
  (is (= []
         (spring-lobby/balance-teams nil 2)))
  (let [players [{:battle-status {:ally 0
                                  :id 0
                                  :mode 1}
                  :username "skynet"}
                 {:ai-name "NullAI"
                  :ai-version "0.1"
                  :battle-status {:ally 3
                                  :id 3
                                  :mode 1}
                  :bot-name "bot1"
                  :owner "skynet"
                  :user {:client-status {:bot true}}}
                 {:ai-name "NullAI"
                  :ai-version "0.1"
                  :battle-status {:ally 4
                                  :id 4
                                  :mode 1}
                  :bot-name "bot2"
                  :owner "skynet"
                  :user {:client-status {:bot true}}}
                 {:ai-name "NullAI"
                  :ai-version "0.1"
                  :battle-status {:ally 1
                                  :id 1
                                  :mode 1}
                  :bot-name "bot3"
                  :owner "skynet"
                  :user {:client-status {:bot true}}}
                 {:ai-name "NullAI"
                  :ai-version "0.1"
                  :battle-status {:ally 2
                                  :id 2
                                  :mode 1}
                  :bot-name "bot4"
                  :owner "skynet"
                  :user {:client-status {:bot true}}}]]
    (is (= [{:ally 0
             :id 0}
            {:ally 0
             :id 1}
            {:ally 0
             :id 2}
            {:ally 1
             :id 3}
            {:ally 1
             :id 4}]
           (map :status-changes
             (spring-lobby/balance-teams players 2))))
    (is (= [{:ally 0
             :id 0}
            {:ally 0
             :id 1}
            {:ally 1
             :id 2}
            {:ally 1
             :id 3}
            {:ally 2
             :id 4}]
           (map :status-changes
             (spring-lobby/balance-teams players 3))))
    (is (= [{:ally 0
             :id 0}
            {:ally 0
             :id 1}
            {:ally 1
             :id 2}
            {:ally 2
             :id 3}
            {:ally 3
             :id 4}]
           (map :status-changes
             (spring-lobby/balance-teams players 4))))
    (is (= [{:ally 0
             :id 0}
            {:ally 1
             :id 1}
            {:ally 2
             :id 2}
            {:ally 3
             :id 3}
            {:ally 4
             :id 4}]
           (map :status-changes
             (spring-lobby/balance-teams players 5))))))


(deftest update-needs-focus
  (is (= {}
         (spring-lobby/update-needs-focus "server" "chat" "channel" {"server" {"chat" {"channel" true}}})))
  (is (= {}
         (spring-lobby/update-needs-focus "server" "battle" :battle {"server" {"battle" {:battle true}}})))
  (is (= {"server" {"battle" {:battle true}}}
         (spring-lobby/update-needs-focus "server" "chat" "channel" {"server" {"chat" {"channel" true}
                                                                               "battle" {:battle true}}})))
  (is (= {"server" {"chat" {"channel" true}}}
         (spring-lobby/update-needs-focus "server" "battle" :battle {"server" {"battle" {:battle true}
                                                                               "chat" {"channel" true}}})))
  (is (= {"server" {"chat" {"channel" true}}}
         (spring-lobby/update-needs-focus "server" "battle" :battle {"server" {"battle" {:battle true}
                                                                               "chat" {"channel" true}}})))
  (is (= {"server" {"chat" {"@me" true}}}
         (spring-lobby/update-needs-focus "server" "chat" "main"
           {"server" {"chat" {"@me" true}}})))
  (is (= {"server" {"chat" {"channel1" true}}}
         (spring-lobby/update-needs-focus "server" "chat" "channel2" {"server" {"chat" {"channel1" true
                                                                                        "channel2" true}}}))))
