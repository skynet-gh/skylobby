(ns spring-lobby.client
  (:require
    [aleph.tcp :as tcp]
    [byte-streams]
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.string :as string]
    [gloss.core :as gloss]
    [gloss.io :as gio]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [spring-lobby.spring :as spring]
    [taoensso.timbre :as log])
  (:import
    (java.nio ByteBuffer)
    (java.security MessageDigest)
    (java.util Base64)))


(def agent-string "alt-spring-lobby-0.1")


(def default-port 8200)


(defn parse-host-port [server-url]
  (if-let [[_all host port] (re-find #"(.+):(\d+)$" server-url)]
    [host (edn/read-string port)]
    [server-url default-port]))


; https://springrts.com/dl/LobbyProtocol/ProtocolDescription.html

(def default-battle-status
  {:ready false
   :ally 0
   :handicap 0
   :mode 1
   :sync 1
   :id 0
   :side 0})

(def protocol
  (gloss/compile-frame
    (gloss/delimited-frame
      ["\n"]
      (gloss/string :utf-8))
    str
    str)) ; TODO parse here

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
      :prefix 6
      :side 2
      :sync 2
      :pad 4
      :handicap 7
      :mode 1
      :ally 4
      :id 4
      :ready 1
      :suffix 1)))


(def default-client-status "0")


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
      (gio/to-byte-buffer
        (gio/encode battle-status-protocol
          (assoc
            (merge default-battle-status battle-status)
            :prefix 0
            :pad 0
            :suffix 0))))))


; https://stackoverflow.com/a/39188819/984393
(defn base64-encode [bs]
  (.encodeToString (Base64/getEncoder) bs))

; https://gist.github.com/jizhang/4325757
(defn md5-bytes [s]
  (let [algorithm (MessageDigest/getInstance "MD5")]
    (.digest algorithm (.getBytes s))))


; https://aleph.io/examples/literate.html#aleph.examples.tcp

