(ns spring-lobby.client.handler
  (:require
    byte-streams
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [gloss.core :as gloss]
    [gloss.io :as gio]
    [skylobby.resource :as resource]
    [spring-lobby.battle :as battle]
    [spring-lobby.client.message :as message]
    [spring-lobby.client.util :as cu]
    [spring-lobby.fs :as fs]
    [spring-lobby.sound :as sound]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.nio ByteBuffer)))


(set! *warn-on-reflection* true)


; https://springrts.com/dl/LobbyProtocol/ProtocolDescription.html#MYBATTLESTATUS:client
(def default-battle-status
  {:ready true
   :ally 0
   :handicap 0
   :mode false
   :sync 2 ; unsynced
   :id 0
   :side 0})

(defn sync-number [sync-bool]
  (if sync-bool 1 2))

(def battle-status-protocol
  (gloss/compile-frame
    (gloss/bit-map
      :prefix 5
      :side 3
      :sync 2
      :pad 4
      :handicap 7
      :mode 1
      :ally 4
      :id 4
      :ready 1
      :suffix 1)))


(def default-scripttags ; TODO read these from lua in map, mod/game, and engine
  {:game
   {:startpostype 1
    :modoptions {}}})

(defn decode-battle-status [status-str]
  (dissoc
    (gio/decode battle-status-protocol
      (byte-streams/convert
        (.array
          (.putInt
            (ByteBuffer/allocate (quot Integer/SIZE Byte/SIZE))
            (Integer/parseInt status-str)))
        ByteBuffer))
    :prefix :pad :suffix))

(defn encode-battle-status [battle-status]
  (str
    (.getInt
      ^ByteBuffer
      (gio/to-byte-buffer
        (gio/encode battle-status-protocol
          (assoc
            (merge default-battle-status battle-status)
            :prefix 0
            :pad 0
            :suffix 0))))))


