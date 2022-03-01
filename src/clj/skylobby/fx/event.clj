(ns skylobby.fx.event
  (:require
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


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
