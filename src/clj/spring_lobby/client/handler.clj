(ns spring-lobby.client.handler
  (:require
    byte-streams
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [gloss.core :as gloss]
    [gloss.io :as gio]
    [spring-lobby.battle :as battle]
    [spring-lobby.client.message :as message]
    [spring-lobby.sound :as sound]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.nio ByteBuffer)))


(set! *warn-on-reflection* true)


(def default-battle-status
  {:ready true
   :ally 0
   :handicap 0
   :mode 0
   :sync 1
   :id 0
   :side 0})

(def default-client-status "0")

(def client-status-protocol
  (gloss/compile-frame
    (gloss/bit-map
      :prefix 1
      :bot 1
      :access 1
      :rank 3
      :away 1
      :ingame 1)))

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


(defn decode-client-status [status-str]
  (dissoc
    (gio/decode client-status-protocol
      (byte-streams/convert
        (.array
          (.put
            (ByteBuffer/allocate 1)
            (Byte/parseByte status-str)))
        ByteBuffer))
    :prefix))

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
  (fn [_client _state m]
    (-> m
        (string/split #"\s")
        first)))


(defn parse-adduser [m]
  (re-find #"\w+ ([^\s]+) ([^\s]+) ([^\s]+)( (.+))?" m))

(defmethod handle "ADDUSER" [_c state m]
  (if-let [[_all username country user-id _ user-agent] (parse-adduser m)]
    (let [user {:username username
                :country country
                :user-id user-id
                :user-agent user-agent
                :client-status (decode-client-status default-client-status)}]
      (swap! state assoc-in [:users username] user))
    (log/warn "Unable to parse ADDUSER" (pr-str m))))

(defmethod handle "REMOVEUSER" [_c state m]
  (let [[_all username] (re-find #"\w+ ([^\s]+)" m)]
    (swap! state update :users dissoc username)))

(defn parse-client-status [m]
  (re-find #"\w+ ([^\s]+) (\w+)" m))

(defn start-game-if-synced
  [{:keys [battle battles engines maps mods] :as state}]
  (let [battle-detail (-> battles (get (:battle-id battle)))
        {:keys [battle-map battle-modname battle-version]} battle-detail
        has-engine (->> engines (filter (comp #{battle-version} :engine-version)) first)
        has-mod (->> mods (filter (comp #{battle-modname} :mod-name)) first)
        has-map (->> maps (filter (comp #{battle-map} :map-name)) first)]
    (if (and has-engine has-mod has-map)
      (do
        (log/info "Starting game to join host")
        (spring/start-game state))
      (log/info
        (str "Missing engine, mod, or map\n"
             (with-out-str
               (pprint
                 {:engine has-engine
                  :mod has-mod
                  :map has-map})))))))

(defmethod handle "CLIENTSTATUS" [_c state-atom m]
  (let [[_all username client-status] (parse-client-status m)
        decoded-status (decode-client-status client-status)
        [prev-state _curr-state] (swap-vals! state-atom assoc-in [:users username :client-status] decoded-status)
        {:keys [battle battles]} prev-state
        prev-status (-> prev-state :users (get username) :client-status)
        my-username (:username prev-state)
        my-status (-> prev-state :users (get my-username) :client-status)
        battle-detail (-> battles (get (:battle-id battle)))]
    (log/debug username decoded-status)
    (cond
      (not (:ingame decoded-status)) (log/debug "Not in game")
      (= (:ingame prev-status) (:ingame decoded-status)) (log/debug "Not a game status change")
      (= username my-username) (log/debug "Ignoring own game start")
      (:ingame my-status) (log/debug "Already in game")
      (not battle) (log/debug "Not in a battle")
      (not (-> battle :users (get my-username) :battle-status :ready)) (log/debug "Not ready")
      (not= (:host-username battle-detail) username) (log/debug "Not the host game start")
      :else
      (start-game-if-synced prev-state))))

(defmethod handle "CLIENTBATTLESTATUS" [_c state m]
  (let [[_all username battle-status team-color] (re-find #"\w+ ([^\s]+) (\w+) (\w+)" m)
        decoded (decode-battle-status battle-status)]
    (log/info "Updating status of" username "to" decoded "with color" team-color)
    (swap! state update-in [:battle :users username]
           assoc
           :battle-status decoded
           :team-color team-color)))

(defmethod handle "UPDATEBOT" [_c state-atom m]
  (let [[_all battle-id username battle-status team-color] (re-find #"\w+ (\w+) ([^\s]+) (\w+) (\w+)" m)
        decoded-status (decode-battle-status battle-status)
        bot-data {:battle-status decoded-status
                  :team-color team-color}]
    (swap! state-atom
      (fn [state]
        (let [state (update-in state [:battles battle-id :bots username] merge bot-data)]
          (if (= battle-id (-> state :battle :battle-id))
            (update-in state [:battle :bots username] merge bot-data)
            state))))))

(defmethod handle "LEFTBATTLE" [_c state-atom m]
  (let [[_all battle-id username] (re-find #"\w+ (\w+) ([^\s]+)" m)]
    (swap! state-atom
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

(defmethod handle "JOIN" [_c state-atom m]
  (let [[_all channel-name] (re-find #"\w+ ([^\s]+)" m)]
    (swap! state-atom
           (fn [{:keys [server] :as state}]
             (-> state
                 (assoc-in [:my-channels channel-name] {:server server}))))))

(defmethod handle "JOINED" [_c state m]
  (let [[_all channel-name username] (re-find #"\w+ ([^\s]+) ([^\s]+)" m)]
    (swap! state assoc-in [:channels channel-name :users username] {})))

(defmethod handle "CLIENTS" [_c state-atom m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        parts (string/split remaining #"\s+")
        channel-name (first parts)
        clients (rest parts)]
    (swap! state-atom update-in [:channels channel-name :users]
           (fn [users]
             (apply assoc users
                    (mapcat (fn [client] [client {}]) clients))))))

(defmethod handle "SAID" [_c state-atom m]
  (let [[_all channel-name username message] (re-find #"\w+ ([^\s]+) ([^\s]+) (.*)" m)
        now (u/curr-millis)]
    (swap! state-atom update-in [:channels channel-name :messages]
      (fn [messages]
        (take u/max-messages
          (conj messages {:text message
                          :timestamp now
                          :username username}))))))

(defmethod handle "SAIDEX" [_c state-atom m]
  (let [[_all channel-name username message] (re-find #"\w+ ([^\s]+) ([^\s]+) (.*)" m)
        now (u/curr-millis)]
    (swap! state-atom update-in [:channels channel-name :messages]
      (fn [messages]
        (take u/max-messages
          (conj messages {:text message
                          :timestamp now
                          :username username
                          :ex true}))))))

(defmethod handle "SAYPRIVATE" [_c state-atom m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)
        now (u/curr-millis)]
    (swap! state-atom
      (fn [state]
        (-> state
            (update-in [:channels (u/user-channel username) :messages]
              (fn [messages]
                (take u/max-messages
                  (conj messages {:text message
                                  :timestamp now
                                  :username (:username state)})))))))))

(defmethod handle "SAIDPRIVATE" [_c state-atom m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)
        now (u/curr-millis)
        channel-name (u/user-channel username)]
    (swap! state-atom
      (fn [state]
        (-> state
            (update-in [:channels channel-name :messages]
              (fn [messages]
                (take u/max-messages
                  (conj messages {:text message
                                  :timestamp now
                                  :username username}))))
            (assoc-in [:my-channels channel-name] {})
            (assoc :selected-tab-main "chat")
            (assoc :selected-tab-channels channel-name))))))

(defmethod handle "SAYPRIVATEEX" [_c state-atom m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)
        now (u/curr-millis)]
    (swap! state-atom
      (fn [state]
        (-> state
            (update-in [:channels (u/user-channel username) :messages]
              (fn [messages]
                (take u/max-messages
                  (conj messages {:text message
                                  :timestamp now
                                  :username (:username state)
                                  :ex true})))))))))

(defmethod handle "SAIDPRIVATEEX" [_c state-atom m]
  (let [[_all username message] (re-find #"\w+ ([^\s]+) (.*)" m)
        now (u/curr-millis)
        channel-name (u/user-channel username)]
    (swap! state-atom
      (fn [state]
        (-> state
            (update-in [:channels channel-name :messages]
              (fn [messages]
                (take u/max-messages
                  (conj messages {:text message
                                  :timestamp now
                                  :username username
                                  :ex true}))))
            (assoc-in [:my-channels channel-name] {})
            (assoc :selected-tab-main "chat")
            (assoc :selected-tab-channels channel-name))))))

(defmethod handle "JOINEDBATTLE" [_c state-atom m]
  (let [[_all battle-id username _ script-password] (re-find #"\w+ (\w+) ([^\s]+)( (.+))?" m)]
    (swap! state-atom
      (fn [state]
        (let [initial-status {}
              next-state (assoc-in state [:battles battle-id :users username] initial-status)]
          (cond-> next-state
                  (= battle-id (-> next-state :battle :battle-id))
                  (assoc-in [:battle :users username] initial-status)
                  script-password
                  (assoc-in [:battle :script-password] script-password)))))))

(defmethod handle "LEFT" [_c state-atom m]
  (let [[_all channel-name username] (re-find #"\w+ ([^\s]+) ([^\s]+)" m)]
    (swap! state-atom
           (fn [state]
             (let [next-state (update-in state [:channels channel-name :users] dissoc username)]
               (if (= (:username state) username) ; me
                 (update next-state :my-channels dissoc channel-name)
                 next-state))))))

(defmethod handle "REMOVESCRIPTTAGS" [_c state m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        scripttag-keys-parsed (map spring-script/parse-scripttag-key (string/split remaining #"\s+"))]
    (doseq [kws scripttag-keys-parsed]
      (when (seq kws)
        (swap! state
          (fn [state]
            (-> state
                (update-in (concat [:scripttags] (drop-last kws)) dissoc (last kws))
                (update-in (concat [:battle :scripttags] (drop-last kws)) dissoc (last kws)))))))))


(defn parse-addbot [m]
  (re-find #"\w+ (\w+) ([^\s]+) ([^\s]+) (\w+) (\w+) (.+)" m))

(defn parse-ai [ai]
  (when ai
    (re-find #"([^|]+)(\|([^\s]+))?" ai)))

(defmethod handle "ADDBOT" [_c state-atom m]
  (let [[_all battle-id bot-name owner battle-status team-color ai] (parse-addbot m)
        [_all ai-name _ ai-version] (parse-ai ai)
        bot {:bot-name bot-name
             :owner owner
             :battle-status (decode-battle-status battle-status)
             :team-color team-color
             :ai-name ai-name
             :ai-version ai-version}]
    (swap! state-atom
      (fn [state]
        (let [state (assoc-in state [:battles battle-id :bots bot-name] bot)]
          (if (= battle-id (-> state :battle :battle-id))
            (assoc-in state [:battle :bots bot-name] bot)
            state))))))

(defmethod handle "REMOVEBOT" [_c state-atom m]
  (if-let [[_all battle-id botname] (re-find #"\w+ (\w+) ([^\s]+)" m)]
    (swap! state-atom
      (fn [state]
        (let [next-state (update-in state [:battles battle-id :bots] dissoc botname)]
          (if (:battle next-state)
            (update-in next-state [:battle :bots] dissoc botname)
            next-state))))
    (let [[_all botname] (re-find #"\w+ (\w+)" m)]
      (swap! state-atom
             (fn [state]
               (if (:battle state)
                 (update-in state [:battle :bots] dissoc botname)
                 state))))))


(defn parse-joinbattle [m]
  (re-find #"\w+ ([^\s]+) ([^\s]+)( ([^\s]+))?" m))

(defmethod handle "JOINBATTLE" [_c state-atom m]
  (let [[_all battle-id hash-code _ channel-name] (parse-joinbattle m)]
    (swap! state-atom assoc
           :battle {:battle-id battle-id
                    :hash-code hash-code
                    :channel-name channel-name
                    :scripttags default-scripttags}
           :battle-map-details nil
           :battle-mod-details nil)))

(defmethod handle "REQUESTBATTLESTATUS" [client state-atom _m]
  (let [{:keys [battle preferred-color]} @state-atom
        battle-status (assoc default-battle-status
                             :id (battle/available-team-id battle)
                             :ally (battle/available-ally battle)
                             :mode false)
        color (or preferred-color
                  (u/random-color))
        msg (str "MYBATTLESTATUS " (encode-battle-status battle-status) " " color)]
    (message/send-message client msg)))

(defmethod handle "BATTLECLOSED" [_c state-atom m]
  (let [[_all battle-id] (re-find #"\w+ (\w+)" m)]
    (swap! state-atom
      (fn [state]
        (let [curr-battle-id (-> state :battle :battle-id)
              next-state (update state :battles dissoc battle-id)]
          (if (= battle-id curr-battle-id)
            (dissoc next-state :battle)
            next-state))))))

(defmethod handle "AGREEMENT" [_c state-atom m]
  (let [[_all agreement-text] (re-find #"\w+ (.*)" m)]
    (swap! state-atom update :agreement-part str "\n" agreement-text)))

(defmethod handle "AGREEMENTEND" [_c state-atom _m]
  (swap! state-atom
         (fn [state]
           (-> state
               (assoc :agreement (:agreement-part state))
               (dissoc :agreement-part)))))

(defmethod handle "OPENBATTLE" [_c _state-atom m]
  (log/info "Battle opened:" m))

(defmethod handle "OPENBATTLEFAILED" [_c state-atom _m]
  (swap! state-atom dissoc :battle))

(defmethod handle "COMPFLAGS" [_c state-atom m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        compflags (string/split remaining #"\s+")]
    (swap! state-atom assoc :compflags compflags)))

(defmethod handle "FAILED" [_client state m]
  (swap! state assoc :last-failed-message m))

(defmethod handle "JOINBATTLEFAILED" [_client state-atom m]
  (swap! state-atom
         (fn [state]
           (-> state
               (dissoc :battle)
               (assoc :last-failed-message m)))))


(defmethod handle "CHANNEL" [_client state-atom m]
  (let [[_all channel-name user-count topic] (re-find #"\w+ ([^\s]+) (\w+) (.+)?" m)]
    (swap! state-atom update-in [:channels channel-name] merge {:channel-name channel-name
                                                                :user-count user-count
                                                                :topic topic})))

(defmethod handle "CHANNELTOPIC" [_client state-atom m]
  (let [[_all channel-name topic] (re-find #"\w+ ([^\s]+) (\w+)" m)]
    (swap! state-atom update-in [:channels channel-name] merge {:topic topic})))

(defmethod handle "ENDOFCHANNELS" [_client _state-atom _m]
  (log/debug "Ignore ENDOFCHANNELS message"))

(defn normalize-startrect [rect-str]
  (let [parsed (Long/parseLong rect-str)]
    (double (/ parsed 200))))

(defmethod handle "ADDSTARTRECT" [_client state-atom m]
  (let [[_all allyteam left top right bottom] (re-find #"\w+ (\w+) (\w+) (\w+) (\w+) (\w+)" m)
        allyteam-kw (keyword (str "allyteam" allyteam))]
    (swap! state-atom update-in [:battle :scripttags :game allyteam-kw]
           (fn [allyteam]
             (assoc allyteam
                    :startrectleft (normalize-startrect left)
                    :startrecttop (normalize-startrect top)
                    :startrectright (normalize-startrect right)
                    :startrectbottom (normalize-startrect bottom))))))

(defmethod handle "REMOVESTARTRECT" [_client state-atom m]
  (let [[_all allyteam] (re-find #"\w+ (\w+)" m)
        allyteam-kw (keyword (str "allyteam" allyteam))]
    (swap! state-atom update-in [:battle :scripttags :game allyteam-kw]
           (fn [allyteam]
             (dissoc allyteam :startrectleft :startrecttop :startrectright :startrectbottom)))))

(defmethod handle "RING" [_client _state-atom m]
  (let [[_all username] (re-find #"\w+ ([^\s]+)" m)]
    (log/info "Playing ring sound from" username)
    (sound/play-ring)))
