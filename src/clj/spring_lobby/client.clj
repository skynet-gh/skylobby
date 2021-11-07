(ns spring-lobby.client
  (:require
    aleph.netty
    [aleph.tcp :as tcp]
    [byte-streams]
    [chime.core :as chime]
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.string :as string]
    [gloss.core :as gloss]
    [gloss.io :as gio]
    java-time
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.client.message :as message]
    spring-lobby.client.handler.tei
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (io.netty.handler.ssl SslContextBuilder SslHandler)
    (io.netty.handler.ssl.util InsecureTrustManagerFactory)
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
   (let [[host port] (parse-host-port server-url)
         pipeline-atom (atom nil)
         raw-client (tcp/client (merge
                                  {:host host
                                   :port port
                                   :ssl? ssl}
                                  (when-not ssl ; starttls
                                    {:pipeline-transform
                                     (fn [pipeline]
                                       (log/info "Saving TCP pipeline for TLS upgrade")
                                       (reset! pipeline-atom pipeline))})))]
     {:client-deferred (d/chain raw-client
                         #(wrap-duplex-stream protocol %))
      :pipeline-atom pipeline-atom})))


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
        client-data (-> state :by-server (get server-key) :client-data)]
    (message/send-message state-atom client-data "LISTCOMPFLAGS")))

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
        client-data (:client-data server-data)
        my-channels (concat
                      (-> state :my-channels (get server-key))
                      (:global-chat-channels state))]
    (message/send-message state-atom client-data "CHANNELS")
    (message/send-message state-atom client-data "FRIENDLIST")
    (message/send-message state-atom client-data "FRIENDREQUESTLIST")
    (when (u/matchmaking? server-data)
      (message/send-message state-atom client-data "c.matchmaking.list_all_queues"))
    (doseq [channel my-channels]
      (let [[channel-name _] channel]
        (if (and channel-name
                 (not (u/battle-channel-name? channel-name))
                 (not (u/user-channel-name? channel-name)))
          (message/send-message state-atom client-data (str "JOIN " channel-name))
          (swap! state-atom update-in [:my-channels server-key] dissoc channel-name))))
    (ping-loop state-atom server-key client-data)))

(defmethod handler/handle "MOTD" [_state-atom _server-url m]
  (log/trace "motd" m))

(defmethod handler/handle "LOGININFOEND" [_state-atom _server-url _m]
  (log/trace "end of login info"))

(defn ping-loop [state-atom server-key client-data]
  (let [ping-loop-future (future
                           (try
                             (log/info "ping loop thread started")
                             (loop []
                               (async/<!! (async/timeout 30000))
                               (u/append-console-log state-atom server-key :client "PING")
                               (when (message/send-message state-atom client-data "PING")
                                 (when-not (Thread/interrupted)
                                   (recur))))
                             (log/info "ping loop ended")
                             (catch Exception e
                               (log/error e "Error in ping loop"))))]
    (swap! state-atom assoc-in [:by-server server-key :ping-loop] ping-loop-future)))


