(ns spring-lobby.client.handler-test
  (:require
    [clojure.test :refer [deftest is]]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.util :as u]))


(deftest decode-battle-status
  (is (= {:ready false,
          :ally 0,
          :handicap 0,
          :mode true,
          :sync 2,
          :id 0,
          :side 0}
         (handler/decode-battle-status "8389632"))))


(deftest encode-battle-status
  (let [encoded (handler/encode-battle-status
                  {:ready false,
                   :ally 0,
                   :handicap 0,
                   :mode true,
                   :sync 2,
                   :id 0,
                   :side 0})]
    (is (= "8389632"
           encoded))))


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
          nil
          nil
          "8"
          " SpringLobby 0.270 (win x32)"
          "SpringLobby 0.270 (win x32)"]
         (rest (handler/parse-adduser "ADDUSER skynet ?? 8 SpringLobby 0.270 (win x32)"))))
  (is (= ["ChanServ"
          "US"
          nil
          nil
          "0"
          " ChanServ"
          "ChanServ"]
         (rest (handler/parse-adduser "ADDUSER ChanServ US 0 ChanServ"))))
  (is (= ["[teh]host20"
          "HU"
          nil
          nil
          "2218"
          " SPADS v0.12.18"
          "SPADS v0.12.18"]
         (rest (handler/parse-adduser "ADDUSER [teh]host20 HU 2218 SPADS v0.12.18"))))
  (is (= ["skynet"
          "??"
          nil
          nil
          "8"
          nil
          nil]
         (rest (handler/parse-adduser "ADDUSER skynet ?? 8"))))
  (is (= ["[Z]ecrus"
          "US"
          " 0"
          "0"
          "67"
          " SpringLobby 0.270-49-gab254fe7d (windows64)"
          "SpringLobby 0.270-49-gab254fe7d (windows64)"]
         (rest (handler/parse-adduser "ADDUSER [Z]ecrus US 0 67 SpringLobby 0.270-49-gab254fe7d (windows64)")))))

(deftest handle-ADDUSER
  (let [state-atom (atom {})
        server-key :server1]
    (handler/handle state-atom server-key "ADDUSER skynet ?? 8 SpringLobby 0.270 (win x32)")
    (is (= {:by-server
            {:server1
             {:users
              {"skynet"
               {:client-status {:access false
                                :away false
                                :bot false
                                :ingame false
                                :rank 0}
                :country "??"
                :cpu nil
                :user-agent "SpringLobby 0.270 (win x32)"
                :user-id "8"
                :username "skynet"}}}}}
           @state-atom)))
  (let [state-atom (atom {})
        server-key :server1]
    (handler/handle state-atom server-key "ADDUSER ChanServ US 0 ChanServ")
    (is (= {:by-server
            {:server1
             {:users
              {"ChanServ"
               {:client-status {:access false
                                :away false
                                :bot false
                                :ingame false
                                :rank 0}
                :country "US"
                :cpu nil
                :user-agent "ChanServ"
                :user-id "0"
                :username "ChanServ"}}}}}
           @state-atom)))
  (let [state-atom (atom {})
        server-key :server1]
    (handler/handle state-atom server-key "ADDUSER [teh]host20 HU 2218 SPADS v0.12.18")
    (is (= {:by-server
            {:server1
             {:users
              {"[teh]host20"
               {:client-status {:access false
                                :away false
                                :bot false
                                :ingame false
                                :rank 0}
                :country "HU"
                :cpu nil
                :user-agent "SPADS v0.12.18"
                :user-id "2218"
                :username "[teh]host20"}}}}}
           @state-atom)))
  (let [state-atom (atom {})
        server-key :server1]
    (handler/handle state-atom server-key "ADDUSER skynet ?? 8")
    (is (= {:by-server
            {:server1
             {:users
              {"skynet"
               {:client-status {:access false
                                :away false
                                :bot false
                                :ingame false
                                :rank 0}
                :country "??"
                :cpu nil
                :user-agent nil
                :user-id "8"
                :username "skynet"}}}}}
           @state-atom)))
  (let [state-atom (atom {})
        server-key :server1]
    (handler/handle state-atom server-key "ADDUSER [Z]ecrus US 0 67 SpringLobby 0.270-49-gab254fe7d (windows64)")
    (is (= {:by-server
            {:server1
             {:users
              {"[Z]ecrus"
               {:client-status {:access false
                                :away false
                                :bot false
                                :ingame false
                                :rank 0}
                :country "US"
                :cpu "0"
                :user-agent "SpringLobby 0.270-49-gab254fe7d (windows64)"
                :user-id "67"
                :username "[Z]ecrus"}}}}}
           @state-atom))))


