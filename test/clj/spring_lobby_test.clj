(ns spring-lobby-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [cheshire.core :as json]
    [spring-lobby]
    [spring-lobby.fs :as fs]))


(deftest available-name
  (is (= "bot"
         (spring-lobby/available-name [] "bot")))
  (is (= "bot0"
         (spring-lobby/available-name ["bot"] "bot")))
  (is (= "bot2"
         (spring-lobby/available-name ["bot" "bot0" "bot1"] "bot"))))

(deftest could-be-this-engine?
  (is (true?
        (with-redefs [fs/platform (constantly "win64")]
          (spring-lobby/could-be-this-engine?
            "104.0.1.1828-g1f481b7 BAR"
            {:download-url
             "https://github.com/beyond-all-reason/spring/releases/download/spring_bar_%7BBAR%7D104.0.1-1828-g1f481b7/spring_bar_.BAR.104.0.1-1828-g1f481b7_windows-64-minimal-portable.7z",
             :resource-filename
             "spring_bar_.BAR.104.0.1.1828-g1f481b7_windows-64-minimal-portable.7z"
             :resource-type :spring-lobby/engine,
             :resource-date "2021-03-20T17:30:16Z",
             :download-source-name "BAR GitHub spring",
             :resource-updated 1616282430238})))))


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