(defn print-loop
  [state-atom server-key client]
  (let [print-loop (future
                     (let [pd (tufte/new-pdata {:dynamic? true})
                           chimer
                           (chime/chime-at
                             (chime/periodic-seq
                               (java-time/plus (java-time/instant) (java-time/duration 1 :minutes))
                               (java-time/duration 1 :minutes))
                             (fn [_chimestamp]
                               (if-let [m @@pd]
                                 (log/info (str "Profiler stats for " server-key ":\n" (tufte/format-pstats m)))
                                 (log/warn "No profiler stats to print for" server-key)))
                             {:error-handler
                              (fn [e]
                                (log/error e "Error in profiler print")
                                true)})]
                       (try
                         (log/info "print loop thread started")
                         (loop []
                           (when-let [d (s/take! client)]
                             (when-let [m @d]
                               (log/info (str "[" server-key "]") "<" (str "'" m "'"))
                               (try
                                 (u/append-console-log state-atom server-key :server m)
                                 (let [t0 (System/nanoTime)
                                       k (-> m
                                             (string/split #"\s")
                                             first)]
                                   (tufte/with-profiling pd {:dynamic? true
                                                             :id :skylobby/client}
                                     (handler state-atom server-key m)
                                     (tufte/capture-time! pd k (- (System/nanoTime) t0))))
                                 (catch Exception e
                                   (log/error e "Error handling message" (str "'" m "'")))
                                 (catch Throwable t
                                   (log/error t "Critical error handling message" (str "'" m "'"))
                                   (throw t)))
                               (when-not (Thread/interrupted)
                                 (recur)))))
                         (log/info "print loop ended")
                         (catch Exception e
                           (log/error e "Error in print loop"))
                         (finally
                           (when chimer
                             (.close chimer))))))]
    (swap! state-atom assoc-in [:by-server server-key :print-loop] print-loop)))

(defn login
  [state-atom
   {:keys [username password] :as client-data}
   {:keys [client-id local-addr user-agent]
    :or {client-id 0
         local-addr "*"
         user-agent (u/agent-string)}}]
  (when (string/blank? password)
    (throw (ex-info "Password is blank" {:username username})))
  (let [pw-md5-base64 (u/base64-md5 password)
        prefix (str "LOGIN " username " ")
        suffix (str " 0 " local-addr " " user-agent "\t" client-id "\t" compflags)
        message (str prefix pw-md5-base64 suffix)
        log-message (str prefix "<password>" suffix)] ; remove password
    (message/send-message state-atom client-data message {:log-message log-message})))


(defmethod handler/handle "COMPFLAGS" [state-atom server-key m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        compflags (set (string/split remaining #"\s+"))
        state (swap! state-atom update-in [:by-server server-key]
                 (fn [state]
                   (-> state
                       (assoc :compflags compflags)
                       (assoc-in [:client-data :compflags] compflags))))
        accepted (-> state :by-server (get server-key) :accepted)
        {:keys [ssl username password] :as client-data} (-> state :by-server (get server-key) :client-data)]
    (if (and ssl (contains? compflags "token-auth"))
      (let [message (str "c.user.get_token_by_name " username "\t" password)
            log-message (str "c.user.get_token_by_name " username "\t<password>")]
        (message/send-message state-atom client-data message {:log-message log-message}))
      (when (not accepted)
        (let [client-id (u/client-id state-atom state)]
          (login state-atom client-data {:client-id client-id
                                         :user-agent (u/user-agent (:user-agent-override state))}))))))


(defn connect
  [state-atom {:keys [client-data]}]
  (let [{:keys [client pipeline-atom ssl server-key ssl-upgraded]} client-data]
    (if (or ssl ssl-upgraded)
      (print-loop state-atom server-key client)
      (do
        (log/info "Starting TLS for" server-key)
        (let [tasserver @(s/take! client)]
          (log/info (str "[" server-key "]") "<" (str "'" tasserver "'"))
          (u/append-console-log state-atom server-key :server tasserver)
          (message/send-message state-atom client-data "STLS")
          (let [stls-response @(s/take! client)]
            (log/info (str "[" server-key "]") "<" (str "'" stls-response "'"))
            (u/append-console-log state-atom server-key :server stls-response)
            (let [^io.netty.channel.ChannelPipeline pipeline @pipeline-atom]
              (if pipeline
                (let [; https://github.com/clj-commons/aleph/blob/master/src/aleph/netty.clj#L721-L724
                      ssl-context-builder (SslContextBuilder/forClient)
                      _ (.trustManager ssl-context-builder InsecureTrustManagerFactory/INSTANCE)
                      _ (.startTls ssl-context-builder true)
                      ssl-context (.build ssl-context-builder)
                      ch (.channel pipeline)
                      engine (.newEngine ssl-context (.alloc ch))
                      handler (SslHandler. engine false)
                      handshake-future (.handshakeFuture handler)]
                  (.addFirst pipeline "ssl" handler)
                  (log/info "Added SslHandler to TCP pipeline")
                  (log/info "Waiting for SSL handshake")
                  (let [handshake @handshake-future]
                    (log/info "SSL handshake finished" handshake))
                  (print-loop state-atom server-key client))
                (log/warn "No TCP pipeline to upgrade to TLS!")))
            (swap! state-atom assoc-in [:by-server server-key :client-data :ssl-upgraded] true)))))))

(defn disconnect [state-atom {:keys [server-key] :as client-data}]
  (when-let [^SplicedStream client (:client client-data)]
    (log/info "Disconnecting client" (:server-key client-data))
    (if-not (s/closed? client)
      (message/send-message state-atom client-data "EXIT")
      (log/warn "Client" server-key "was already closed"))
    (s/close! client)
    (log/info "Connection" server-key "closed?" (s/closed? client))))

(defmethod handler/handle "DENIED" [state-atom server-key m]
  (log/info (str "Login denied: '" m "'"))
  (let [[old-state] (swap-vals! state-atom
                      (fn [state]
                        (let [server-url (-> state :by-server (get server-key) :client-data :server-url)]
                          (-> state
                              (update-in [:by-server server-key] dissoc :accepted :client-data)
                              (assoc-in [:login-error server-url] m)))))
        client-data (-> old-state :by-server (get server-key) :client-data)]
    (disconnect state-atom client-data)))

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
                  client-data (-> old-state :by-server (get server-key) :client-data)]
              (disconnect state-atom client-data))
            nil))))))
