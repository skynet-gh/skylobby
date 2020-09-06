(ns spring-lobby.client-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby.client :as client]))


(deftest decode-client-status
  (is (= {:bot false,
          :access false,
          :rank 0,
          :away false,
          :ingame false}
         (client/decode-client-status "0")))
  (is (= {:bot false,
          :access false,
          :rank 0,
          :away false,
          :ingame true}
         (client/decode-client-status "1")))
  (is (= {:bot false,
          :access false,
          :rank 2,
          :away false,
          :ingame false}
         (client/decode-client-status "8")))
  (is (= {:bot true,
          :access false,
          :rank 0,
          :away false,
          :ingame false}
         (client/decode-client-status "64"))))


(deftest decode-battle-status
  (is (= {:ready false,
          :ally 0,
          :handicap 0,
          :mode true,
          :sync 2,
          :id 0,
          :side 0}
         (client/decode-battle-status "8389632"))))


(deftest parse-adduser
  (is (= ["skynet"
          "??"
          "8"
          "SpringLobby 0.270 (win x32)"]
         (rest (client/parse-adduser "ADDUSER skynet ?? 8 SpringLobby 0.270 (win x32)"))))
  (is (= ["ChanServ"
          "US"
          "None"
          "ChanServ"]
         (rest (client/parse-adduser "ADDUSER ChanServ US None ChanServ")))))

(deftest parse-addbot
  (is (= ["12" "kekbot1" "skynet9001" "0" "0" "KAIK|0.13"]
         (rest (client/parse-addbot "ADDBOT 12 kekbot1 skynet9001 0 0 KAIK|0.13")))))

(deftest parse-battleopened
  (is (= ["1"
          "0"
          "0"
          "skynet"
          "192.168.1.6"
          "8452"
          "8"
          "1"
          "0"
          "-1706632985"
          "Spring"
          "104.0.1-1510-g89bb8e3 maintenance"
          "Mini_SuperSpeedMetal"
          "deth"
          "Balanced Annihilation V10.24"
          "__battle__8"]
         (rest
           (client/parse-battleopened "BATTLEOPENED 1 0 0 skynet 192.168.1.6 8452 8 1 0 -1706632985 Spring	104.0.1-1510-g89bb8e3 maintenance	Mini_SuperSpeedMetal	deth	Balanced Annihilation V10.24	__battle__8")))))

(deftest parse-updatebattleinfo
  (is (= ["1"
          "0"
          "0"
          "1465550451"
          "Archers_Valley_v6"]
         (rest
           (client/parse-updatebattleinfo "UPDATEBATTLEINFO 1 0 0 1465550451 Archers_Valley_v6")))))

(deftest parse-joinbattle
  (is (= ["32" "-1706632985" "__battle__1"]
         (rest
           (client/parse-joinbattle "JOINBATTLE 32 -1706632985 __battle__1")))))