(deftest handle-SAIDBATTLEEX
  (let [state-atom (atom {:by-server {:server1 {:battle {:battle-id "1"}}}})
        server-key :server1
        now 12345]
    (with-redefs [u/curr-millis (constantly now)]
      (handler/handle state-atom server-key "SAIDBATTLEEX [teh]cluster1[01] * Hi [Z]kynet! Current battle type is team."))
    (is (= {:by-server
            {:server1
             {:battle {:battle-id "1"}
              :channels
              {"__battle__1"
               {:messages
                [{:ex true
                  :text "* Hi [Z]kynet! Current battle type is team."
                  :timestamp now
                  :username "[teh]cluster1[01]"}]}}}}}
           @state-atom))))


(deftest handle-SAIDFROM
  (let [state-atom (atom {})
        server-key :server1
        now 12345]
    (with-redefs [u/curr-millis (constantly now)]
      (handler/handle state-atom server-key "SAIDFROM evolution user:springrts.com :)"))
    (is (= {:by-server
            {:server1
             {:channels
              {"evolution"
               {:messages
                [{:ex false
                  :text ":)"
                  :timestamp now
                  :username "user:springrts.com"}]}}}}}
           @state-atom))))

(deftest handle-CLIENTSFROM
  (let [state-atom (atom {})
        server-key :server1]
    (handler/handle state-atom server-key
      "CLIENTSFROM evolution appservice user1:discord user:springrts.com user3:discord user4:discord")
    (is (= {:by-server
            {:server1
             {:channels
              {"evolution"
               {:users
                {"user1:discord" {:bridge "appservice"}
                 "user3:discord" {:bridge "appservice"}
                 "user4:discord" {:bridge "appservice"}
                 "user:springrts.com" {:bridge "appservice"}}}}}}}
           @state-atom))))

(deftest handle-JOINEDFROM
  (let [state-atom (atom
                     {:by-server
                      {:server1
                       {:channels
                        {"evolution"
                         {:users
                          {"user1:discord" {:bridge "appservice"}}}}}}})
        server-key :server1]
    (handler/handle state-atom server-key "JOINEDFROM evolution appservice user4:discord")
    (is (= {:by-server
            {:server1
             {:channels
              {"evolution"
               {:users
                {"user1:discord" {:bridge "appservice"}
                 "user4:discord" {:bridge "appservice"}}}}}}}
           @state-atom))))

(deftest handle-LEFTFROM
  (let [state-atom (atom
                     {:by-server
                      {:server1
                       {:channels
                        {"evolution"
                         {:users
                          {"user1:discord" {:bridge "appservice"}
                           "user4:discord" {:bridge "appservice"}}}}}}})
        server-key :server1]
    (handler/handle state-atom server-key "LEFTFROM evolution user1:discord")
    (is (= {:by-server
            {:server1
             {:channels
              {"evolution"
               {:users
                {"user4:discord" {:bridge "appservice"}}}}}}}
           @state-atom))))


(deftest handle-CLIENTSTATUS
  (let [state-atom (atom {})
        server-key :server1]
    (handler/handle state-atom server-key "CLIENTSTATUS skynet 0")
    (is (= {:by-server
            {:server1
             {:users
              {"skynet"
               {:client-status
                {:access false
                 :away false
                 :bot false
                 :ingame false
                 :rank 0}}}}}}
           @state-atom))))
