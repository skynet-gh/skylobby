(ns skylobby.client
  (:require
    [chime.core :as chime]
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.string :as string]
    java-time
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [skylobby.client.gloss :as gloss]
    [skylobby.client.handler :as handler]
    [skylobby.client.message :as message]
    [skylobby.client.stls :as stls]
    [skylobby.client.tcp :as tcp]
    skylobby.client.tei
    [skylobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (java.time Duration)
    (manifold.stream SplicedStream)))


(set! *warn-on-reflection* true)


(def ^:dynamic handler handler/handle) ; for overriding in dev


; https://github.com/spring/uberserver/blob/e63fee427136e5bafc1b20c8c984a5c348bc6624/protocol/Protocol.py#L190
(def compflags "sp b t u cl lu")
  ; ^ found at springfightclub, was "sp u"


(def default-client-status "0")

; https://aleph.io/examples/literate.html#aleph.examples.tcp


(defn parse-host-port [server-url]
  (if-let [[_all host port] (re-find #"(.+):(\d+)$" server-url)]
    [host (edn/read-string port)]
    [server-url u/default-server-port]))

(defn client
  ([server-url]
   (client server-url nil))
  ([server-url {:keys [encoding ssl]}]
   (let [[host port] (parse-host-port server-url)
         pipeline-atom (atom nil)
         raw-client
         (tcp/client
           (merge
             {:host host
              :port port
              :ssl? ssl}
             (when-not ssl ; starttls
               {:pipeline-transform
                (fn [pipeline]
                  (log/info "Saving TCP pipeline for TLS upgrade")
                  (reset! pipeline-atom pipeline))})))
         protocol (gloss/protocol encoding)
         duplex-stream-fn (partial gloss/wrap-duplex-stream protocol)
         client-deferred (d/chain raw-client duplex-stream-fn)]
     {:client-deferred client-deferred
      :pipeline-atom pipeline-atom})))

(defmethod handler/handle :default [state-atom server-url m]
  (log/warn "No handler for message" (str "'" m "'"))
  (swap! state-atom assoc-in [:by-server server-url :last-failed-message] m))


(defn print-loop
  [state-atom server-key client]
  (let [print-loop (future
                     (try
                       (let [pd (tufte/new-pdata {:dynamic? true})
                             chimer
                             (chime/chime-at
                               (chime/periodic-seq
                                 (java-time/plus (java-time/instant) (Duration/ofMillis 60000))
                                 (Duration/ofMillis 60000))
                               (fn [_chimestamp]
                                 (if-let [m @@pd]
                                   (log/info (str "Profiler stats for " server-key ":\n" (tufte/format-pstats m)))
                                   (log/warn "No profiler stats to print for" server-key)))
                               {:error-handler
                                (fn [e]
                                  (log/error e "Error in profiler print")
                                  true)})]
                         (try
                           (log/info "Print loop thread started")
                           (loop []
                             (when-let [d (try
                                            (s/take! client)
                                            (catch java.nio.charset.MalformedInputException e
                                              (swap! state-atom update-in [:login-error server-key] str "\nCharacter encoding error")
                                              (log/error e "Encoding error")))]
                               (when-let [m @d]
                                 (log/info (str "[" server-key "]") "<" (str "'" m "'"))
                                 (try
                                   (let [t0 (System/nanoTime)
                                         k (-> m
                                               (string/split #"\s")
                                               first)]
                                     (swap! state-atom (u/append-console-log-fn server-key :server m))
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
                           (log/info "Print loop ended")
                           (catch Exception e
                             (log/error e "Error in print loop"))
                           (catch Throwable t
                             (log/error t "Serious error in print loop"))
                           (finally
                             (when chimer
                               (.close chimer)))))
                       (catch Throwable t
                         (log/error t "Serious error in print loop init"))))]
    (swap! state-atom assoc-in [:by-server server-key :print-loop] print-loop)))


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
          (message/send state-atom client-data "STLS")
          (let [stls-response @(s/take! client)]
            (log/info (str "[" server-key "]") "<" (str "'" stls-response "'"))
            (u/append-console-log state-atom server-key :server stls-response)
            (let [pipeline @pipeline-atom]
              (if (stls/upgrade-pipeline pipeline)
                (print-loop state-atom server-key client)
                (log/error "STLS upgrade did not return success")))
            (swap! state-atom assoc-in [:by-server server-key :client-data :ssl-upgraded] true)))))))

(defn disconnect [state-atom {:keys [server-key] :as client-data}]
  (when-let [^SplicedStream client (:client client-data)]
    (log/info "Disconnecting client" (:server-key client-data))
    (if-not (s/closed? client)
      (message/send state-atom client-data "EXIT")
      (log/warn "Client" server-key "was already closed"))
    (s/close! client)
    (log/info "Connection" server-key "closed?" (s/closed? client))))


(defn ping-loop [state-atom server-key client-data]
  (let [ping-loop-future (future
                           (try
                             (log/info "Ping loop thread started")
                             (loop []
                               (async/<!! (async/timeout 30000))
                               (when (message/send state-atom client-data "PING")
                                 (when-not (Thread/interrupted)
                                   (recur))))
                             (log/info "Ping loop ended")
                             (catch Exception e
                               (log/error e "Error in ping loop"))
                             (catch Throwable t
                               (log/error t "Serious error in ping loop"))))]
    (swap! state-atom assoc-in [:by-server server-key :ping-loop] ping-loop-future)))


(defn handle-tasserver [state-atom server-key m]
  (let [state (swap! state-atom assoc-in [:by-server server-key :tas-server] m)
        client-data (-> state :by-server (get server-key) :client-data)]
    (message/send state-atom client-data "LISTCOMPFLAGS")))

(defmethod handler/handle "TASSERVER" [state-atom server-key m]
  (handle-tasserver state-atom server-key m))

(defmethod handler/handle "TASServer" [state-atom server-key m]
  (handle-tasserver state-atom server-key m))

(defmethod handler/handle "QUEUED" [state-atom server-key _m]
  (log/info "Login queued")
  (let [client-data (get-in @state-atom [:by-server server-key :client-data])]
    (async/thread
      (async/<!! (async/timeout 5000))
      (message/send state-atom client-data "c.auth.login_queue_heartbeat"))))

(defmethod handler/handle "ACCEPTED" [state-atom server-key m]
  (let [[_all username] (re-find #"\w+ (.*)" m)
        state (swap! state-atom
                (fn [state]
                  (-> state
                      (update-in [:by-server server-key]
                           assoc
                           :username username
                           :accepted true)
                      (update :login-error dissoc server-key)
                      (update :normal-logout dissoc server-key))))
        server-data (-> state :by-server (get server-key))
        client-data (:client-data server-data)]
    (ping-loop state-atom server-key client-data)))


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
        prefix (str "LOGIN " (string/replace username #"\s" "") " ")
        suffix (str " 0 " local-addr " " user-agent "\t" client-id "\t" compflags)
        message (str prefix pw-md5-base64 suffix)
        log-message (str prefix "<password>" suffix)] ; remove password
    (message/send state-atom client-data message {:log-message log-message})))

(defmethod handler/handle "s.auth.front_of_queue" [state-atom server-key _m]
  (let [client-data (get-in state-atom [:by-server server-key :client-data])
        client-id (u/client-id state-atom @state-atom)]
    (login state-atom client-data {:client-id client-id
                                   :user-agent (u/user-agent (:user-agent-override state-atom))})))

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
        (message/send state-atom client-data message {:log-message log-message}))
      (when (not accepted)
        (let [client-id (u/client-id state-atom state)]
          (login state-atom client-data {:client-id client-id
                                         :user-agent (u/user-agent (:user-agent-override state))}))))))

(defmethod handler/handle "DENIED" [state-atom server-key m]
  (log/info (str "Login denied: '" m "'"))
  (let [[old-state] (swap-vals! state-atom
                      (fn [state]
                        (-> state
                            (update-in [:by-server server-key] dissoc :accepted :client-data)
                            (update-in [:login-error server-key] str "\nDENIED: " m))))
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
                                    (-> state
                                        (update-in [:by-server server-key] dissoc :accepted :client-data)
                                        (update-in [:login-error server-key] str "\nDENIED: " msg))))
                  client-data (-> old-state :by-server (get server-key) :client-data)]
              (disconnect state-atom client-data))
            nil))))))
