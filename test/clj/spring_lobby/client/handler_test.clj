(ns spring-lobby.client.handler-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby.client.handler :as handler]))


(deftest parse-addbot
  (is (= ["12" "kekbot1" "skynet9001" "0" "0" "KAIK|0.13"]
         (rest (handler/parse-addbot "ADDBOT 12 kekbot1 skynet9001 0 0 KAIK|0.13"))))
  (is (= ["4" "Bot2" "skynet9001" "4195534" "16744322" "MFAI : normal (air)|<not-versioned>"]
         (rest (handler/parse-addbot "ADDBOT 4 Bot2 skynet9001 4195534 16744322 MFAI : normal (air)|<not-versioned>"))))
  (is (= ["13674" "BARbstable(1)" "owner" "4195334" "16747775" "BARb"]
         (rest (handler/parse-addbot "ADDBOT 13674 BARbstable(1) owner 4195334 16747775 BARb")))))

(deftest parse-ai
  (is (= ["MFAI : normal (air)" "|<not-versioned>" "<not-versioned>"]
         (rest (handler/parse-ai "MFAI : normal (air)|<not-versioned>"))))
  (is (= ["BARb" nil nil]
         (rest (handler/parse-ai "BARb")))))

(deftest parse-joinbattle
  (is (= ["32" "-1706632985" " __battle__1" "__battle__1"]
         (rest
           (handler/parse-joinbattle "JOINBATTLE 32 -1706632985 __battle__1")))))

(deftest parse-adduser
  (is (= ["skynet"
          "??"
          "8"
          " SpringLobby 0.270 (win x32)"
          "SpringLobby 0.270 (win x32)"]
         (rest (handler/parse-adduser "ADDUSER skynet ?? 8 SpringLobby 0.270 (win x32)"))))
  (is (= ["ChanServ"
          "US"
          "None"
          " ChanServ"
          "ChanServ"]
         (rest (handler/parse-adduser "ADDUSER ChanServ US None ChanServ"))))
  (is (= ["[teh]host20"
          "HU"
          "2218"
          " SPADS v0.12.18"
          "SPADS v0.12.18"]
         (rest (handler/parse-adduser "ADDUSER [teh]host20 HU 2218 SPADS v0.12.18")))))
(is (= ["skynet"
        "??"
        "8"]
       (rest (handler/parse-adduser "ADDUSER skynet ?? 8"))))
