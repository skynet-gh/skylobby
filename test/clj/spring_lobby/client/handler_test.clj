(ns spring-lobby.client.handler-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.client.message :as message]
    [spring-lobby.client.util :as cu]
    [spring-lobby.util :as u]))


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
          "\t__battle__8"
          "__battle__8"]
         (rest
           (handler/parse-battleopened "BATTLEOPENED 1 0 0 skynet 192.168.1.6 8452 8 1 0 -1706632985 Spring	104.0.1-1510-g89bb8e3 maintenance	Mini_SuperSpeedMetal	deth	Balanced Annihilation V10.24	__battle__8")))))

(deftest parse-updatebattleinfo
  (is (= ["1"
          "0"
          "0"
          "1465550451"
          "Archers_Valley_v6"]
         (rest
           (handler/parse-updatebattleinfo "UPDATEBATTLEINFO 1 0 0 1465550451 Archers_Valley_v6")))))

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
  (is (= ["Zecrus"
          "US"
          " 0"
          "0"
          "67"
          " SpringLobby 0.270-49-gab254fe7d (windows64)"
          "SpringLobby 0.270-49-gab254fe7d (windows64)"]
         (rest (handler/parse-adduser "ADDUSER Zecrus US 0 67 SpringLobby 0.270-49-gab254fe7d (windows64)"))))
  (is (= ["ChanServ" "US" nil nil "None" " ChanServ" "ChanServ"]
         (rest (handler/parse-adduser "ADDUSER ChanServ US None ChanServ")))))

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
    (handler/handle state-atom server-key "ADDUSER Zecrus US 0 67 SpringLobby 0.270-49-gab254fe7d (windows64)")
    (is (= {:by-server
            {:server1
             {:users
              {"Zecrus"
               {:client-status {:access false
                                :away false
                                :bot false
                                :ingame false
                                :rank 0}
                :country "US"
                :cpu "0"
                :user-agent "SpringLobby 0.270-49-gab254fe7d (windows64)"
                :user-id "67"
                :username "Zecrus"}}}}}
           @state-atom))))


