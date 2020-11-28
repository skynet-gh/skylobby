(ns spring-lobby.client.handler
  (:require
    [spring-lobby.client :as client]
    [spring-lobby.spring :as spring]
    [taoensso.timbre :as log]))


(defmethod client/handle "CLIENTSTATUS" [_c state m]
  (let [[_all username client-status] (re-find #"\w+ (\w+) (\w+)" m)
        decoded-status (client/decode-client-status client-status)
        state-data @state]
    (swap! state assoc-in [:users username :client-status] decoded-status)
    (when (and (:battle state)
               (= (:host-username (:battle state)) username)
               (:ingame decoded-status))
      (log/info "Starting game to join host")
      (spring/start-game state-data))))
