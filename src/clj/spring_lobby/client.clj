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
    (manifold.stream SplicedStream)))


(set! *warn-on-reflection* true)


(declare ping-loop)


(def ^:dynamic handler handler/handle) ; for overriding in dev


; https://github.com/spring/uberserver/blob/e63fee427136e5bafc1b20c8c984a5c348bc6624/protocol/Protocol.py#L190
(def compflags "sp b t u cl lu")
  ; ^ found at springfightclub, was "sp u"


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
   (client server-url nil))
  ([server-url {:keys [ssl]}]
   (let [[host port] (parse-host-port server-url)]
     (d/chain (tcp/client {:host host
                           :port port
                           :ssl? ssl})
       #(wrap-duplex-stream protocol %)))))


(defmethod handler/handle :default [state-atom server-url m]
  (log/trace "no handler for message" (str "'" m "'"))
  (swap! state-atom assoc-in [:by-server server-url :last-failed-message] m))

(defmethod handler/handle "PONG" [state-atom server-url _m]
  (swap! state-atom assoc-in [:by-server server-url :last-pong] (u/curr-millis)))

(defmethod handler/handle "SETSCRIPTTAGS" [state-atom server-url m]
  (let [[_all script-tags-raw] (re-find #"\w+ (.*)" m)
        parsed (spring-script/parse-scripttags script-tags-raw)]
    (swap! state-atom update-in [:by-server server-url :battle :scripttags] u/deep-merge parsed)))


(defn handle-tasserver [state-atom server-key m]
  (let [state (swap! state-atom assoc-in [:by-server server-key :tas-server] m)
        client (-> state :by-server (get server-key) :client-data :client)]
    (message/send-message client "LISTCOMPFLAGS")))

(defmethod handler/handle "TASSERVER" [state-atom server-key m]
  (handle-tasserver state-atom server-key m))

(defmethod handler/handle "TASServer" [state-atom server-key m]
  (handle-tasserver state-atom server-key m))

(defmethod handler/handle "ACCEPTED" [state-atom server-key m]
  (let [[_all username] (re-find #"\w+ (.*)" m)
        state (swap! state-atom
                (fn [state]
                  (let [server-url (-> state :by-server (get server-key) :client-data :server-url)]
                    (-> state
                        (update-in [:by-server server-key]
                             assoc
                             :username username
                             :accepted true)
                        (update :login-error dissoc server-url)))))
        server-data (-> state :by-server (get server-key))
        client (-> server-data :client-data :client)
        my-channels (concat
                      (-> state :my-channels (get server-key))
                      (:global-chat-channels state))]
    (message/send-message client "CHANNELS")
    (when (u/matchmaking? server-data)
      (message/send-message client "c.matchmaking.list_all_queues"))
    (doseq [channel my-channels]
      (let [[channel-name _] channel]
        (when-not (or (u/battle-channel-name? channel-name)
                      (u/user-channel-name? channel-name))
          (message/send-message client (str "JOIN " channel-name)))))
    (ping-loop state-atom server-key client)))

(defmethod handler/handle "MOTD" [_state-atom _server-url m]
  (log/trace "motd" m))

(defmethod handler/handle "LOGININFOEND" [_state-atom _server-url _m]
  (log/trace "end of login info"))

(defn ping-loop [state-atom server-key client]
  (let [ping-loop (future
                    (try
                      (log/info "ping loop thread started")
                      (loop []
                        (async/<!! (async/timeout 30000))
                        (when (message/send-message client "PING")
                          (u/append-console-log state-atom server-key :client "PING")
                          (when-not (Thread/interrupted)
                            (recur))))
                      (log/info "ping loop ended")
                      (catch Exception e
                        (log/error e "Error in ping loop"))))]
    (swap! state-atom assoc-in [:by-server server-key :ping-loop] ping-loop)))


(defn print-loop
  [state-atom server-key client]
  (let [print-loop (future
                     (try
                       (log/info "print loop thread started")
                       (loop []
                         (when-let [d (s/take! client)]
                           (when-let [m @d]
                             (log/info (str "[" server-key "]") "<" (str "'" m "'"))
                             (try
                               (handler state-atom server-key m)
                               (u/append-console-log state-atom server-key :server m)
                               (catch Exception e
                                 (log/error e "Error handling message" (str "'" m "'")))
                               (catch Throwable t
                                 (log/error t "Critical error handling message" (str "'" m "'"))
                                 (throw t)))
                             (when-not (Thread/interrupted)
                               (recur)))))
                       (log/info "print loop ended")
                       (catch Exception e
                         (log/error e "Error in print loop"))))]
    (swap! state-atom assoc-in [:by-server server-key :print-loop] print-loop)))

(defn login
  [client username password {:keys [client-id local-addr user-agent]
                             :or {client-id 0
                                  local-addr "*"
                                  user-agent (u/agent-string)}}]
  (when (string/blank? password)
    (throw (ex-info "Password is blank" {:username username})))
  (let [pw-md5-base64 (u/base64-md5 password)
        msg (str "LOGIN " username " " pw-md5-base64 " 0 " local-addr
                 " " user-agent "\t" client-id "\t" compflags)]
    (message/send-message client msg)))


(defmethod handler/handle "COMPFLAGS" [state-atom server-key m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        compflags (set (string/split remaining #"\s+"))
        state (swap! state-atom update-in [:by-server server-key]
                 (fn [state]
                   (-> state
                       (assoc :compflags compflags)
                       (assoc-in [:client-data :compflags] compflags))))
        accepted (-> state :by-server (get server-key) :accepted)
        {:keys [client ssl username password]} (-> state :by-server (get server-key) :client-data)]
    (if (and ssl (contains? compflags "token-auth"))
      (message/send-message client (str "c.user.get_token_by_name " username "\t" password))
      (when (not accepted)
        (let [client-id (u/client-id state-atom state)]
          (login client username password (merge
                                            {:client-id client-id}
                                            (when-let [user-agent (:user-agent-override state)]
                                              {:user-agent user-agent}))))))))


(defn connect
  [state-atom {:keys [client-data server-key]}]
  (let [{:keys [client]} client-data]
    (print-loop state-atom server-key client)))

(defn disconnect [^SplicedStream c]
  (log/info "disconnecting")
  (if-not (s/closed? c)
    (message/send-message c "EXIT")
    (log/warn "Client was already closed"))
  (s/close! c)
  (log/info "connection closed?" (s/closed? c)))

(defmethod handler/handle "DENIED" [state-atom server-key m]
  (log/info (str "Login denied: '" m "'"))
  (let [[old-state] (swap-vals! state-atom
                      (fn [state]
                        (let [server-url (-> state :by-server (get server-key) :client-data :server-url)]
                          (-> state
                              (update-in [:by-server server-key] dissoc :accepted :client-data)
                              (assoc-in [:login-error server-url] m)))))
        client (-> old-state :by-server (get server-key) :client-data :client)]
    (disconnect client)))

(defmethod handler/handle "NO" [state-atom server-key m]
  (let [[_all command] (re-find #"[^\s]+ cmd=(.*)" m)]
    (if (string/blank? command)
      (log/error "Error parsing NO response" m)
      (let [[_all action args] (re-find #"([^\s]+)\s+(.*)" command)]
        (if (string/blank? action)
          (log/error "Unable to parse NO response command" command)
          (case action
            "c.user.get_token_by_name"
            (let [msg (string/trim args)
                  [old-state] (swap-vals! state-atom
                                 (fn [state]
                                    (let [server-url (-> state :by-server (get server-key) :client-data :server-url)]
                                      (-> state
                                          (update-in [:by-server server-key] dissoc :accepted :client-data)
                                          (assoc-in [:login-error server-url] msg)))))
                  client (-> old-state :by-server (get server-key) :client-data :client)]
              (disconnect client))
            nil))))))
