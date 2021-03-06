(ns skylobby.fx.event.battle
  (:require
    clojure.set
    [skylobby.client.message :as message]
    [skylobby.event.battle :as event.battle]
    [skylobby.fx.color :as fx.color]
    [skylobby.spring :as spring]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (javafx.scene.control ColorPicker)))


(set! *warn-on-reflection* false)


(defn update-bot-state
  [state-atom server-key {:keys [bot-name] :as id} bot-state]
  (log/info "Updating bot battle state for" id "with" bot-state)
  (case (u/server-type server-key)
    :direct-host
    (let [state (swap! state-atom update-in [:by-server server-key :battle :bots bot-name] u/deep-merge bot-state)]
      (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
        (let [bots (get-in state [:by-server server-key :battle :bots])]
          (broadcast-fn [:skylobby.direct/battle-bots bots]))
        (log/warn "No broadcast-fn" server-key)))
    :direct-client
    (if-let [send-fn (get-in @state-atom [:by-server server-key :client :send-fn])]
      (send-fn [:skylobby.direct.client/bot-state bot-state])
      (log/warn "No send-fn" server-key))))

(defn update-user-state
  [state-atom server-key {:keys [username] :as id} user-state]
  ; TODO bots
  (log/info "Updating user state for" id "with" user-state)
  (case (u/server-type server-key)
    :direct-host
    (let [state (swap! state-atom update-in [:by-server server-key :users username] u/deep-merge user-state)]
      (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
        (let [users (get-in state [:by-server server-key :users])]
          (broadcast-fn [:skylobby.direct/users users]))
        (log/warn "No broadcast-fn" server-key)))
    :direct-client
    (if-let [send-fn (get-in @state-atom [:by-server server-key :client :send-fn])]
      (send-fn [:skylobby.direct.client/user-state user-state])
      (log/warn "No send-fn" server-key))))


