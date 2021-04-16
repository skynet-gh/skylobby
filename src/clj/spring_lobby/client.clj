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
    [spring-lobby.client.handler :as handler]
    [spring-lobby.client.message :as message]
    spring-lobby.client.handler.tei
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.nio ByteBuffer)
    (java.security MessageDigest)
    (java.util Base64)
    (manifold.stream SplicedStream)))


(set! *warn-on-reflection* true)


(def ^:dynamic handler handler/handle) ; for overriding in dev


; https://github.com/spring/uberserver/blob/e63fee427136e5bafc1b20c8c984a5c348bc6624/protocol/Protocol.py#L190
(def compflags "sp b t u cl lu")
  ; ^ found at springfightclub, was "sp u"


(defn agent-string []
  (str u/app-name
       "-"
       (u/app-version)))


(def default-port 8200)


(defn parse-host-port [server-url]
  (if-let [[_all host port] (re-find #"(.+):(\d+)$" server-url)]
    [host (edn/read-string port)]
    [server-url default-port]))


(def default-scripttags ; TODO read these from lua in map, mod/game, and engine
  {:game
   {:startpostype 1
    :modoptions {}}})

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
      ^ByteBuffer
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
(defn md5-bytes [^String s]
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
   (client host port nil))
  ([host port {:keys [ssl]}]
   (d/chain (tcp/client {:host host
                         :port port
                         :ssl? ssl})
     #(wrap-duplex-stream protocol %))))


(defmethod handler/handle :default [state-atom server-url m]
  (log/trace "no handler for message" (str "'" m "'"))
  (swap! state-atom assoc-in [:by-server server-url :last-failed-message] m))

(defmethod handler/handle "PONG" [state-atom server-url _m]
  (swap! state-atom assoc-in [:by-server server-url :last-pong] (u/curr-millis)))

(defmethod handler/handle "SETSCRIPTTAGS" [state-atom server-url m]
  (let [[_all script-tags-raw] (re-find #"\w+ (.*)" m)
        parsed (spring-script/parse-scripttags script-tags-raw)]
    (swap! state-atom update-in [:by-server server-url :battle :scripttags] u/deep-merge parsed)))

(defmethod handler/handle "TASSERVER" [state-atom server-url m]
  (swap! state-atom assoc-in [:by-server server-url :tas-server] m))

(defmethod handler/handle "TASServer" [state-atom server-url m]
  (swap! state-atom assoc-in [:by-server server-url :tas-server] m))

(defmethod handler/handle "ACCEPTED" [state-atom server-url m]
  (let [[_all username] (re-find #"\w+ (.*)" m)]
    (swap! state-atom update-in [:by-server server-url]
           assoc
           :username username
           :accepted true)))

(defmethod handler/handle "MOTD" [_state-atom _server-url m]
  (log/trace "motd" m))

(defmethod handler/handle "LOGININFOEND" [_state-atom _server-url _m]
  (log/trace "end of login info"))


(defn parse-battleopened [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+)\s+([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)(\t([^\t]+))?" m))

(defmethod handler/handle "BATTLEOPENED" [state-atom server-url m]
  (if-let [[_all battle-id battle-type battle-nat-type host-username battle-ip battle-port battle-maxplayers battle-passworded battle-rank battle-maphash battle-engine battle-version battle-map battle-title battle-modname _ channel-name] (parse-battleopened m)]
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
                  :channel-name channel-name
                  :users {host-username {}}}]
      (swap! state-atom assoc-in [:by-server server-url :battles battle-id] battle))
    (log/warn "Unable to parse BATTLEOPENED" (pr-str m))))

(defn parse-updatebattleinfo [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) (.+)" m))

(defmethod handler/handle "UPDATEBATTLEINFO" [state-atom server-url m]
  (let [[_all battle-id battle-spectators battle-locked battle-maphash battle-map] (parse-updatebattleinfo m)]
    (swap! state-atom update-in [:by-server server-url]
      (fn [state]
        (let [my-battle-id (-> state :battle :battle-id)
              old-battle-map (-> state (get :battles) (get battle-id) :battle-map)
              my-battle (= my-battle-id battle-id)
              map-changed (not= old-battle-map battle-map)]
          (cond-> state
                  true
                  (update-in [:battles battle-id] assoc
                    :battle-id battle-id
                    :battle-spectators battle-spectators
                    :battle-locked battle-locked
                    :battle-maphash battle-maphash
                    :battle-map battle-map)
                  (and my-battle map-changed)
                  (assoc :battle-map-details nil)))))))


(defn ping-loop [state-atom server-url]
  (swap! state-atom update-in [:by-server server-url]
    (fn [{:keys [client] :as server-data}]
      (assoc server-data
        :ping-loop
        (future
          (try
            (log/info "ping loop thread started")
            (loop []
              (async/<!! (async/timeout 30000))
              (when (message/send-message client "PING")
                (u/update-console-log state-atom :client client "PING")
                (when-not (Thread/interrupted)
                  (recur))))
            (log/info "ping loop ended")
            (catch Exception e
              (log/error e "Error in ping loop"))))))))


(defn print-loop
  [state-atom server-url]
  (swap! state-atom update-in [:by-server server-url]
    (fn [{:keys [client] :as server-data}]
      (assoc server-data
        :print-loop
        (future
          (try
            (log/info "print loop thread started")
            (loop []
              (when-let [d (s/take! client)]
                (when-let [m @d]
                  (log/info (str "[" server-url "]") "<" (str "'" m "'"))
                  (try
                    (handler state-atom server-url m)
                    (u/update-console-log state-atom :server client m)
                    (catch Exception e
                      (log/error e "Error handling message")))
                  (when-not (Thread/interrupted)
                    (recur)))))
            (log/info "print loop ended")
            (catch Exception e
              (log/error e "Error in print loop"))))))))

(defn base64-md5 [password]
  (base64-encode (md5-bytes password)))

(defn login
  ([client username password]
   (login client "*" username password))
  ([client local-addr username password]
   (when (string/blank? password)
     (throw (ex-info "Password is blank" {:username username})))
   (let [pw-md5-base64 (base64-md5 password)
         user-id 0 ; (rand-int Integer/MAX_VALUE)
         msg (str "LOGIN " username " " pw-md5-base64 " 0 " local-addr
                  " " (agent-string) "\t" user-id "\t" compflags)]
     (message/send-message client msg))))


(defn connect
  [state-atom server-url]
  (let [state @state-atom
        {:keys [my-channels password username]} state
        {:keys [client]} (-> state :by-server (get server-url))]
    (print-loop state-atom server-url)
    (message/send-message client "LISTCOMPFLAGS")
    (login client "*" username password)
    (message/send-message client "CHANNELS")
    (doseq [channel (get my-channels server-url)]
      (let [[channel-name _] channel]
        (when-not (or (u/battle-channel-name? channel-name)
                      (u/user-channel-name? channel-name))
          (message/send-message client (str "JOIN " channel-name)))))
    (ping-loop state-atom server-url)))

(defn disconnect [^SplicedStream c]
  (log/info "disconnecting")
  (if-not (s/closed? c)
    (message/send-message c "EXIT")
    (log/warn "Client was already closed"))
  (s/close! c)
  (log/info "connection closed?" (s/closed? c)))

(defmethod handler/handle "DENIED" [state-atom server-url m]
  (log/info (str "Login denied: '" m "'"))
  (let [[old-state] (swap-vals! state-atom update-in [:by-server server-url]
                      (fn [state]
                        (-> state
                            (dissoc :client :client-deferred)
                            (assoc :login-error m))))
        client (-> old-state :by-server (get server-url) :client)]
    (disconnect client)))
