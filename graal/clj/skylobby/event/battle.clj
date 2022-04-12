(ns skylobby.event.battle
  (:require
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
