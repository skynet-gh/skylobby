(ns skylobby.watch
  (:require 
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.resource :as resource]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(defn- battle-map-details-relevant-keys [state]
  (-> state
      (select-keys [:by-server :by-spring-root :map-details :servers :spring-isolation-dir])
      (update :by-server
        (fn [by-server]
          (reduce-kv
            (fn [m k v]
              (assoc m k
                (-> v
                    (select-keys [:battle :battles])
                    (update :battles select-keys [(:battle-id (:battle v))]))))
            {}
            by-server)))))

(defn- battle-mod-details-relevant-data [state]
  (-> state
      (select-keys [:by-server :by-spring-root :mod-details :servers :spring-isolation-dir])
      (update :by-server
        (fn [by-server]
          (reduce-kv
            (fn [m k v]
              (assoc m k
                (-> v
                    (select-keys [:battle :battles])
                    (update :battles select-keys [(:battle-id (:battle v))]))))
            {}
            by-server)))))

(defn battle-map-details-watcher [_k state-atom old-state new-state]
  (when (not= (battle-map-details-relevant-keys old-state)
              (battle-map-details-relevant-keys new-state))
    (try
      (doseq [[server-key new-server] (-> new-state :by-server seq)]
        (let [old-server (-> old-state :by-server (get server-key))
              server-url (-> new-server :client-data :server-url)
              {:keys [current-tasks servers spring-isolation-dir tasks-by-kind]} new-state
              spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                              spring-isolation-dir)
              spring-root-path (fs/canonical-path spring-root)
              old-maps (-> old-state :by-spring-root (get spring-root-path) :maps)
              new-maps (-> new-state :by-spring-root (get spring-root-path) :maps)
              old-battle-id (-> old-server :battle :battle-id)
              new-battle-id (-> new-server :battle :battle-id)
              old-map (-> old-server :battles (get old-battle-id) :battle-map)
              new-map (-> new-server :battles (get new-battle-id) :battle-map)
              map-exists (->> new-maps (filter (comp #{new-map} :map-name)) first)
              map-details (-> new-state :map-details (get (fs/canonical-path (:file map-exists))))
              tries (or (:tries map-details) resource/max-tries)
              all-tasks (concat (mapcat second tasks-by-kind) (vals current-tasks))]
          (when (and (or (not (resource/details? map-details))
                         (< tries resource/max-tries))
                     (or (and (not (string/blank? new-map))
                              (not (resource/details? map-details))
                              (or (not= old-battle-id new-battle-id)
                                  (not= old-map new-map)
                                  (and
                                    (empty? (filter (comp #{:spring-lobby/map-details} :spring-lobby/task-type) all-tasks))
                                    map-exists)))
                         (and
                           (not (some (comp #{new-map} :map-name) old-maps))
                           map-exists)))
            (log/info "Mod details update for" server-key)
            (task/add-task! state-atom
              {:spring-lobby/task-type :spring-lobby/map-details
               :map-name new-map
               :map-file (:file map-exists)
               :source :battle-map-details-watcher
               :tries tries}))))
      (catch Exception e
        (log/error e "Error in :battle-map-details state watcher")))))


(defn battle-mod-details-watcher [_k state-atom old-state new-state]
  (when (not= (battle-mod-details-relevant-data old-state)
              (battle-mod-details-relevant-data new-state))
    (try
      (doseq [[server-key new-server] (-> new-state :by-server seq)]
        (if-not (u/server-needs-battle-status-sync-check new-server)
          (log/debug "Server" server-key "does not need battle mod details check")
          (let [old-server (-> old-state :by-server (get server-key))
                server-url (-> new-server :client-data :server-url)
                {:keys [current-tasks servers spring-isolation-dir tasks-by-kind]} new-state
                spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                                spring-isolation-dir)
                spring-root-path (fs/canonical-path spring-root)
                old-mods (-> old-state :by-spring-root (get spring-root-path) :mods)
                new-mods (-> new-state :by-spring-root (get spring-root-path) :mods)
                old-battle-id (-> old-server :battle :battle-id)
                new-battle-id (-> new-server :battle :battle-id)
                old-mod (-> old-server :battles (get old-battle-id) :battle-modname)
                new-mod (-> new-server :battles (get new-battle-id) :battle-modname)
                new-mod-sans-git (u/mod-name-sans-git new-mod)
                mod-name-set (set [new-mod new-mod-sans-git])
                filter-fn (comp mod-name-set u/mod-name-sans-git :mod-name)
                mod-exists (->> new-mods (filter filter-fn) first)
                mod-details (resource/cached-details (:mod-details new-state) mod-exists)
                all-tasks (concat (mapcat second tasks-by-kind) (vals current-tasks))]
            (when (or (and (and (not (string/blank? new-mod))
                                (not (resource/details? mod-details)))
                           (or (not= old-battle-id new-battle-id)
                               (not= old-mod new-mod)
                               (and
                                 (empty? (filter (comp #{:spring-lobby/mod-details} :spring-lobby/task-type) all-tasks))
                                 mod-exists)))
                      (and (not (some filter-fn old-mods))
                           mod-exists))
              (log/info "Mod details update for" server-key)
              (task/add-task! state-atom
                {:spring-lobby/task-type :spring-lobby/mod-details
                 :mod-name new-mod
                 :mod-file (:file mod-exists)
                 :source :battle-mod-details-watcher
                 :use-git-mod-version (:use-git-mod-version new-state)})))))
      (catch Exception e
        (log/error e "Error in :battle-mod-details state watcher")))))
