(ns skylobby.auto-resources
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [skylobby.http :as http]
    [skylobby.fs :as fs]
    [skylobby.rapid :as rapid]
    [skylobby.resource :as resource]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(defn import-dest-is-source? [spring-root {:keys [resource-file] :as importable}]
  (= (fs/canonical-path resource-file)
     (fs/canonical-path (resource/resource-dest spring-root importable))))


(defn auto-resources-tasks
  [{:keys [battle-changed engine-version map-name mod-name spring-root]}
   {:keys [by-spring-root cooldowns current-tasks downloadables-by-url file-cache http-download importables-by-path rapid-by-spring-root
           springfiles-search-results tasks-by-kind]}]
  (let [
        spring-root-path (fs/canonical-path spring-root)
        {:keys [rapid-data-by-version]} (get rapid-by-spring-root spring-root-path)
        {:keys [engines maps mods]} (get by-spring-root spring-root-path)
        rapid-data (get rapid-data-by-version mod-name)
        rapid-id (:id rapid-data)
        sdp-file (rapid/sdp-file spring-root (rapid/sdp-filename (:hash rapid-data)))
        sdp-file-exists (fs/exists? sdp-file)
        all-tasks (concat (mapcat second tasks-by-kind) (vals current-tasks))
        tasks-by-type (group-by :spring-lobby/task-type all-tasks)
        rapid-task (->> all-tasks
                        (filter (comp #{:spring-lobby/rapid-download} :spring-lobby/task-type))
                        (filter (comp #{rapid-id} :rapid-id))
                        first)
        update-rapid-task (->> all-tasks
                               (filter (comp #{:spring-lobby/update-rapid :spring-lobby/update-rapid-packages} :spring-lobby/task-type))
                               first)
        importables (vals importables-by-path)
        map-importable (some->> importables
                                (filter (comp #{:spring-lobby/map} :resource-type))
                                (filter (partial resource/could-be-this-map? map-name))
                                first)
        map-import-task (->> all-tasks
                             (filter (comp #{:spring-lobby/import} :spring-lobby/task-type))
                             (filter (comp (partial resource/same-resource-file? map-importable) :importable))
                             (remove (partial import-dest-is-source? spring-root))
                             first)
        no-map (->> maps
                    (filter (comp #{map-name} :map-name))
                    first
                    not)
        downloadables (vals downloadables-by-url)
        map-downloadable (->> downloadables
                              (filter (comp #{:spring-lobby/map} :resource-type))
                              (filter (partial resource/could-be-this-map? map-name))
                              first)
        map-download-task (->> all-tasks
                               (filter (comp #{:spring-lobby/http-downloadable} :spring-lobby/task-type))
                               (filter (comp (partial resource/same-resource-filename? map-downloadable) :downloadable))
                               first)
        search-springfiles-map-task (->> all-tasks
                                         (filter (comp #{:spring-lobby/search-springfiles} :spring-lobby/task-type))
                                         (filter (comp #{map-name} :springname))
                                         first)
        download-springfiles-map-task (->> all-tasks
                                           (filter (comp #{:spring-lobby/download-springfiles :spring-lobby/http-downloadable} :spring-lobby/task-type))
                                           (filter (comp #{map-name} :springname))
                                           first)
        engine-details (resource/engine-details engines engine-version)
        engine-file (:file engine-details)
        engine-importable (some->> importables
                                   (filter (comp #{:spring-lobby/engine} :resource-type))
                                   (filter (partial resource/could-be-this-engine? engine-version))
                                   (remove (partial import-dest-is-source? spring-root))
                                   first)
        engine-import-task (->> all-tasks
                                (filter (comp #{:spring-lobby/import} :spring-lobby/task-type))
                                (filter (comp (partial resource/same-resource-file? engine-importable) :importable))
                                first)
        engine-downloadable (->> downloadables
                                 (filter (comp #{:spring-lobby/engine} :resource-type))
                                 (filter (partial resource/could-be-this-engine? engine-version))
                                 first)
        engine-download-dest (resource/resource-dest spring-root engine-downloadable)
        engine-extract-dest (when engine-download-dest
                              (fs/file spring-root "engine" (fs/filename engine-download-dest)))
        engine-download-task (->> all-tasks
                                  (filter (comp #{:spring-lobby/download-and-extract :spring-lobby/http-downloadable} :spring-lobby/task-type))
                                  (filter (comp (partial resource/same-resource-filename? engine-downloadable) :downloadable))
                                  first)
        engine-extract-task (->> all-tasks
                                 (filter (comp #{:spring-lobby/extract-7z} :spring-lobby/task-type))
                                 (filter (comp (partial resource/same-resource-filename? engine-downloadable) fs/filename :file))
                                 first)
        mod-downloadable (->> downloadables
                              (filter (comp #{:spring-lobby/mod} :resource-type))
                              (filter (partial resource/could-be-this-mod? mod-name))
                              first)
        mod-download-task (->> all-tasks
                               (filter (comp #{:spring-lobby/http-downloadable} :spring-lobby/task-type))
                               (filter (comp (partial resource/same-resource-filename? mod-downloadable) :downloadable))
                               first)
        mod-refresh-tasks (->> all-tasks
                               (filter (comp #{:spring-lobby/refresh-mods} :spring-lobby/task-type))
                               (map (comp fs/canonical-path :spring-root))
                               set)
        engine-refresh-tasks (->> all-tasks
                                  (filter (comp #{:spring-lobby/refresh-engines} :spring-lobby/task-type))
                                  (map (comp fs/canonical-path :spring-root))
                                  set)
        no-mod (->> mods
                    (filter (comp #{mod-name} :mod-name))
                    first
                    not)
        springfiles-search-result (get springfiles-search-results map-name)
        springfiles-url (http/springfiles-url springfiles-search-result)
        download-source-tasks (->> all-tasks
                                   (filter (comp #{:spring-lobby/update-downloadables}))
                                   (map :download-source-name)
                                   set)
        engine-download-source-name (resource/engine-download-source engine-version)
        tasks [(when
                 (not engine-details)
                 (cond
                   (and engine-importable
                        (not engine-import-task)
                        (not (fs/file-exists? file-cache (resource/resource-dest spring-root engine-importable))))
                   (do
                     (log/info "Adding task to auto import engine" engine-importable)
                     {:spring-lobby/task-type :spring-lobby/import
                      :importable engine-importable
                      :spring-isolation-dir spring-root})
                   (and (not engine-importable)
                        engine-downloadable
                        (not engine-download-task)
                        (not (fs/file-exists? file-cache engine-download-dest)))
                   (do
                     (log/info "Adding task to auto download engine" engine-downloadable)
                     {:spring-lobby/task-type :spring-lobby/download-and-extract
                      :downloadable engine-downloadable
                      :spring-isolation-dir spring-root})
                   (and (not engine-importable)
                        engine-downloadable
                        (not engine-download-task)
                        (fs/file-exists? file-cache engine-download-dest)
                        (not engine-extract-task)
                        (not (fs/file-exists? file-cache engine-extract-dest)))
                   (do
                     (log/info "Adding task to extract engine archive" engine-download-dest)
                     {:spring-lobby/task-type :spring-lobby/extract-7z
                      :file engine-download-dest
                      :dest engine-extract-dest})
                   (and (not engine-importable)
                        (not engine-downloadable)
                        engine-download-source-name
                        (u/check-cooldown cooldowns [:download-source engine-download-source-name])
                        (not (contains? download-source-tasks engine-download-source-name)))
                   (do
                     (log/info "Adding task to update download source" engine-download-source-name "looking for" engine-version)
                     (merge
                       {:spring-lobby/task-type :spring-lobby/update-downloadables
                        :force true}
                       (get http/download-sources-by-name engine-download-source-name)))
                   (and (not (contains? engine-refresh-tasks spring-root-path))
                        (fs/file-exists? file-cache engine-extract-dest))
                   (do
                     (log/info "Refreshing engines to pick up" engine-extract-dest)
                     {:spring-lobby/task-type :spring-lobby/refresh-engines
                      :force true
                      :priorites [engine-extract-dest]
                      :spring-root spring-root})
                   :else
                   (when engine-version
                     (log/info "Nothing to do to auto get engine" engine-version
                       (with-out-str
                         (pprint
                           {:importable engine-importable
                            :downloadable engine-downloadable
                            :download-source-name engine-download-source-name
                            :download-task engine-download-task
                            :spring-root spring-root}))))))
               (when
                 no-map
                 (cond
                   (and map-importable
                        (not map-import-task)
                        (not (fs/file-exists? file-cache (resource/resource-dest spring-root map-importable))))
                   (do
                     (log/info "Adding task to auto import map" map-importable)
                     {:spring-lobby/task-type :spring-lobby/import
                      :importable map-importable
                      :spring-isolation-dir spring-root})
                   (and (not map-importable)
                        map-downloadable
                        (not map-download-task)
                        (not (fs/file-exists? file-cache (resource/resource-dest spring-root map-downloadable))))
                   (do
                     (log/info "Adding task to auto download map" map-downloadable)
                     {:spring-lobby/task-type :spring-lobby/http-downloadable
                      :downloadable map-downloadable
                      :spring-isolation-dir spring-root})
                   (and map-name
                        (not map-importable)
                        (not map-downloadable)
                        (not (contains? springfiles-search-results map-name))
                        (not search-springfiles-map-task))
                   (do
                     (log/info "Adding task to search springfiles for map" map-name)
                     {:spring-lobby/task-type :spring-lobby/search-springfiles
                      :springname map-name
                      :resource-type :spring-lobby/map
                      :spring-isolation-dir spring-root})
                   (and map-name
                        (not map-importable)
                        (not map-downloadable)
                        springfiles-search-result
                        ((fnil < 0) (:tries (get http-download springfiles-url)) resource/max-tries)
                        (not download-springfiles-map-task)
                        (not (:spring-lobby/refresh-maps tasks-by-type)))
                   (do
                     (log/info "Adding task to download map" map-name "from springfiles")
                     {:spring-lobby/task-type :spring-lobby/download-springfiles
                      :resource-type :spring-lobby/map
                      :springname map-name
                      :search-result (get springfiles-search-results map-name)
                      :spring-isolation-dir spring-root})
                   :else
                   (when map-name
                     (log/info "Nothing to do to auto get map" map-name))))
               (when
                 no-mod
                 (cond
                   (and rapid-id
                        (not rapid-task)
                        (not update-rapid-task)
                        (not (contains? mod-refresh-tasks spring-root-path))
                        engine-file
                        sdp-file-exists)
                   (do
                     (log/info "Refreshing mods to pick up" sdp-file)
                     {:spring-lobby/task-type :spring-lobby/refresh-mods
                      :delete-invalid-sdp true
                      :priorites [sdp-file]
                      :spring-root spring-root})
                   (and rapid-id
                        (not rapid-task)
                        (not update-rapid-task)
                        engine-file
                        (not sdp-file-exists)
                        (u/check-cooldown cooldowns [:rapid spring-root-path rapid-id]))
                   (do
                     (log/info "Adding task to auto download rapid" rapid-id)
                     {:spring-lobby/task-type :spring-lobby/rapid-download
                      :engine-file engine-file
                      :rapid-id rapid-id
                      :spring-isolation-dir spring-root})
                   (and (not rapid-id)
                        mod-downloadable
                        (not mod-download-task)
                        (not (fs/file-exists? file-cache (resource/resource-dest spring-root mod-downloadable))))
                   (do
                     (log/info "Adding task to auto download mod" mod-downloadable)
                     {:spring-lobby/task-type :spring-lobby/http-downloadable
                      :downloadable mod-downloadable
                      :spring-isolation-dir spring-root})
                   (and (not rapid-id)
                        (not rapid-task)
                        engine-file
                        (not update-rapid-task)
                        (not (string/blank? mod-name))
                        (u/check-cooldown cooldowns [:update-rapid spring-root-path]))
                   (do
                     (log/info "Adding task to update rapid looking for" mod-name)
                     {:spring-lobby/task-type :spring-lobby/update-rapid
                      :engine-version engine-version
                      :mod-name mod-name
                      :spring-isolation-dir spring-root})
                   :else
                   (when mod-name
                     (log/info "Nothing to do to auto get game" mod-name
                       (with-out-str
                         (pprint
                           {:downloadable mod-downloadable
                            :download-task mod-download-task
                            :engine-file engine-file
                            :refresh-tasks mod-refresh-tasks
                            :rapid-data rapid-data
                            :sdp-file sdp-file
                            :sdp-file-exists sdp-file-exists
                            :spring-root spring-root}))))))
               (when
                 (and no-mod
                      (not rapid-id)
                      (not rapid-task)
                      (not update-rapid-task)
                      battle-changed)
                      ; ^ only do when first joining a battle
                 (log/info "Adding task to update rapid looking for" mod-name)
                 {:spring-lobby/task-type :spring-lobby/update-rapid
                  :engine-version engine-version
                  :mod-name mod-name
                  :spring-isolation-dir spring-root})]]
    (filter some? tasks)))

(defn server-auto-resources [server-key _old-state new-state old-server new-server]
  (when (:auto-get-resources new-state)
    (try
      (when (u/server-needs-battle-status-sync-check new-server)
        (log/info "Auto getting resources for" server-key)
        (let [{:keys [
                      servers spring-isolation-dir]} new-state
              {:keys [battle battles client-data]} new-server
              server-url (:server-url client-data)
              spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                              spring-isolation-dir)
              {:keys [battle-map battle-modname battle-version]} (get battles (:battle-id battle))
              battle-changed (and (-> new-server :battle :battle-id)
                                  (not= (-> old-server :battle :battle-id) (-> new-server :battle :battle-id)))]
          (auto-resources-tasks {
                                 :battle-changed battle-changed
                                 :engine-version battle-version
                                 :map-name battle-map
                                 :mod-name battle-modname
                                 :spring-root spring-root}
                                new-state)))
      (catch Exception e
        (log/error e "Error in :auto-get-resources state watcher for server" (first new-server))))))

(defn auto-get-resources-watcher [_k state-atom old-state new-state]
  (when (and (:auto-get-resources new-state)
             (some (comp u/server-needs-battle-status-sync-check second) (:by-server new-state)))
    (try
      (when-let [tasks (->> new-state
                            :by-server
                            (remove (comp keyword? first))
                            (mapcat
                              (fn [[server-key new-server]]
                                (let [old-server (-> old-state :by-server (get server-key))]
                                  (server-auto-resources server-key old-state new-state old-server new-server))))
                            (filter some?)
                            seq)]
        (log/info "Adding" (count tasks) "to auto get resources")
        (task/add-tasks! state-atom tasks))
      (catch Exception e
        (log/error e "Error in :auto-get-resources state watcher")))))
