(ns skylobby.battle-sync
  (:require
    [skylobby.fs :as fs]
    [skylobby.client.gloss :as gloss]
    [skylobby.client.message :as message]
    [skylobby.event.battle :as event.battle]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn update-battle-status-sync-watcher [_k state-atom old-state new-state]
  (when (some (comp u/server-needs-battle-status-sync-check second)
              (-> new-state :by-server seq))
    (try
      (log/info "Checking servers for battle sync status updates")
      (doseq [[server-key new-server] (concat
                                        (->> new-state :by-server u/valid-servers)
                                        (->> new-state u/complex-servers))]
        (if (and (= (:servers old-state)
                    (:servers new-state))
                 (not (u/server-needs-battle-status-sync-check new-server)))
          (log/debug "Server" server-key "does not need battle sync status check")
          (let [
                _ (log/info "Checking battle sync status for" server-key)
                old-server (get-in old-state [:by-server server-key])
                server-url (get-in new-server [:client-data :server-url])
                {:keys [servers spring-isolation-dir]} new-state
                spring-root (or (get-in servers [server-url :spring-isolation-dir])
                                spring-isolation-dir)
                spring-root-path (fs/canonical-path spring-root)

                old-spring (get-in old-state [:by-spring-root spring-root-path])
                new-spring (get-in new-state [:by-spring-root spring-root-path])

                old-sync (resource/sync-status old-server old-spring (:mod-details old-state) (:map-details old-state))
                new-sync (resource/sync-status new-server new-spring (:mod-details new-state) (:map-details new-state))

                new-sync-number (u/sync-number new-sync)
                battle (:battle new-server)
                client-data (:client-data new-server)
                my-username (or (:username client-data)
                                (:username new-server))
                {:keys [battle-status team-color]} (get-in battle [:users my-username])
                old-sync-number (get-in battle [:users my-username :battle-status :sync])
                battle-id (:battle-id battle)
                battle-changed (not= battle-id
                                     (-> old-server :battle :battle-id))]
            (when (and battle-id
                       (or (not= old-sync new-sync)
                           (not= old-sync-number new-sync-number)
                           battle-changed))
              (if battle-changed
                (log/info "Setting battle sync status for" server-key "in battle" battle-id "to" new-sync "(" new-sync-number ")")
                (log/info "Updating battle sync status for" server-key "in battle" battle-id "from" old-sync
                          "(" old-sync-number ") to" new-sync "(" new-sync-number ")"))
              (if (#{:direct-client :direct-host} (u/server-type server-key))
                (event.battle/update-player-or-bot-state
                  state-atom
                  server-key
                  {:username my-username}
                  {:battle-status {:sync new-sync-number}})
                (let [new-battle-status (assoc battle-status :sync new-sync-number)]
                  (when (and (= old-sync new-sync)
                             (not= old-sync-number new-sync-number))
                    ; teiserver bug workaround)
                    (swap! state-atom assoc-in [:by-server server-key :battle :users my-username :battle-status :sync] new-sync-number))
                  (message/send state-atom client-data
                    (str "MYBATTLESTATUS " (gloss/encode-battle-status new-battle-status) " " (or team-color 0)))))))))
      (catch Exception e
        (log/error e "Error in :update-battle-status-sync state watcher")))))