(defmulti handle
  (fn [_state _server-url m]
    (-> m
        (string/split #"\s")
        first)))


(defn parse-adduser [m]
  (re-find #"\w+ ([^\s]+) ([^\s]+)( ([\d]+))? ([\d]+)( (.+))?" m))

(defmethod handle "ADDUSER" [state-atom server-url m]
  (if-let [[_all username country _cpu cpu user-id _user-agent user-agent] (parse-adduser m)]
    (let [user {:username username
                :country country
                :cpu cpu
                :user-id user-id
                :user-agent user-agent
                :client-status cu/default-client-status}]
      (swap! state-atom assoc-in [:by-server server-url :users username] user))
    (log/warn "Unable to parse ADDUSER" (pr-str m))))

(defmethod handle "REMOVEUSER" [state-atom server-url m]
  (let [[_all username] (re-find #"\w+ ([^\s]+)" m)]
    (swap! state-atom update-in [:by-server server-url :users] dissoc username)))

(defn parse-client-status [m]
  (re-find #"\w+ ([^\s]+) (\w+)" m))

(defn start-game-if-synced
  [state-atom {:keys [by-spring-root servers spring-isolation-dir] :as state} server-data]
  (let [{:keys [battle battles]} server-data
        spring-root (or (-> servers (get (-> server-data :client-data :server-url)) :spring-isolation-dir)
                        spring-isolation-dir)
        {:keys [engines maps mods]} (-> by-spring-root (get (fs/canonical-path spring-root)))
        battle-detail (-> battles (get (:battle-id battle)))
        {:keys [battle-map battle-modname battle-version]} battle-detail
        has-engine (->> engines (filter (comp #{battle-version} :engine-version)) first)
        has-mod (->> mods (filter (comp #{battle-modname} :mod-name)) first)
        has-map (->> maps (filter (comp #{battle-map} :map-name)) first)]
    (if (and has-engine has-mod has-map)
      (do
        (log/info "Starting game to join host")
        (spring/start-game
          state-atom
          (merge
            state
            server-data
            {:spring-isolation-dir spring-root
             :engines engines
             :maps maps
             :mods mods})))
      (log/info
        (str "Missing engine, mod, or map\n"
             (with-out-str
               (pprint
                 {:engine has-engine
                  :mod has-mod
                  :map has-map})))))))

(defmethod handle "CLIENTSTATUS" [state-atom server-key m]
  (let [[_all username client-status] (parse-client-status m)
        decoded-status (cu/decode-client-status client-status)
        now (u/curr-millis)
        [prev-state _curr-state] (swap-vals! state-atom update-in [:by-server server-key :users username]
                                   (fn [user-data]
                                     (let [prev-status (:client-status user-data)]
                                       (cond-> user-data
                                         true
                                         (assoc :client-status decoded-status)
                                         (and (not (:ingame prev-status)) (:ingame decoded-status))
                                         (assoc :game-start-time now)))))
        {:keys [auto-launch battle battles users] :as server-data} (-> prev-state :by-server (get server-key))
        prev-status (-> users (get username) :client-status)
        my-username (:username server-data)
        my-status (-> users (get my-username) :client-status)
        battle-detail (-> battles (get (:battle-id battle)))
        my-battle-status (-> battle :users (get my-username) :battle-status)
        am-spec (not (:mode my-battle-status))]
    (log/debug "CLIENTSTATUS" username decoded-status)
    (cond
      (not (:ingame decoded-status)) (log/debug "Not in game")
      (= (:ingame prev-status) (:ingame decoded-status)) (log/debug "Not a game status change")
      (= username my-username) (log/debug "Ignoring own game start")
      (:ingame my-status) (log/debug "Already in game")
      (not battle) (log/debug "Not in a battle")
      (not= (:host-username battle-detail) username) (log/debug "Not the host game start")
      (and (not auto-launch) am-spec)
      (log/info "Not auto starting game" (pr-str {:spec am-spec :auto-launch auto-launch}))
      :else
      (start-game-if-synced state-atom prev-state server-data))))

(defmethod handle "CLIENTBATTLESTATUS" [state-atom server-url m]
  (let [[_all username battle-status team-color] (re-find #"\w+ ([^\s]+) (\w+) (\w+)" m)
        decoded (decode-battle-status battle-status)]
    (log/info "Updating status of" username "to" decoded "with color" team-color)
    (swap! state-atom update-in [:by-server server-url]
      (fn [server]
        (-> server
            (update-in [:battle :users username]
              assoc
              :battle-status decoded
              :team-color team-color))))))


(defmethod handle "UPDATEBOT" [state-atom server-url m]
  (let [[_all battle-id username battle-status team-color] (re-find #"\w+ (\w+) ([^\s]+) (\w+) (\w+)" m)
        decoded-status (decode-battle-status battle-status)
        bot-data {:battle-status decoded-status
                  :team-color team-color}]
    (swap! state-atom update-in [:by-server server-url]
      (fn [state]
        (let [state (update-in state [:battles battle-id :bots username] merge bot-data)]
          (if (= battle-id (-> state :battle :battle-id))
            (update-in state [:battle :bots username] merge bot-data)
            state))))))

(defmethod handle "LEFTBATTLE" [state-atom server-url m]
  (let [[_all battle-id username] (re-find #"\w+ (\w+) ([^\s]+)" m)]
    (swap! state-atom update-in [:by-server server-url]
      (fn [state]
        (let [this-battle (when-let [battle (:battle state)]
                            (= battle-id (:battle-id battle)))
              is-me (= username (:username state))]
          (update-in
            (cond
              (and this-battle is-me) (dissoc state :battle)
              this-battle (update-in state [:battle :users] dissoc username)
              :else state)
            [:battles battle-id :users] dissoc username))))))

(defmethod handle "JOIN" [state-atom server-url m]
  (let [[_all channel-name] (re-find #"\w+ ([^\s]+)" m)]
    (swap! state-atom update-in [:by-server server-url]
           (fn [{:keys [server] :as state}]
             (-> state
                 (assoc-in [:my-channels channel-name] {:server server}))))))

(defmethod handle "JOINED" [state-atom server-url m]
  (let [[_all channel-name username] (re-find #"\w+ ([^\s]+) ([^\s]+)" m)]
    (swap! state-atom assoc-in [:by-server server-url :channels channel-name :users username] {})))

(defmethod handle "JOINEDFROM" [state-atom server-url m]
  (let [[_all channel-name bridge username] (re-find #"\w+\s+([^\s]+)\s+([^\s]+)\s+([^\s]+)" m)]
    (swap! state-atom assoc-in [:by-server server-url :channels channel-name :users username] {:bridge bridge})))

(defmethod handle "CLIENTS" [state-atom server-url m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        parts (string/split remaining #"\s+")
        channel-name (first parts)
        clients (rest parts)]
    (swap! state-atom update-in [:by-server server-url :channels channel-name :users]
           (fn [users]
             (apply assoc users
                    (mapcat (fn [client] [client {}]) clients))))))

(defmethod handle "CLIENTSFROM" [state-atom server-url m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        [channel-name bridge & clients] (string/split remaining #"\s+")]
    (swap! state-atom update-in [:by-server server-url :channels channel-name :users]
           (fn [users]
             (apply assoc users
                    (mapcat (fn [client] [client {:bridge bridge}]) clients))))))

(defmethod handle "SAID" [state-atom server-url m]
  (let [[_all channel-name username message] (re-find #"\w+ ([^\s]+) ([^\s]+) (.*)" m)]
    (swap! state-atom update-in [:by-server server-url :channels channel-name :messages]
      (u/update-chat-messages-fn username message))))

(defmethod handle "SAIDEX" [state-atom server-url m]
  (let [[_all channel-name username message] (re-find #"\w+ ([^\s]+) ([^\s]+) (.*)" m)]
    (swap! state-atom update-in [:by-server server-url :channels channel-name :messages]
      (u/update-chat-messages-fn username message true))))

(defmethod handle "SAIDFROM" [state-atom server-url m]
  (let [[_all channel-name username message] (re-find #"\w+ ([^\s]+) ([^\s]+) (.*)" m)]
    (swap! state-atom update-in [:by-server server-url :channels channel-name :messages]
      (u/update-chat-messages-fn username message))))

; legacy battle chat
(defmethod handle "SAIDBATTLE" [state-atom server-key m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)]
    (swap! state-atom update-in [:by-server server-key]
      (fn [server]
        (if-let [battle-id (-> server :battle :battle-id)]
          (let [channel-name (str "__battle__" battle-id)]
            (-> server
                (update-in [:channels channel-name :messages]
                  (u/update-chat-messages-fn username message))))
          server)))))

(defmethod handle "SAIDBATTLEEX" [state-atom server-key m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)]
    (swap! state-atom update-in [:by-server server-key]
      (fn [server]
        (if-let [battle-id (-> server :battle :battle-id)]
          (let [channel-name (str "__battle__" battle-id)]
            (-> server
                (update-in [:channels channel-name :messages]
                  (u/update-chat-messages-fn username message true))))
          server)))))


(defmethod handle "SAYPRIVATE" [state-atom server-url m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)]
    (swap! state-atom update-in [:by-server server-url]
      (fn [state]
        (-> state
            (update-in [:channels (u/user-channel username) :messages]
              (u/update-chat-messages-fn (:username state) message)))))))

(defmethod handle "SAIDPRIVATE" [state-atom server-url m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)
        channel-name (u/user-channel username)]
    (swap! state-atom
      (fn [state]
        (-> state
            (update-in [:by-server server-url]
              (fn [state]
                (-> state
                    (update-in [:channels channel-name :messages]
                      (u/update-chat-messages-fn username message))
                    (assoc-in [:my-channels channel-name] {}))))
            (assoc :selected-tab-main "chat")
            (assoc :selected-tab-channel channel-name))))))

(defmethod handle "SAYPRIVATEEX" [state-atom server-url m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)]
    (swap! state-atom update-in [:by-server server-url]
      (fn [state]
        (-> state
            (update-in [:channels (u/user-channel username) :messages]
              (u/update-chat-messages-fn (:username state) message true)))))))

(defmethod handle "SAIDPRIVATEEX" [state-atom server-url m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)
        channel-name (u/user-channel username)]
    (swap! state-atom
      (fn [state]
        (-> state
            (update-in [:by-server server-url]
              (fn [state]
                (-> state
                    (update-in [:channels channel-name :messages]
                      (u/update-chat-messages-fn username message true))
                    (assoc-in [:my-channels channel-name] {}))))
            (assoc :selected-tab-main "chat")
            (assoc :selected-tab-channel channel-name))))))

(defmethod handle "JOINEDBATTLE" [state-atom server-url m]
  (let [[_all battle-id username _ script-password] (re-find #"\w+ (\w+) ([^\s]+)( (.+))?" m)]
    (swap! state-atom update-in [:by-server server-url]
      (fn [state]
        (let [initial-status {}
              next-state (assoc-in state [:battles battle-id :users username] initial-status)]
          (cond-> next-state
                  (= battle-id (-> next-state :battle :battle-id))
                  (assoc-in [:battle :users username] initial-status)
                  script-password
                  (assoc-in [:battle :script-password] script-password)))))))

(defn- left [state-atom server-key channel-name username]
  (swap! state-atom update-in [:by-server server-key]
         (fn [state]
           (let [next-state (update-in state [:channels channel-name :users] dissoc username)]
             (if (= (:username state) username) ; me
               (update next-state :my-channels dissoc channel-name)
               next-state)))))

(defmethod handle "LEFT" [state-atom server-key m]
  (let [[_all channel-name username] (re-find #"\w+ ([^\s]+) ([^\s]+)" m)]
    (left state-atom server-key channel-name username)))

(defmethod handle "LEFTFROM" [state-atom server-key m]
  (let [[_all channel-name username] (re-find #"\w+ ([^\s]+) ([^\s]+)" m)]
    (left state-atom server-key channel-name username)))

(defmethod handle "REMOVESCRIPTTAGS" [state-atom server-url m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        scripttag-keys-parsed (map spring-script/parse-scripttag-key (string/split remaining #"\s+"))]
    (doseq [kws scripttag-keys-parsed] ; TODO ONE UPDATE
      (when (seq kws)
        (swap! state-atom update-in [:by-server server-url]
          (fn [state]
            (-> state
                (update-in (concat [:scripttags] (drop-last kws)) dissoc (last kws))
                (update-in (concat [:battle :scripttags] (drop-last kws)) dissoc (last kws)))))))))


(defn parse-addbot [m]
  (re-find #"\w+ (\w+) ([^\s]+) ([^\s]+) (\w+) (\w+) (.+)" m))

(defn parse-ai [ai]
  (when ai
    (re-find #"([^|]+)(\|([^\s]+))?" ai)))

(defmethod handle "ADDBOT" [state-atom server-url m]
  (let [[_all battle-id bot-name owner battle-status team-color ai] (parse-addbot m)
        [_all ai-name _ ai-version] (parse-ai ai)
        bot {:bot-name bot-name
             :owner owner
             :battle-status (decode-battle-status battle-status)
             :team-color team-color
             :ai-name ai-name
             :ai-version ai-version}]
    (swap! state-atom update-in [:by-server server-url]
      (fn [state]
        (let [state (assoc-in state [:battles battle-id :bots bot-name] bot)]
          (if (= battle-id (-> state :battle :battle-id))
            (assoc-in state [:battle :bots bot-name] bot)
            state))))))

(defmethod handle "REMOVEBOT" [state-atom server-url m]
  (if-let [[_all battle-id botname] (re-find #"\w+ (\w+) ([^\s]+)" m)]
    (swap! state-atom update-in [:by-server server-url]
      (fn [state]
        (let [next-state (update-in state [:battles battle-id :bots] dissoc botname)]
          (if (:battle next-state)
            (update-in next-state [:battle :bots] dissoc botname)
            next-state))))
    (let [[_all botname] (re-find #"\w+ (\w+)" m)]
      (swap! state-atom update-in [:by-server server-url]
             (fn [state]
               (if (:battle state)
                 (update-in state [:battle :bots] dissoc botname)
                 state))))))


(defn parse-joinbattle [m]
  (re-find #"\w+ ([^\s]+) ([^\s]+)( ([^\s]+))?" m))

(defmethod handle "JOINBATTLE" [state-atom server-url m]
  (let [[_all battle-id hash-code _ channel-name] (parse-joinbattle m)]
    (swap! state-atom update-in [:by-server server-url]
           assoc
           :battle {:battle-id battle-id
                    :hash-code hash-code
                    :channel-name channel-name
                    :scripttags default-scripttags}
           :battle-map-details nil
           :battle-mod-details nil)))

(defmethod handle "REQUESTBATTLESTATUS" [state-atom server-url _m]
  (let [{:keys [map-details mod-details servers spring-isolation-dir] :as state} @state-atom
        {:keys [battle client-data preferred-color] :as server-data} (-> state :by-server (get server-url))
        spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                        spring-isolation-dir)
        spring-root-path (fs/canonical-path spring-root)
        spring (-> state :by-spring-root (get spring-root-path))
        my-username (:username client-data)
        {:keys [battle-status]} (-> battle :users (get my-username))
        new-battle-status (assoc (or battle-status default-battle-status)
                            :id (battle/available-team-id battle)
                            :ally (battle/available-ally battle)
                            :sync (sync-number
                                    (resource/sync-status server-data spring mod-details map-details)))
        color (or preferred-color (u/random-color))
        client (:client client-data)
        msg (str "MYBATTLESTATUS " (encode-battle-status new-battle-status) " " color)]
    (message/send-message client msg)))

(defmethod handle "BATTLECLOSED" [state-atom server-url m]
  (let [[_all battle-id] (re-find #"\w+ (\w+)" m)]
    (swap! state-atom update-in [:by-server server-url]
      (fn [state]
        (let [curr-battle-id (-> state :battle :battle-id)
              next-state (update state :battles dissoc battle-id)]
          (if (= battle-id curr-battle-id)
            (dissoc next-state :battle)
            next-state))))))

(defmethod handle "AGREEMENT" [state-atom server-url m]
  (let [[_all agreement-text] (re-find #"\w+ (.*)" m)]
    (swap! state-atom update-in [:by-server server-url :agreement-part] str "\n" agreement-text)))

(defmethod handle "AGREEMENTEND" [state-atom server-url _m]
  (swap! state-atom update-in [:by-server server-url]
         (fn [state]
           (-> state
               (assoc :agreement (:agreement-part state))
               (dissoc :agreement-part)))))

(defmethod handle "OPENBATTLE" [_state-atom _server-url m]
  (log/info "Battle opened:" m))

(defmethod handle "OPENBATTLEFAILED" [state-atom server-url _m]
  (swap! state-atom update-in [:by-server server-url] dissoc :battle))

(defmethod handle "FAILED" [state-atom server-url m]
  (try
    (let [[_all reason] (re-find #"\w+ (.*)" m)
          [_all msg _command] (re-find #"msg=(.*)\tcmd=(.*)" reason)]
      (if (= "not in battle" msg)
        (do
          (log/info "Ignoring failed message for 'not in battle'")
          (swap! state-atom update-in [:by-server server-url]
                 (fn [server-data]
                   (-> server-data
                       (dissoc :battle)))))
        (swap! state-atom update-in [:by-server server-url]
               (fn [server-data]
                 (-> server-data
                     (assoc :last-failed-message m))))))
    (catch Exception e
      (log/error e "Error parsing failed message")
      (swap! state-atom assoc-in [:by-server server-url :last-failed-message] m))))

(defmethod handle "JOINBATTLEFAILED" [state-atom server-url m]
  (let [state (swap! state-atom update-in [:by-server server-url]
                     (fn [state]
                       (-> state
                           (dissoc :battle)
                           (assoc :last-failed-message m))))
        client (-> state :by-server (get server-url) :client-data :client)]
    (when (= m "JOINBATTLEFAILED You are already in a battle")
      (message/send-message client "LEAVEBATTLE"))))


(defmethod handle "CHANNEL" [state-atom server-url m]
  (let [[_all channel-name user-count topic] (re-find #"\w+ ([^\s]+) (\w+) (.+)?" m)]
    (swap! state-atom update-in [:by-server server-url :channels channel-name]
           merge {:channel-name channel-name
                  :user-count user-count
                  :topic topic})))

(defmethod handle "CHANNELTOPIC" [state-atom server-url m]
  (let [[_all channel-name topic] (re-find #"\w+ ([^\s]+) (\w+)" m)]
    (swap! state-atom update-in [:by-server server-url :channels channel-name] merge {:topic topic})))

(defmethod handle "ENDOFCHANNELS" [_state-atom _server-url _m]
  (log/debug "Ignore ENDOFCHANNELS message"))

(defn normalize-startrect [rect-str]
  (let [parsed (Long/parseLong rect-str)]
    (double (/ parsed 200))))

(defmethod handle "ADDSTARTRECT" [state-atom server-url m]
  (let [[_all allyteam left top right bottom] (re-find #"\w+ (\w+) (\w+) (\w+) (\w+) (\w+)" m)
        allyteam-kw (keyword (str "allyteam" allyteam))]
    (swap! state-atom update-in [:by-server server-url :battle :scripttags :game allyteam-kw]
           (fn [allyteam]
             (assoc allyteam
                    :startrectleft (normalize-startrect left)
                    :startrecttop (normalize-startrect top)
                    :startrectright (normalize-startrect right)
                    :startrectbottom (normalize-startrect bottom))))))

(defmethod handle "REMOVESTARTRECT" [state-atom server-url m]
  (let [[_all allyteam] (re-find #"\w+ (\w+)" m)
        allyteam-kw (keyword (str "allyteam" allyteam))]
    (swap! state-atom update-in [:by-server server-url :battle :scripttags :game allyteam-kw]
           (fn [allyteam]
             (dissoc allyteam :startrectleft :startrecttop :startrectright :startrectbottom)))))

(defmethod handle "RING" [_state-atom _server-url m]
  (let [[_all username] (re-find #"\w+ ([^\s]+)" m)]
    (log/info "Playing ring sound from" username)
    (sound/play-ring)))
