(ns skylobby.event
  (:require
    [clojure.core.async :as async]
    crypto.random
    [spring-lobby.client.message :as message]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))



(defn leave-battle [state-atom {:keys [client-data server-key]}]
  (swap! state-atom assoc-in [:last-battle server-key :should-rejoin] false)
  (message/send-message state-atom client-data "LEAVEBATTLE")
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
      (message/send-message state-atom client-data
        (str "JOINBATTLE " selected-battle
             (if battle-passworded
               (str " " battle-password)
               (str " *"))
             " " (crypto.random/hex 6))))
    (log/warn "No battle to join" opts)))