(defn add-methods
  [multifn state-atom] ; TODO need to move event handler out of spring-lobby ns
  (defmethod multifn ::engine-changed
    [event-data]
    (event.battle/engine-changed state-atom event-data))
  (defmethod multifn ::mod-changed
    [event-data]
    (event.battle/mod-changed state-atom event-data))
  (defmethod multifn ::map-changed
    [event-data]
    (event.battle/map-changed state-atom event-data))
  (defmethod multifn ::kick
    [{:keys [bot-name client-data server-key username]}]
    (let [server-type (u/server-type server-key)]
      (future
        (try
          (case server-type
            :direct-client
            (if bot-name
              (if-let [send-fn (get-in @state-atom [:by-server server-key :client :send-fn])]
                (send-fn [:skylobby.direct.client/remove-bot {:bot-name bot-name}])
                (log/warn "No send-fn" server-key))
              (log/warn "No method to kick user" username "from" server-key))
            :direct-host
            (let [[_old-state {:keys [by-server]}] (swap-vals! state-atom update-in [:by-server server-key]
                                                     (fn [server-data]
                                                       (if bot-name
                                                         (update-in server-data [:battle :bots] dissoc bot-name)
                                                         (update-in server-data [:battle :users] dissoc username))))
                  {:keys [battle client-username server]} (get by-server server-key)
                  {:keys [broadcast-fn send-fn]} server]
              (if-let [client-id (->> client-username (filter (comp #{username} second)) first first)]
                (send-fn client-id [::close {:reason "kicked by host"}])
                (log/warn "No client-id found for user" username))
              (broadcast-fn [:skylobby.direct/battle-users (:users battle)])
              (broadcast-fn [:skylobby.direct/battle-bots (:bots battle)]))
            :singleplayer
            (do
              (log/info "Special server battle kick")
              (swap! state-atom
                     (fn [state]
                       (-> state
                           (update-in [:by-server server-key :battles :singleplayer :bots] dissoc bot-name)
                           (update-in [:by-server server-key :battle :bots] dissoc bot-name)
                           (update-in [:by-server server-key :battles :singleplayer :users] dissoc username)
                           (update-in [:by-server server-key :battle :users] dissoc username)))))
            :spring-lobby
            (if bot-name
              (message/send state-atom client-data (str "REMOVEBOT " bot-name))
              (message/send state-atom client-data (str "KICKFROMBATTLE " username))))
          (catch Exception e
            (log/error e "Error kicking from battle"))))))
  (defmethod multifn ::ally-changed
    [{:keys [id server-key] :fx/keys [event]}]
    (future
      (try
        (when-let [ally (try (Integer/parseInt event) (catch Exception _e))]
          (let [old-ally (-> id :battle-status :ally u/to-number)]
            (if (not= ally old-ally)
              (event.battle/update-player-or-bot-state state-atom server-key id {:battle-status {:ally ally}})
              (log/debug "No change for ally"))))
        (catch Exception e
          (log/error e "Error updating battle ally")))))
  (defmethod multifn ::team-changed
    [{:keys [id server-key] :fx/keys [event]}]
    (future
      (try
        (when-let [player-id (try (Integer/parseInt event) (catch Exception _e))]
          (let [old-id (-> id :battle-status :id u/to-number)]
            (if (not= player-id old-id)
              (event.battle/update-player-or-bot-state state-atom server-key id {:battle-status {:id player-id}})
              (log/debug "No change for team"))))
        (catch Exception e
          (log/error e "Error updating battle team")))))
  (defmethod multifn ::side-changed
    [{:keys [id indexed-mod server-key sides] :fx/keys [event]}]
    (future
      (try
        (let [side (get (clojure.set/map-invert sides) event)]
          (swap! state-atom assoc-in [:preferred-factions (:mod-name-only indexed-mod)] side)
          (if (not= side (-> id :battle-status :side))
            (let [old-side (-> id :battle-status :side)]
              (log/info "Updating side for" id "from" old-side "(" (get sides old-side) ") to" side "(" event ")")
              (event.battle/update-player-or-bot-state state-atom server-key id {:battle-status {:side side}}))
            (log/debug "No change for side")))
        (catch Exception e
          (log/error e "Error updating battle side")))))
  (defmethod multifn ::spectate-changed
    [{:keys [id is-me ready-on-unspec server-key] :fx/keys [event] :as data}]
    (let [mode (if (contains? data :value)
                 (:value data)
                 (not event))
          desired-ready (boolean ready-on-unspec)
          battle-status (if (and mode is-me)
                          {:mode mode
                           :ready desired-ready}
                          {:mode mode})]
      (event.battle/update-player-or-bot-state state-atom server-key id {:battle-status battle-status})))
  (defmethod multifn ::on-change-spectate
    [{:fx/keys [event] :as e}]
    (multifn (assoc e
                    :event/type ::spectate-changed
                    :value (= "Playing" event))))
  (defmethod multifn ::color-changed
    [{:keys [id server-key] :fx/keys [event]}]
    (let [^ColorPicker source (.getSource event)
          javafx-color (.getValue source)
          color-int (fx.color/javafx-color-to-spring javafx-color)]
      (event.battle/update-player-or-bot-state state-atom server-key id {:team-color color-int})))
  (defmethod multifn ::handicap-changed
    [{:keys [id server-key] :fx/keys [event]}]
    (when-let [handicap (max 0
                          (min 100
                            event))]
      (if (not= handicap (-> id :battle-status :handicap))
        (do
          (log/info "Updating handicap for" id "from" (-> id :battle-status :ally) "to" handicap)
          (event.battle/update-player-or-bot-state state-atom server-key id {:battle-status {:handicap handicap}}))
        (log/debug "No change for handicap"))))
  (defmethod multifn ::ready-changed
    [{:keys [server-key username] :fx/keys [event]}]
    (let [ready (boolean event)]
      (event.battle/update-player-or-bot-state state-atom server-key {:username username} {:battle-status {:ready ready}})))
  (defmethod multifn ::away-changed
    [{:keys [server-key username] :fx/keys [event]}]
    (let [away (= "Away" event)]
      (update-user-state state-atom server-key {:username username} {:client-status {:away away}})))
  (defmethod multifn ::startpostype-changed
    [{:keys [server-key] :fx/keys [event]}]
    (let [startpostype (get spring/startpostypes-by-name event)
          state (swap! state-atom assoc-in [:by-server server-key :battle :scripttags "game" "startpostype"] startpostype)]
      (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
        (let [scripttags (get-in state [:by-server server-key :battle :scripttags])]
          (broadcast-fn [:skylobby.direct/battle-scripttags scripttags]))
        (log/warn "No broadcast-fn" server-key))))
  (defmethod multifn ::add-bot
    [{:keys [bot-data server-key]}]
    (let [server-type (u/server-type server-key)
          {:keys [bot-name]} bot-data
          state (if (#{:singleplayer :direct-host} server-type)
                  (swap! state-atom assoc-in [:by-server server-key :battle :bots bot-name] bot-data)
                  @state-atom)]
      (case (u/server-type server-key)
        :direct-host
        (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
          (broadcast-fn [:skylobby.direct/battle-bots (get-in state [:by-server server-key :battle :bots])])
          (log/warn "No broadcast-fn" server-key))
        :direct-client
        (if-let [send-fn (get-in state [:by-server server-key :client :send-fn])]
          (send-fn [:skylobby.direct.client/add-bot bot-data])
          (log/warn "No send-fn" server-key))
        ; else
        nil)))
  (defmethod multifn ::modoption-change
    [{:keys [am-host client-data modoption-key modoption-type option-key server-key] :fx/keys [event] :as e}]
    (let [value (u/modoption-value modoption-type event)
          option-key (or option-key "modoptions")
          modoption-key-str (name modoption-key)
          server-type (u/server-type server-key)]
      (if (#{:singleplayer :direct-host} server-type)
        (let [state (swap! state-atom assoc-in [:by-server server-key :battle :scripttags "game" option-key modoption-key-str] (str event))]
          (when (#{:direct-host} server-type)
            (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
              (let [scripttags (get-in state [:by-server server-key :battle :scripttags])]
                (broadcast-fn [:skylobby.direct/battle-scripttags scripttags]))
              (log/warn "No broadcast-fn" server-key))))
        (if am-host
          (message/send state-atom client-data (str "SETSCRIPTTAGS game/" option-key "/" modoption-key-str "=" value))
          (multifn
            (assoc e
                   :event/type :skylobby.fx.event.chat/send
                   :no-clear-draft true
                   :message (str "!bSet " modoption-key-str " " value)))))))
  (defmethod multifn ::split-boxes
    [{:keys [am-host split-percent split-type server-key] :as e}]
    (swap! state-atom assoc :last-split-type split-type)
    (let [
          server-type (u/server-type server-key)]
      (if (#{:singleplayer :direct-host} server-type)
        (event.battle/split-boxes state-atom e)
        (if am-host
          (throw (ex-info "TODO implement split for host" {:server-key server-key}))
          (multifn
            (assoc e
                   :event/type :skylobby.fx.event.chat/send
                   :no-clear-draft true
                   :message (str "!split " split-type " " split-percent))))))))
