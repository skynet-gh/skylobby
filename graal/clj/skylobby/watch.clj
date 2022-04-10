(ns skylobby.watch
  (:require
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.resource :as resource]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


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


(defn- replay-map-and-mod-details-relevant-keys [state]
  (select-keys
    state
    [:by-spring-root :map-details :mod-details :online-bar-replays :parsed-replays-by-path
     :selected-replay-file :selected-replay-id :servers :spring-isolation-dir]))

(defn replay-map-and-mod-details-watcher [_k state-atom old-state new-state]
  (when (or (not= (replay-map-and-mod-details-relevant-keys old-state)
                  (replay-map-and-mod-details-relevant-keys new-state))
            (:single-replay-view new-state))
    (try
      (let [old-selected-replay-file (:selected-replay-file old-state)
            old-replay-id (:selected-replay-id old-state)
            {:keys [online-bar-replays parsed-replays-by-path replay-details selected-replay-file
                    selected-replay-id spring-isolation-dir]} new-state

            old-replay-path (fs/canonical-path old-selected-replay-file)
            new-replay-path (fs/canonical-path selected-replay-file)

            old-replay (or (get parsed-replays-by-path old-replay-path)
                           (get online-bar-replays old-replay-id))
            new-replay (or (get parsed-replays-by-path new-replay-path)
                           (get online-bar-replays selected-replay-id))

            old-mod (:replay-mod-name old-replay)
            old-map (:replay-map-name old-replay)

            new-mod (:replay-mod-name new-replay)
            new-map (:replay-map-name new-replay)

            spring-root-path (fs/canonical-path spring-isolation-dir)

            old-maps (-> old-state :by-spring-root (get spring-root-path) :maps)
            new-maps (-> new-state :by-spring-root (get spring-root-path) :maps)

            old-mods (-> old-state :by-spring-root (get spring-root-path) :mods)
            new-mods (-> new-state :by-spring-root (get spring-root-path) :mods)

            new-mod-sans-git (u/mod-name-sans-git new-mod)
            mod-name-set (set [new-mod new-mod-sans-git])
            filter-fn (comp mod-name-set u/mod-name-sans-git :mod-name)

            map-exists (->> new-maps (filter (comp #{new-map} :map-name)) first)
            mod-exists (->> new-mods (filter filter-fn) first)

            map-details (resource/cached-details (:map-details new-state) map-exists)
            mod-details (resource/cached-details (:mod-details new-state) mod-exists)

            map-changed (not= new-map (:map-name map-details))
            mod-changed (not= new-mod (:mod-name mod-details))

            replay-details (get replay-details new-replay-path)

            tasks [
                   (when (or (and (or (not= old-replay-path new-replay-path)
                                      (not= old-mod new-mod)
                                      (:single-replay-view new-state))
                                  (and (not (string/blank? new-mod))
                                       (or (not (resource/details? mod-details))
                                           mod-changed)))
                             (and
                               (or (not (some filter-fn old-mods))
                                   (:single-replay-view new-state))
                               mod-exists))
                     {:spring-lobby/task-type :spring-lobby/mod-details
                      :mod-name new-mod
                      :mod-file (:file mod-exists)
                      :use-git-mod-version (:use-git-mod-version new-state)})
                   (when (or (and (or (not= old-replay-path new-replay-path)
                                      (not= old-map new-map)
                                      (:single-replay-view new-state))
                                  (and (not (string/blank? new-map))
                                       (or (not (resource/details? map-details))
                                           map-changed)))
                             (and
                               (or (not (some (comp #{new-map} :map-name) old-maps))
                                   (:single-replay-view new-state))
                               map-exists))
                     {:spring-lobby/task-type :spring-lobby/map-details
                      :map-name new-map
                      :map-file (:file map-exists)})
                   (when (and (not= old-replay-path new-replay-path)
                              (not replay-details))
                     {:spring-lobby/task-type :spring-lobby/replay-details
                      :replay-file selected-replay-file})]]
        (when-let [tasks (->> tasks (filter some?) seq)]
          (log/info "Adding" (count tasks) "for replay resources")
          (task/add-tasks! state-atom tasks)))
      (catch Exception e
        (log/error e "Error in :replay-map-and-mod-details state watcher")))))
