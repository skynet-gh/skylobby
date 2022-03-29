(ns skylobby.event-stub
  (:require
    [manifold.stream :as s]
    [skylobby.client :as client]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn- update-disconnected!
  [state-atom server-key]
  (log/info "Disconnecting from" (pr-str server-key))
  (let [[old-state _new-state] (swap-vals! state-atom
                                 (fn [state]
                                   (-> state
                                       (update :by-server dissoc server-key)
                                       (update :needs-focus dissoc server-key))))
        {:keys [client-data ping-loop print-loop]} (-> old-state :by-server (get server-key))]
    (if client-data
      (client/disconnect state-atom client-data)
      (log/warn (ex-info "stacktrace" {:server-key server-key}) "No client to disconnect!"))
    (if ping-loop
      (future-cancel ping-loop)
      (log/warn (ex-info "stacktrace" {:server-key server-key}) "No ping loop to cancel!"))
    (if print-loop
      (future-cancel print-loop)
      (log/warn (ex-info "stacktrace" {:server-key server-key}) "No print loop to cancel!")))
  nil)

(defn- do-connect
  [state-atom {:keys [client-data server] :as state}]
  (let [{:keys [client-deferred server-key]} client-data]
    (try
      (let [^SplicedStream client @client-deferred]
        (s/on-closed client
          (fn []
            (log/info "client closed")
            (update-disconnected! state-atom server-key)))
        (s/on-drained client
          (fn []
            (log/info "client drained")
            (update-disconnected! state-atom server-key)))
        (if (s/closed? client)
          (log/warn "client was closed on create")
          (let [[server-url _server-data] server
                client-data (assoc client-data :client client)]
            (log/info "Connecting to" server-key)
            (swap! state-atom
              (fn [state]
                (-> state
                    (update :login-error dissoc server-url)
                    (assoc-in [:by-server server-key :client-data :client] client))))
            (client/connect state-atom (assoc state :client-data client-data)))))
      (catch Exception e
        (log/error 
          "Connect error"
          (str e)) 
        #_
        (log/error 
          e 
          "Connect error")
        (swap! state-atom assoc-in [:by-server server-key :login-error] (str (.getMessage e)))
        (update-disconnected! state-atom server-key)))
    nil))

(defn connect [state-atom {:keys [no-focus server server-key password username] :as state}]
 (let [[server-url server-opts] server
        client-data (client/client server-url server-opts)
        client-data (assoc client-data
                           :server-key server-key
                           :server-url server-url
                           :ssl (:ssl (second server))
                           :password password
                           :username username)]
   (log/info client-data)
   (swap! state-atom
          (fn [state]
            (cond-> state
                    true
                    (update-in [:by-server server-key]
                      assoc :client-data client-data
                            :server server)
                    (not no-focus)
                    (assoc :selected-server-tab server-key))))
   (do-connect state-atom (assoc state :client-data client-data))))

(defn disconnect [state-atom server-key]
  (update-disconnected! state-atom server-key))

(defn leave-battle [state-atom {:keys [client-data server-key]}]
  nil)

(defn join-battle [state-atom {:keys [battle battle-password battle-passworded client-data selected-battle] :as opts}]
  nil)

(defn set-ignore
  ([state-atom server-key username ignore]
   (set-ignore state-atom server-key username ignore nil))
  ([state-atom server-key username ignore {:keys [channel-name]}]
   nil))

(defn send-message [state-atom {:keys [channel-name client-data message server-key] :as e}]
  nil)

(defn start-battle
  [state-atom {:keys [am-host am-spec battle-status channel-name client-data host-ingame] :as state}]
  nil)

(defn set-my-battle-status
  [state-atom client-data battle-status team-color]
  nil)

(defn set-battle-mode
  [state-atom {:keys [battle-status client-data mode ready-on-unspec team-color]}]
  nil)

(defn set-auto-unspec [state-atom {:keys [auto-unspec server-key] :as opts}]
  nil)

(defn set-battle-ready
  [state-atom {:keys [battle-status client-data ready team-color]}]
  nil)

(defn set-client-status [state-atom {:keys [client-data client-status]}]
  nil)
