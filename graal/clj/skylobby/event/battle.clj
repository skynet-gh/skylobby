(ns skylobby.event.battle
  (:require
    [skylobby.fs :as fs]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn update-player-or-bot-state
  [state-atom server-key {:keys [bot-name username] :as id} state-change]
  (let [is-bot (boolean bot-name)
        battle-kw (if is-bot :bots :users)
        message-type (if is-bot :skylobby.direct/battle-bots :skylobby.direct/battle-users)]
    (log/info "Updating" battle-kw "battle state for" id "with" state-change)
    (case (u/server-type server-key)
      :direct-host
      (let [state (swap! state-atom update-in [:by-server server-key :battle battle-kw (or bot-name username)] u/deep-merge state-change)]
        (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
          (let [users-or-bots (get-in state [:by-server server-key :battle battle-kw])]
            (broadcast-fn [message-type users-or-bots]))
          (log/warn "No broadcast-fn" server-key)))
      :direct-client
      (if-let [send-fn (get-in @state-atom [:by-server server-key :client :send-fn])]
        (send-fn [(if is-bot
                    :skylobby.direct.client/bot-state
                    :skylobby.direct.client/player-state)
                  (assoc state-change :bot-name bot-name)])
        (log/warn "No send-fn" server-key)))))

(defn engine-changed
  [state-atom
   {:fx/keys [event] :keys [battle-id engine-version server-key spring-root]}]
  (let [engine-version (or engine-version event)
        {:keys [by-server]}
        (swap! state-atom
          (fn [state]
            (let [spring-root (or spring-root (:spring-isolation-dir state))]
              (-> state
                  (assoc :engine-filter "")
                  (assoc-in [:by-spring-root (fs/canonical-path spring-root) :engine-version] engine-version)
                  (assoc-in [:by-server server-key :battles battle-id :battle-version] engine-version)))))
        server (get-in by-server [server-key :server])]
    (when (= :direct-host (u/server-type server-key))
      (if-let [broadcast-fn (:broadcast-fn server)]
        (broadcast-fn [:skylobby.direct/battle-details {:battle-version engine-version}])
        (log/warn "No broadcast-fn found for server" server)))))

(defn map-changed
  [state-atom
   {:fx/keys [event] :keys [battle-id map-name server-key spring-root]}]
  (let [map-name (or map-name event)
        {:keys [by-server]}
        (swap! state-atom
          (fn [state]
            (let [spring-root (or spring-root (:spring-isolation-dir state))]
              (-> state
                  (assoc :map-input-prefix "")
                  (assoc-in [:by-spring-root (fs/canonical-path spring-root) :map-name] map-name)
                  (assoc-in [:by-server server-key :battles battle-id :battle-map] map-name)))))
        server (get-in by-server [server-key :server])]
    (when (= :direct-host (u/server-type server-key))
      (if-let [broadcast-fn (get-in by-server [server-key :server :broadcast-fn])]
        (broadcast-fn [:skylobby.direct/battle-details {:battle-map map-name}])
        (log/warn "No broadcast-fn found for server" server)))))

(defn mod-changed
  [state-atom
   {:fx/keys [event] :keys [battle-id mod-name server-key spring-root]}]
  (let [mod-name (or mod-name event)
        {:keys [by-server]}
        (swap! state-atom
          (fn [state]
            (let [spring-root (or spring-root (:spring-isolation-dir state))]
              (-> state
                  (assoc :mod-filter "")
                  (assoc-in [:by-spring-root (fs/canonical-path spring-root) :mod-name] mod-name)
                  (assoc-in [:by-server server-key :battles battle-id :battle-modname] mod-name)))))
        server (get-in by-server [server-key :server])]
    (when (= :direct-host (u/server-type server-key))
      (if-let [broadcast-fn (:broadcast-fn server)]
        (broadcast-fn [:skylobby.direct/battle-details {:battle-modname mod-name}])
        (log/warn "No broadcast-fn found for server" server)))))
