(ns spring-lobby.client
  (:require
    [aleph.tcp :as tcp]
    [byte-streams]
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [gloss.core :as gloss]
    [gloss.io :as gio]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.client.message :as message]
    [spring-lobby.git :as git]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.net URL)
    (java.nio ByteBuffer)
    (java.security MessageDigest)
    (java.util Base64)
    (java.util.jar Manifest)
    (manifold.stream SplicedStream)))


(set! *warn-on-reflection* true)


(def ^:dynamic handler handler/handle) ; for overriding in dev


(def default-ssl false) ; TODO


(defn manifest-attributes [url]
  (-> (str "jar:" url "!/META-INF/MANIFEST.MF")
      URL. .openStream Manifest. .getMainAttributes))
      ;(.getValue "Build-Number")))

; https://stackoverflow.com/a/16431226/984393
(defn manifest-version []
  (try
    (when-let [clazz (Class/forName "spring_lobby")]
      (log/debug "Discovered class" clazz)
      (when-let [loc (-> (.getProtectionDomain clazz) .getCodeSource .getLocation)]
        (log/debug "Discovered location" loc)
        (-> (str "jar:" loc "!/META-INF/MANIFEST.MF")
            URL. .openStream Manifest. .getMainAttributes
            (.getValue "Build-Number"))))
    (catch Exception e
      (log/warn e "Unable to read version from manifest"))))

(defn agent-string []
  (str "alt-spring-lobby-"
       (or (manifest-version)
           (try
             (spring/short-git-commit (git/tag-or-latest-id (io/file ".")))
             (catch Exception e
               (log/warn e "Error getting git version")))
           (slurp (io/resource "alt-spring-lobby.version"))
           "UNKNOWN")))


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
  ([host port {:keys [ssl] :or {ssl default-ssl}}]
   (d/chain (tcp/client {:host host
                         :port port
                         :ssl? ssl})
     #(wrap-duplex-stream protocol %))))


(defmethod handler/handle :default [_client state m]
  (log/trace "no handler for message" (str "'" m "'"))
  (swap! state assoc :last-failed-message m))

(defmethod handler/handle "PONG" [_client state _m]
  (swap! state assoc :last-pong (System/currentTimeMillis)))

(defmethod handler/handle "SETSCRIPTTAGS" [_client state m]
  (let [[_all script-tags-raw] (re-find #"\w+ (.*)" m)
        parsed (spring-script/parse-scripttags script-tags-raw)]
    (swap! state update-in [:battle :scripttags] u/deep-merge parsed)))

(defmethod handler/handle "TASSERVER" [_client state m]
  (swap! state assoc :tas-server m))

(defmethod handler/handle "ACCEPTED" [_client state _m]
  (swap! state assoc :accepted true))

(defmethod handler/handle "MOTD" [_client _state m]
  (log/trace "motd" m))

(defmethod handler/handle "LOGININFOEND" [_client _state _m]
  (log/trace "end of login info"))


(defn parse-battleopened [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)" m))

(defmethod handler/handle "BATTLEOPENED" [_c state m]
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
                  :channel-name channel-name
                  :users {host-username {}}}]
      (swap! state assoc-in [:battles battle-id] battle))
    (log/warn "Unable to parse BATTLEOPENED")))

(defn parse-updatebattleinfo [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) (.+)" m))

(defmethod handler/handle "UPDATEBATTLEINFO" [_c state m]
  (let [[_all battle-id battle-spectators battle-locked battle-maphash battle-map] (parse-updatebattleinfo m)]
    (swap! state update-in [:battles battle-id]
           assoc
           :battle-id battle-id
           :battle-spectators battle-spectators
           :battle-locked battle-locked
           :battle-maphash battle-maphash
           :battle-map battle-map)))


(defn ping-loop [state-atom c]
  (swap! state-atom
    assoc
    :ping-loop
    (future
      (try
        (log/info "ping loop thread started")
        (loop []
          (async/<!! (async/timeout 30000))
          (when (message/send-message c "PING")
            (when-not (Thread/interrupted)
              (recur))))
        (log/info "ping loop ended")
        (catch Exception e
          (log/error e "Error in ping loop"))))))


(defn print-loop
  [state-atom c]
  (swap! state-atom
    assoc
    :print-loop
    (future
      (try
        (log/info "print loop thread started")
        (loop []
          (when-let [d (s/take! c)]
            (when-let [m @d]
              (log/info "<" (str "'" m "'"))
              (try
                (handler c state-atom m)
                (catch Exception e
                  (log/error e "Error handling message")))
              (when-not (Thread/interrupted)
                (recur)))))
        (log/info "print loop ended")
        (catch Exception e
          (log/error e "Error in print loop"))))))

(defn base64-md5 [password]
  (base64-encode (md5-bytes password)))

(defn login
  ([client username password]
   (login client "*" username password))
  ([client local-addr username password]
   (let [pw-md5-base64 (base64-md5 password)
         git-ref "b6e84c6023cbffac"
         user-id (rand-int Integer/MAX_VALUE)
         compat-flags "sp u"
         msg (str "LOGIN " username " " pw-md5-base64 " 0 " local-addr
                  " " (agent-string) "\t" user-id " " git-ref "\t" compat-flags)]
     (message/send-message client msg))))


(defn connect
  [state-atom client]
  (let [{:keys [my-channels server password username]} @state-atom]
    (when default-ssl ; TODO
      (message/send-message client "STLS"))
    (print-loop state-atom client)
    (message/send-message client "LISTCOMPFLAGS")
    (login client "*" username password)
    (message/send-message client "CHANNELS")
    (doseq [channel my-channels]
      (let [[channel-name {channel-server :server}] channel]
        (when (= server channel-server)
          (message/send-message client (str "JOIN " channel-name)))))
    (ping-loop state-atom client)))

(defn disconnect [^SplicedStream c]
  (log/info "disconnecting")
  (when-not (s/closed? c)
    (message/send-message c "EXIT"))
  (s/close! c)
  (log/info "connection closed?" (s/closed? c)))

(defmethod handler/handle "DENIED" [client state-atom m]
  (log/info (str "Login denied: '" m "'"))
  (disconnect client)
  (swap! state-atom
    (fn [state]
      (-> state
          (dissoc :client :client-deferred)
          (assoc :login-error m)))))
