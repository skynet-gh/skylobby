(ns skylobby.event-stub
  (:require
    [clojure.core.async :as async]
    [clojure.string :as string]
    [manifold.stream :as s]
    [skylobby.client :as client]
    [skylobby.client.gloss :as gloss]
    [skylobby.client.message :as message]
    [skylobby.util :as u]
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
  (swap! state-atom assoc-in [:last-battle server-key :should-rejoin] false)
  (message/send state-atom client-data "LEAVEBATTLE")
  (swap! state-atom update-in [:by-server server-key]
    (fn [server-data]
      (let [battle (:battle server-data)]
        (-> server-data
            (assoc-in [:old-battles (:battle-id battle)] battle)
            (dissoc :auto-unspec :battle))))))

(defn join-battle [state-atom {:keys [battle battle-password battle-passworded client-data selected-battle] :as opts}]
  (when battle
    (leave-battle state-atom opts)
    (async/<!! (async/timeout 500)))
  (if selected-battle
    (let [server-key (u/server-key client-data)]
      (swap! state-atom
        (fn [state]
          (-> state
              (assoc-in [:by-server server-key :battle] {})
              (update-in [:by-server server-key] dissoc :selected-battle)
              (assoc-in [:selected-tab-main server-key] "battle"))))
      (message/send state-atom client-data
        (str "JOINBATTLE " selected-battle
             (if battle-passworded
               (str " " battle-password)
               (str " *"))
             " " (crypto.random/hex 6))))
    (log/warn "No battle to join" opts)))

(defn set-ignore
  ([state-atom server-key username ignore]
   (set-ignore state-atom server-key username ignore nil))
  ([state-atom server-key username ignore {:keys [channel-name]}]
   (swap! state-atom
     (fn [state]
       (let [channel-name (or channel-name
                              (u/visible-channel state server-key))]
         (-> state
             (assoc-in [:ignore-users server-key username] ignore)
             (update-in [:by-server server-key :channels channel-name :messages] conj {:text (str (if ignore "Ignored " "Unignored ") username)
                                                                                       :timestamp (u/curr-millis)
                                                                                       :message-type :info})))))))

(defn send-chat [state-atom {:keys [channel-name client-data message server-key] :as e}]
  (swap! state-atom update-in [:by-server server-key]
    (fn [server-data]
      (-> server-data
          (update :message-drafts dissoc channel-name)
          (update-in [:channels channel-name :sent-messages] conj message)
          (assoc-in [:channels channel-name :history-index] u/default-history-index))))
  (cond
    (string/blank? channel-name)
    (log/info "Skipping message" (pr-str message) "to empty channel" (pr-str channel-name))
    (string/blank? message)
    (log/info "Skipping empty message" (pr-str message) "to" (pr-str channel-name))
    :else
    (cond
      (re-find #"^/ingame" message) (message/send state-atom client-data "GETUSERINFO")
      (re-find #"^/ignore" message)
      (let [[_all username] (re-find #"^/ignore\s+([^\s]+)\s*" message)]
        (set-ignore state-atom server-key username true {:channel-name channel-name}))
      (re-find #"^/unignore" message)
      (let [[_all username] (re-find #"^/unignore\s+([^\s]+)\s*" message)]
        (set-ignore state-atom server-key username false {:channel-name channel-name}))
      (or (re-find #"^/msg" message) (re-find #"^/message" message))
      (let [[_all user message] (re-find #"^/msg\s+([^\s]+)\s+(.+)" message)]
        (send-chat state-atom
          (merge e
            {:channel-name (str "@" user)
             :message message})))
      (re-find #"^/rename" message)
      (let [[_all new-username] (re-find #"^/rename\s+([^\s]+)" message)]
       (swap! state-atom update-in [:by-server server-key :channels channel-name :messages] conj {:text (str "Renaming to" new-username)
                                                                                                  :timestamp (u/curr-millis)
                                                                                                  :message-type :info}
        (message/send state-atom client-data (str "RENAMEACCOUNT " new-username))))
      :else
      (let [[private-message username] (re-find #"^@(.*)$" channel-name)
            unified (-> client-data :compflags (contains? "u"))]
        (if-let [[_all message] (re-find #"^/me (.*)$" message)]
          (if private-message
            (message/send state-atom client-data (str "SAYPRIVATEEX " username " " message))
            (if (and (not unified) (u/battle-channel-name? channel-name))
              (message/send state-atom client-data (str "SAYBATTLEEX " message))
              (message/send state-atom client-data (str "SAYEX " channel-name " " message))))
          (if private-message
            (message/send state-atom client-data (str "SAYPRIVATE " username " " message))
            (if (and (not unified) (u/battle-channel-name? channel-name))
              (message/send state-atom client-data (str "SAYBATTLE " message))
              (message/send state-atom client-data (str "SAY " channel-name " " message)))))))))

(defn start-battle
  [state-atom {:keys [am-host am-spec battle-status channel-name client-data host-ingame] :as state}]
  (when-not (:mode battle-status)
    (if (and (:server-url client-data)
             (or (string/starts-with? (:server-url client-data) "bar.teifion.co.uk")
                 (string/starts-with? (:server-url client-data) "road-flag.bnr.la")))
      (send-chat state-atom
        {:channel-name channel-name
         :client-data client-data
         :message (str "!joinas spec")})
      (log/info "Skipping !joinas spec for this server"))
    (async/<!! (async/timeout 1000))))

(defn set-my-battle-status
  [state-atom client-data battle-status team-color]
  (message/send state-atom client-data
    (str "MYBATTLESTATUS"
         " "
         (gloss/encode-battle-status battle-status)
         " "
         (or team-color "0"))))

(defn set-battle-mode
  [state-atom {:keys [battle-status client-data mode ready-on-unspec team-color]}]
  (let [
        battle-status (assoc battle-status
                             :mode mode
                             :ready (boolean ready-on-unspec))]
    (swap! state-atom assoc-in [:by-server (u/server-key client-data) :battle :desired-ready] (boolean ready-on-unspec))
    (set-my-battle-status state-atom client-data battle-status team-color)))

(defn set-auto-unspec [state-atom {:keys [auto-unspec server-key] :as opts}]
  (log/info "Setting auto-unspec to" auto-unspec)
  (swap! state-atom assoc-in [:by-server server-key :auto-unspec] auto-unspec)
  (when auto-unspec
    (set-battle-mode state-atom (assoc opts :mode auto-unspec))))

(defn set-battle-ready
  [state-atom {:keys [battle-status client-data ready team-color]}]
  (swap! state-atom assoc-in [:by-server (u/server-key client-data) :battle :desired-ready] (boolean ready))
  (set-my-battle-status state-atom client-data (assoc battle-status :ready ready) team-color))

(defn set-client-status [state-atom {:keys [client-data client-status]}]
  (message/send state-atom client-data (str "MYSTATUS " (gloss/encode-client-status client-status))))