(deftest handle-SAIDBATTLEEX
  (testing "message"
    (let [state-atom (atom {:by-server {:server1 {:battle {:battle-id "1"}}}})
          server-key :server1
          now 12345]
      (with-redefs [u/curr-millis (constantly now)]
        (handler/handle state-atom server-key "SAIDBATTLEEX [teh]cluster1[01] * Hi skynet! Current battle type is team."))
      (is (= {:by-server
              {:server1
               {:battle {:battle-id "1"}
                :channels
                {"__battle__1"
                 {:messages
                  [{:message-type :ex
                    :text "* Hi skynet! Current battle type is team."
                    :timestamp now
                    :username "[teh]cluster1[01]"
                    :spads {:spads-message-type :greeting,
                            :spads-parsed ["Hi skynet! Current battle type is team."
                                           "skynet"
                                           "team"],
                            :text "* Hi skynet! Current battle type is team."}
                    :vote nil}]}}}}}
             @state-atom))))
  (testing "auto unspec"
    (let [
          server-key :server1
          state-atom (atom
                       {:by-server
                        {server-key
                         {:auto-unspec true
                          :battle
                          {:battle-id "0"
                           :users
                           {"skynet"
                            {:battle-status
                             {:ally 0
                              :handicap 0
                              :id 0
                              :mode false
                              :ready false
                              :side 0
                              :sync 0}}}}
                          :username "skynet"
                          :client-data {:compflags #{"u"}}}}})
          messages-atom (atom [])
          now 1631909524841]
      (with-redefs [message/send-message (fn [_state-atom _client-data message] (swap! messages-atom conj message))
                    handler/auto-unspec-ready? (constantly true)
                    u/curr-millis (constantly now)]
        (handler/handle state-atom server-key "SAIDBATTLEEX host1 * Global setting changed by skynet (teamSize=16)"))
      (is (= {:by-server
              {server-key
               {:auto-unspec true
                :battle
                {:battle-id "0"
                 :users
                 {"skynet"
                  {:battle-status
                   {:ally 0
                    :handicap 0
                    :id 0
                    :mode false
                    :ready false
                    :side 0
                    :sync 0}}}}
                :channels {"__battle__0" {:messages [{:message-type :ex
                                                      :text "* Global setting changed by skynet (teamSize=16)"
                                                      :timestamp now
                                                      :username "host1"
                                                      :spads nil
                                                      :vote nil}]}}
                :username "skynet"
                :client-data {:compflags #{"u"}}}}}
             @state-atom))
      (is (= ["MYBATTLESTATUS 1024 0"]
             @messages-atom)))))


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
                [{:message-type nil
                  :text ":)"
                  :timestamp now
                  :username "user:springrts.com"
                  :spads nil
                  :vote nil}]}}}}}
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
  (testing "zero"
    (let [state-atom (atom {})
          game-started (atom false)
          server-key :server1]
      (with-redefs [handler/start-game-if-synced (fn [& _] (reset! game-started true))]
        (handler/handle state-atom server-key "CLIENTSTATUS skynet 0"))
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
             @state-atom))
      (is (false? @game-started))))
  (testing "start game"
    (let [host "skynet"
          me "me"
          state-atom (atom
                       {:by-server
                        {:server1
                         {:battle
                          {:battle-id :battle1
                           :users
                           {me
                            {:battle-status {:mode true}}}}
                          :battles
                          {:battle1
                           {:host-username host}}
                          :username me}}})
          game-started (atom false)
          server-key :server1
          client-status-str (cu/encode-client-status
                              (assoc
                                cu/default-client-status
                                :ingame true))
          now 12345]
      (with-redefs [handler/start-game-if-synced (fn [& _] (reset! game-started true))
                    u/curr-millis (constantly now)]
        (handler/handle state-atom server-key (str "CLIENTSTATUS " host " " client-status-str)))
      (is (= {:by-server
              {:server1
               {:battle
                {:battle-id :battle1
                 :users
                 {me
                  {:battle-status {:mode true}}}}
                :battles
                {:battle1
                 {:host-username host}}
                :username me
                :users
                {"skynet"
                 {:client-status
                  {:access false
                   :away false
                   :bot false
                   :ingame true
                   :rank 0}
                  :game-start-time now}}}}}
             @state-atom))
      (is (true? @game-started)))))

(deftest handle-CLIENTBATTLESTATUS
  (testing "add user"
    (let [
          server-key :server1
          state-atom (atom
                       {:by-server
                        {server-key
                         {:battle {}}}})
          messages-atom (atom [])]
      (with-redefs [message/send-message (fn [_state-atom _client-data message] (swap! messages-atom conj message))]
        (handler/handle state-atom server-key "CLIENTBATTLESTATUS skynet 0 0"))
      (is (= {:by-server
              {server-key
               {:battle
                {:users
                 {"skynet"
                  {:battle-status
                   {:ally 0
                    :handicap 0
                    :id 0
                    :mode false
                    :ready false
                    :side 0
                    :sync 0}
                   :team-color "0"}}}}}}
             @state-atom))
      (is (= []
             @messages-atom))))
  (testing "auto unspec"
    (let [
          server-key :server1
          state-atom (atom
                       {:by-server
                        {server-key
                         {:auto-unspec true
                          :battle
                          {:users
                           {"skynet"
                            {:battle-status
                             {:ally 0
                              :handicap 0
                              :id 0
                              :mode false
                              :ready false
                              :side 0
                              :sync 0}}
                            "other"
                            {:battle-status
                             {:ally 0
                              :handicap 0
                              :id 1
                              :mode true
                              :ready false
                              :side 0
                              :sync 0}}}}
                          :username "skynet"}}})
          messages-atom (atom [])]
      (with-redefs [message/send-message (fn [_state-atom _client-data message] (swap! messages-atom conj message))
                    handler/auto-unspec-ready? (constantly true)]
        (handler/handle state-atom server-key "CLIENTBATTLESTATUS other 0 0"))
      (is (= {:by-server
              {server-key
               {:auto-unspec true
                :battle
                {:users
                 {"skynet"
                  {:battle-status
                   {:ally 0
                    :handicap 0
                    :id 0
                    :mode false
                    :ready false
                    :side 0
                    :sync 0}}
                  "other"
                  {:battle-status
                   {:ally 0
                    :handicap 0
                    :id 0
                    :mode false
                    :ready false
                    :side 0
                    :sync 0}
                   :team-color "0"}}}
                :username "skynet"}}}
             @state-atom))
      (is (= ["MYBATTLESTATUS 1024 0"]
             @messages-atom)))))


(deftest handle-LEFTBATTLE
  (testing "auto unspec"
    (let [
          server-key :server1
          state-atom (atom
                       {:by-server
                        {server-key
                         {:auto-unspec true
                          :battle
                          {:battle-id "0"
                           :users
                           {"skynet"
                            {:battle-status
                             {:ally 0
                              :handicap 0
                              :id 0
                              :mode false
                              :ready false
                              :side 0
                              :sync 0}}
                            "other"
                            {:battle-status
                             {:ally 0
                              :handicap 0
                              :id 1
                              :mode true
                              :ready false
                              :side 0
                              :sync 0}}}}
                          :username "skynet"
                          :client-data {:compflags #{"u"}}}}})
          messages-atom (atom [])]
      (with-redefs [message/send-message (fn [_state-atom _client-data message] (swap! messages-atom conj message))
                    handler/auto-unspec-ready? (constantly true)]
        (handler/handle state-atom server-key "LEFTBATTLE 0 other"))
      (is (= {:by-server
              {server-key
               {:auto-unspec true
                :battle
                {:battle-id "0"
                 :users
                 {"skynet"
                  {:battle-status
                   {:ally 0
                    :handicap 0
                    :id 0
                    :mode false
                    :ready false
                    :side 0
                    :sync 0}}}}
                :battles {"0" {:users nil}}
                :username "skynet"
                :client-data {:compflags #{"u"}}}}}
             @state-atom))
      (is (= ["MYBATTLESTATUS 1024 0"]
             @messages-atom)))))


(deftest teamsize-changed-message?
  (is (false? (handler/teamsize-changed-message? "")))
  (is (true? (handler/teamsize-changed-message? "* Global setting changed by skynet (teamSize=16)"))))

(deftest handle-SAIDEX
  (testing "auto unspec"
    (let [
          server-key :server1
          state-atom (atom
                       {:by-server
                        {server-key
                         {:auto-unspec true
                          :battle
                          {:channel-name "__battle__0"
                           :users
                           {"skynet"
                            {:battle-status
                             {:ally 0
                              :handicap 0
                              :id 0
                              :mode false
                              :ready false
                              :side 0
                              :sync 0}}}}
                          :username "skynet"
                          :client-data {:compflags #{"u"}}}}})
          messages-atom (atom [])
          now 1631909524841]
      (with-redefs [message/send-message (fn [_state-atom _client-data message] (swap! messages-atom conj message))
                    handler/auto-unspec-ready? (constantly true)
                    u/curr-millis (constantly now)]
        (handler/handle state-atom server-key "SAIDEX __battle__0 host1 * Global setting changed by skynet (teamSize=16)"))
      (is (= {:by-server
              {server-key
               {:auto-unspec true
                :battle
                {:channel-name "__battle__0"
                 :users
                 {"skynet"
                  {:battle-status
                   {:ally 0
                    :handicap 0
                    :id 0
                    :mode false
                    :ready false
                    :side 0
                    :sync 0}}}}
                :channels {"__battle__0" {:messages [{:message-type :ex
                                                      :text "* Global setting changed by skynet (teamSize=16)"
                                                      :timestamp now
                                                      :username "host1"
                                                      :spads nil
                                                      :vote nil}]}}
                :my-channels {"__battle__0" {}}
                :username "skynet"
                :client-data {:compflags #{"u"}}}}
              :selected-tab-channel "__battle__0"}
             @state-atom))
      (is (= ["MYBATTLESTATUS 1024 0"]
             @messages-atom)))))