(defn wrap-duplex-stream
  [protocol s]
  (let [out (s/stream)]
    (s/connect
      (s/map #(gio/encode protocol %) out)
      s)
    (s/splice
      out
      (gio/decode-stream s protocol))))

(defn client
  ([server-url]
   (apply client (parse-host-port server-url)))
  ([host port]
   (d/chain (tcp/client {:host host
                         :port port})
     #(wrap-duplex-stream protocol %))))


(defn send-message [c m]
  (when c
    (log/info ">" (str "'" m "'"))
    @(s/put! c (str m "\n"))))

(defmulti handle
  (fn [_client _state m]
    (-> m
        (string/split #"\s")
        first)))

(defmethod handle :default [_client state m]
  (log/trace "no handler for message" (str "'" m "'"))
  (swap! state assoc :last-failed-message m))

(defmethod handle "PONG" [_client state _m]
  (swap! state assoc :last-pong (System/currentTimeMillis)))

(defmethod handle "SETSCRIPTTAGS" [_client state m]
  (let [[_all script-tags-raw] (re-find #"\w+ (.*)" m)
        parsed (spring/parse-scripttags script-tags-raw)]
    (swap! state update-in [:battle :scripttags] spring/deep-merge parsed)))

(defmethod handle "TASSERVER" [_client state m]
  (swap! state assoc :tas-server m))

(defmethod handle "ACCEPTED" [_client state _m]
  (swap! state assoc :accepted true))

(defmethod handle "MOTD" [_client _state m]
  (log/trace "motd" m))

(defmethod handle "LOGININFOEND" [_client _state _m]
  (log/trace "end of login info"))

(defmethod handle "FAILED" [_client state m]
  (swap! state assoc :last-failed-message m))

(defmethod handle "JOINBATTLEFAILED" [_client state m]
  (swap! state assoc :last-failed-message m))


(defn parse-adduser [m]
  (re-find #"\w+ (\w+) ([^\s]+) (\w+) (.*)" m))

(defmethod handle "ADDUSER" [_c state m]
  (let [[_all username country user-id user-agent] (parse-adduser m)
        user {:username username
              :country country
              :user-id user-id
              :user-agent user-agent
              :client-status (decode-client-status default-client-status)}]
    (swap! state assoc-in [:users username] user)))

(defmethod handle "REMOVEUSER" [_c state m]
  (let [[_all username] (re-find #"\w+ (\w+)" m)]
    (swap! state update :users dissoc username)))


(defn parse-addbot [m]
  (re-find #"\w+ (\w+) (\w+) (\w+) (\w+) (\w+) ([^\s]+)" m))

(defmethod handle "ADDBOT" [_c state m]
  (let [[_all battle-id bot-name owner battle-status team-color ai] (parse-addbot m)
        [_all ai-name ai-version] (when ai (re-find #"([^\s]+)\|([^\s]+)" ai))
        bot {:bot-name bot-name
             :owner owner
             :battle-status (decode-battle-status battle-status)
             :team-color team-color
             :ai-name ai-name
             :ai-version ai-version}]
    (swap! state
      (fn [state]
        (let [state (assoc-in state [:battles battle-id :bots bot-name] bot)]
          (if (= battle-id (-> state :battle :battle-id))
            (assoc-in state [:battle :bots bot-name] bot)
            state))))))

(defmethod handle "REMOVEBOT" [_c state m]
  (if-let [[_all battle-id botname] (re-find #"\w+ (\w+) (\w+)" m)]
    (swap! state
      (fn [state]
        (-> state
            (update-in [:battles battle-id :bots] dissoc botname)
            (update-in [:battle :bots] dissoc botname))))
    (let [[_all botname] (re-find #"\w+ (\w+)" m)]
      (swap! state update-in [:battle :bots] dissoc botname))))


(defn parse-battleopened [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)" m))

(defmethod handle "BATTLEOPENED" [_c state m]
  (if-let [[_all battle-id battle-type battle-nat-type host-username battle-ip battle-port battle-maxplayers battle-passworded battle-rank battle-maphash battle-engine battle-version battle-map battle-title battle-modname channel-name] (parse-battleopened m)]
    (let [battle {:battle-id battle-id
                  :battle-type battle-type
                  :battle-nat-type battle-nat-type
                  :host-username host-username
                  :battle-ip battle-ip
                  :battle-port battle-port
                  :battle-maxplayers battle-maxplayers
                  :battle-passworded battle-passworded
                  :battle-rank battle-rank
                  :battle-maphash battle-maphash
                  :battle-engine battle-engine
                  :battle-version battle-version
                  :battle-map battle-map
                  :battle-title battle-title
                  :battle-modname battle-modname
                  :channel-name channel-name}]
      (swap! state assoc-in [:battles battle-id] battle))
    (log/warn "Unable to parse BATTLEOPENED")))

(defn parse-updatebattleinfo [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) (.+)" m))

(defmethod handle "UPDATEBATTLEINFO" [_c state m]
  (let [[_all battle-id battle-spectators battle-locked battle-maphash battle-map] (parse-updatebattleinfo m)]
    (swap! state update-in [:battles battle-id]
           assoc
           :battle-id battle-id
           :battle-spectators battle-spectators
           :battle-locked battle-locked
           :battle-maphash battle-maphash
           :battle-map battle-map)))

(defmethod handle "BATTLECLOSED" [_c state m]
  (let [[_all battle-id] (re-find #"\w+ (\w+)" m)]
    (swap! state
      (fn [state]
        (let [battle-name (-> state :battles (get battle-id) :battle-name)
              curr-battle-name (-> state :battle :battle-name)
              state (update state :battles dissoc battle-id)]
          (if (= battle-name curr-battle-name)
            (dissoc state :battle)
            state))))))

(defmethod handle "CLIENTSTATUS" [_c state m]
  (let [[_all username client-status] (re-find #"\w+ (\w+) (\w+)" m)]
    (swap! state assoc-in [:users username :client-status] (decode-client-status client-status))))

(defmethod handle "JOIN" [_c state m]
  (let [[_all channel-name] (re-find #"\w+ (\w+)" m)]
    (swap! state assoc-in [:my-channels channel-name] {})))

(defmethod handle "JOINED" [_c state m]
  (let [[_all channel-name username] (re-find #"\w+ (\w+) (\w+)" m)]
    (swap! state assoc-in [:channels channel-name :users username] {})))

(defn parse-joinbattle [m]
  (re-find #"\w+ ([^\s]+) ([^\s]+) ([^\s]+)" m))

(defmethod handle "JOINBATTLE" [_c state m]
  (let [[_all battle-id hash-code channel-name] (parse-joinbattle m)]
    (swap! state assoc :battle {:battle-id battle-id
                                :hash-code hash-code
                                :channel-name channel-name
                                :scripttags spring/default-scripttags})))

(defmethod handle "JOINEDBATTLE" [_c state m]
  (let [[_all battle-id username] (re-find #"\w+ (\w+) (\w+)" m)]
    (swap! state
      (fn [state]
        (let [initial-status {}
              state (assoc-in state [:battles battle-id :users username] initial-status)]
          (if (= battle-id (-> state :battle :battle-id))
            (assoc-in state [:battle :users username] initial-status)
            state))))))

(defmethod handle "LEFT" [_c state m]
  (let [[_all _channel-name username] (re-find #"\w+ (\w+) (\w+)" m)]
    (swap! state update-in [:channels :users] dissoc username)))

(defmethod handle "LEFTBATTLE" [_c state m]
  (let [[_all battle-id username] (re-find #"\w+ (\w+) (\w+)" m)]
    (swap! state
      (fn [state]
        (update-in
          (if (= username (:username state))
            (dissoc state :battle)
            (update-in state [:battle :users] dissoc username))
          [:battles battle-id :users] dissoc username)))))

(defmethod handle "REQUESTBATTLESTATUS" [c _state _m]
  (send-message c "MYBATTLESTATUS 0 0")) ; TODO real status

(defmethod handle "CLIENTBATTLESTATUS" [_c state m]
  (let [[_all username battle-status team-color] (re-find #"\w+ (\w+) (\w+) (\w+)" m)]
    (swap! state update-in [:battle :users username]
           assoc
           :battle-status (decode-battle-status battle-status)
           :team-color team-color)))

(defmethod handle "UPDATEBOT" [_c state m]
  (let [[_all battle-id username battle-status team-color] (re-find #"\w+ (\w+) (\w+) (\w+) (\w+)" m)
        decoded-status (decode-battle-status battle-status)
        bot-data {:battle-status decoded-status
                  :team-color team-color}]
    (log/debug username (pr-str decoded-status) team-color)
    (swap! state
      (fn [state]
        (let [state (update-in state [:battles battle-id :bots username] merge bot-data)]
          (if (= battle-id (-> state :battle :battle-id))
            (update-in state [:battle :bots username] merge bot-data)
            state))))))


(defn ping-loop [state-atom c]
  (swap! state-atom
    assoc
    :ping-loop
    (future
      (log/info "ping loop thread started")
      (loop []
        (async/<!! (async/timeout 30000))
        (when (send-message c "PING")
          (when-not (Thread/interrupted)
            (recur))))
      (log/info "ping loop ended"))))

(defn print-loop [state-atom c]
  (swap! state-atom
    assoc
    :print-loop
    (future
      (log/info "print loop thread started")
      (loop []
        (when-let [d (s/take! c)]
          (when-let [m @d]
            (log/info "<" (str "'" m "'"))
            (try
              (handle c state-atom m)
              (catch Exception e
                (log/error e "Error handling message")))
            (when-not (Thread/interrupted)
              (recur)))))
      (log/info "print loop ended"))))

(defn exit [c]
  (send-message c "EXIT"))

(defn login
  [client local-addr username password]
  (let [pw-md5-base64 (base64-encode (md5-bytes password))
        git-ref "b6e84c6023cbffac"
        user-id (rand-int Integer/MAX_VALUE)
        compat-flags "sp u"
        msg (str "LOGIN " username " " pw-md5-base64 " 0 " local-addr
                 " " agent-string "\t" user-id " " git-ref "\t" compat-flags)]
    (send-message client msg)))


(defn connect
  ([state-atom]
   (connect state-atom (client (:server-url @state-atom))))
  ([state-atom client]
   (let [{:keys [username password]} @state-atom]
     (print-loop state-atom client)
     (login client "*" username password)
     (ping-loop state-atom client))))

(defn disconnect [c]
  (log/info "disconnecting")
  (exit c)
  (.close c)
  (log/info "connection closed?" (.isClosed c)))

(defmethod handle "DENIED" [client state m]
  (log/info (str "Login denied: '" m "'"))
  (disconnect client)
  (swap! state
    (fn [state]
      (-> state
          (dissoc :client :client-deferred)
          (assoc :login-error m)))))

(defn open-battle [c {:keys [battle-type nat-type battle-password host-port max-players mod-hash rank map-hash
                             engine engine-version map-name title mod-name]
                      :or {battle-type 0
                           nat-type 0
                           battle-password "*"
                           host-port 8452
                           max-players 8
                           rank 0
                           engine "Spring"}}]
  (send-message c
    (str "OPENBATTLE " battle-type " " nat-type " " battle-password " " host-port " " max-players
         " " mod-hash " " rank " " map-hash " " engine "\t" engine-version "\t" map-name "\t" title
         "\t" mod-name)))
