(ns skylobby.direct.client
  (:require
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [skylobby.spring :as spring]
    [taoensso.encore :as encore :refer [have]]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* false)


; https://github.com/ptaoussanis/sente/blob/7a1fad84cad9839834648a3d89dce8c1807d2f70/src/taoensso/sente.cljc#L198
(defn cb-error? [cb-reply-clj]
  (#{:chsk/closed :chsk/timeout :chsk/error} cb-reply-clj))
(defn cb-success? [cb-reply-clj]
  (not (cb-error? cb-reply-clj)))

(defmulti -event-msg-handler (fn [_state-atom _server-key message] (:id message)))

(defn event-msg-handler
  [state-atom server-key]
  (fn [{:as ev-msg}]
    (log/trace ev-msg)
    (-event-msg-handler state-atom server-key ev-msg)))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [_state-atom _server-key {:keys [event]}]
  (log/warnf "Unhandled event: %s" event))

(defmethod -event-msg-handler
  :chsk/ws-ping
  [_state-atom _server-key {:keys [event]}]
  (log/debugf "WebSocket ping: %s" event))

(defn disconnect [state-atom server-key reason]
  (let [[old-state _new-state] (swap-vals! state-atom
                                 (fn [state]
                                   (-> state
                                       (update :by-server dissoc server-key)
                                       (assoc-in [:login-error :direct-client] reason)
                                       (assoc :selected-server-tab "direct"))))
        {:keys [client-close-fn]} (get-in old-state [:by-server server-key])]
    (when (fn? client-close-fn)
      (log/info "Closing client for" server-key)
      (client-close-fn))))

(defmethod -event-msg-handler :chsk/state
  [state-atom server-key {:keys [?data send-fn]}]
  (let [[_old-state-map new-state-map] (have vector? ?data)]
    (if (:first-open? new-state-map)
      (let [username (get-in @state-atom [:by-server server-key :username])]
        (log/info "Channel socket successfully established!: %s" new-state-map)
        (send-fn
          [::join username]
          5000
          (fn [reply]
            (when (cb-success? reply)
              (log/info "Server reply to join" reply)
              (disconnect state-atom server-key (:reason reply))))))
      (do
        (log/infof "Channel socket state change: %s" new-state-map)
        (when-not (:open? new-state-map)
          (log/info "Disconnecting")
          (let [last-ex (get-in new-state-map [:last-ws-error :ex])
                reason (if last-ex
                         (.getMessage last-ex)
                         "disconnected")]
            (disconnect state-atom server-key reason)))))))

(defmethod -event-msg-handler :chsk/recv
  [_state-atom _server-key {:keys [?data]}]
  (log/info "Push event from server: %s" ?data))

(defmethod -event-msg-handler :chsk/handshake
  [_state-atom _server-key {:keys [?data]}]
  (let [[_?uid _?csrf-token _?handshake-data] ?data]
    (log/info "Handshake: %s" ?data)))


(defmethod -event-msg-handler :skylobby.direct/close
  [state-atom server-key {:keys [?data]}]
  (log/info "Server closed, disconnecting")
  (swap! state-atom
    (fn [state]
      (-> state
          (assoc-in [:login-error :direct-client] (:reason ?data))
          (assoc :selected-server-tab "direct"))))
  (disconnect state-atom server-key "server closed"))


(defmethod -event-msg-handler :skylobby.direct/set-engine
  [state-atom server-key {:keys [?data send-fn]}]
  (log/info "Setting engine to" ?data)
  (swap! state-atom assoc-in [:by-server server-key :battles :direct :battle-version] ?data)
  (send-fn [:skylobby.direct.client/player-state {:battle-status {:sync 0}}]))


(defmethod -event-msg-handler :skylobby.direct/set-mod
  [state-atom server-key {:keys [?data send-fn]}]
  (log/info "Setting mod to" ?data)
  (swap! state-atom assoc-in [:by-server server-key :battles :direct :battle-modname] ?data)
  (send-fn [:skylobby.direct.client/player-state {:battle-status {:sync 0}}]))

(defmethod -event-msg-handler :skylobby.direct/set-map
  [state-atom server-key {:keys [?data send-fn]}]
  (log/info "Setting map to" ?data)
  (swap! state-atom assoc-in [:by-server server-key :battles :direct :battle-map] ?data)
  (send-fn [:skylobby.direct.client/player-state {:battle-status {:sync 0}}]))

(defmethod -event-msg-handler :skylobby.direct/battle-details
  [state-atom server-key {:keys [?data send-fn]}]
  (log/info "Updating battle details with" ?data)
  (swap! state-atom update-in [:by-server server-key :battles :direct] merge ?data)
  (send-fn [:skylobby.direct.client/player-state {:battle-status {:sync 0}}]))

(defmethod -event-msg-handler :skylobby.direct/chat
  [state-atom server-key {:keys [?data]}]
  (log/info "Adding chat" ?data)
  (let [{:keys [channel-name]} ?data
        messages-path [:by-server server-key :channels channel-name :messages]]
    (swap! state-atom update-in messages-path conj ?data)))

(defmethod -event-msg-handler :skylobby.direct/battle-users
  [state-atom server-key {:keys [?data]}]
  (log/info "Battle users update" ?data)
  (swap! state-atom assoc-in [:by-server server-key :battle :users] ?data))

(defmethod -event-msg-handler :skylobby.direct/battle-bots
  [state-atom server-key {:keys [?data]}]
  (log/info "Battle bots update" ?data)
  (swap! state-atom assoc-in [:by-server server-key :battle :bots] ?data))

(defmethod -event-msg-handler :skylobby.direct/battle-scripttags
  [state-atom server-key {:keys [?data]}]
  (log/info "Battle scripttags update" ?data)
  (swap! state-atom assoc-in [:by-server server-key :battle :scripttags] ?data))

(defmethod -event-msg-handler :skylobby.direct/users
  [state-atom server-key {:keys [?data]}]
  (log/info "Users update" ?data)
  (let [[old-state new-state] (swap-vals! state-atom assoc-in [:by-server server-key :users] ?data)
        {:keys [battle battles users]} (get-in new-state [:by-server server-key])
        {:keys [battle-id]} battle
        {:keys [host-username]} (get battles battle-id)
        host-ingame (get-in users [host-username :client-status :ingame])
        host-now-ingame (and (not (get-in old-state [:by-server server-key :users host-username :client-status :ingame]))
                             host-ingame)]
    (when host-now-ingame
      (let [
            {:keys [by-server by-spring-root engine-overrides spring-isolation-dir]} new-state
            {:keys [battle battles username] :as server-data} (get by-server server-key)
            my-battle-status (get-in battle [:users username :battle-status])]
        (spring/start-game
          state-atom
          (merge server-data
            {:am-host false ; TODO
             :am-spec true ; TODO
             :battle (assoc battle :battle-ip (:hostname server-key))
             :battles battles
             :battle-status my-battle-status
             :channel-name (u/battle-channel-name battle)
             :engine-overrides engine-overrides
             :host-ingame true
             :server-key server-key
             :spring-isolation-dir spring-isolation-dir}
            (dissoc
              (resource/spring-root-resources spring-isolation-dir by-spring-root)
              :engine-version :map-name :mod-name)))))))
