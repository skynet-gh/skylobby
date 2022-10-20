(ns skylobby.auto-resources
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.http :as http]
    [skylobby.rapid :as rapid]
    [skylobby.resource :as resource]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn import-dest-is-source? [spring-root {:keys [resource-file] :as importable}]
  (= (fs/canonical-path resource-file)
     (fs/canonical-path (resource/resource-dest spring-root importable))))


(defn resource-details
  [{:keys [engine-version map-name mod-name spring-root]}
   {:keys [by-spring-root spring-isolation-dir]}]
  (let [
        spring-root-path (fs/canonical-path (or spring-root spring-isolation-dir))
        {:keys [engines maps mods]} (get by-spring-root spring-root-path)]
    {:engine-details (resource/engine-details engines engine-version)
     :map-details (->> maps
                       (filter (comp #{map-name} :map-name))
                       first)
     :mod-details (->> mods
                       (filter (comp #{mod-name} :mod-name))
                       first)}))

(defn auto-resources-tasks
  [{:keys [battle-changed engine-version map-name mod-name spring-root] :as resources}
   {:keys [by-spring-root cooldowns current-tasks db downloadables-by-url file-cache http-download
           importables-by-path rapid-by-spring-root
           springfiles-search-results spring-isolation-dir tasks-by-kind use-db-for-rapid] :as state}]
  (let [
        spring-root (or spring-root spring-isolation-dir)
        spring-root-path (fs/canonical-path spring-root)
        {:keys [rapid-data-by-version]} (get rapid-by-spring-root spring-root-path)
        {:keys [engines]} (get by-spring-root spring-root-path)
        rapid-data (if (and db use-db-for-rapid)
                     (rapid/rapid-data-by-version db spring-root-path mod-name)
                     (get rapid-data-by-version mod-name))
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
        {:keys [engine-details map-details mod-details]} (resource-details resources state)
        no-map (not map-details)
        downloadables (vals downloadables-by-url)
        map-downloadable (->> downloadables
                              (filter (comp #{:spring-lobby/map} :resource-type))
                              (filter (partial resource/could-be-this-map? map-name))
                              shuffle
                              first)
        map-download-task (->> all-tasks
                               (filter (comp #{:spring-lobby/http-downloadable} :spring-lobby/task-type))
                               (filter (comp (partial resource/same-resource-filename? map-downloadable) :downloadable))
                               first)
        search-springfiles-map-task (->> all-tasks
                                         (filter (comp #{:spring-lobby/search-springfiles} :spring-lobby/task-type))
                                         (filter (comp #{map-name} :springname))
                                         first)
        search-springfiles-mod-task (->> all-tasks
                                         (filter (comp #{:spring-lobby/search-springfiles} :spring-lobby/task-type))
                                         (filter (comp #{mod-name} :springname))
                                         first)
        download-springfiles-map-task (->> all-tasks
                                           (filter (comp #{:spring-lobby/download-springfiles :spring-lobby/http-downloadable} :spring-lobby/task-type))
                                           (filter (comp #{map-name} :springname))
                                           first)
        download-springfiles-mod-task (->> all-tasks
                                           (filter (comp #{:spring-lobby/download-springfiles :spring-lobby/http-downloadable} :spring-lobby/task-type))
                                           (filter (comp #{mod-name} :springname))
                                           first)
        engine-file (:file engine-details)
        engine-dir-exists (and (fs/exists? engine-file)
                               (fs/is-directory? engine-file))
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
                                 shuffle
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
                              shuffle
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
        no-mod (not mod-details)
        map-springfiles-search-result (get springfiles-search-results map-name)
        mod-springfiles-search-result (get springfiles-search-results mod-name)
        map-springfiles-url (http/springfiles-url map-springfiles-search-result)
        mod-springfiles-url (http/springfiles-url mod-springfiles-search-result)
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
                        (not (fs/exists? engine-download-dest)))
                   (do
                     (log/info "Adding task to auto download engine" engine-downloadable)
                     {:spring-lobby/task-type :spring-lobby/download-and-extract
                      :downloadable engine-downloadable
                      :spring-isolation-dir spring-root})
                   (and (not engine-importable)
                        engine-downloadable
                        (not engine-download-task)
                        (fs/exists? engine-download-dest)
                        (not engine-extract-task)
                        (or
                          (not (fs/exists? engine-extract-dest))
                          (fs/is-file? engine-extract-dest)))
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
                        (fs/file-exists? file-cache engine-extract-dest)
                        (fs/is-directory? engine-extract-dest)
                        (not
                          (some
                            (comp #{engine-extract-dest} :file)
                            engines)))
                   (do
                     (log/info "Refreshing engines to pick up" engine-extract-dest)
                     {:spring-lobby/task-type :spring-lobby/refresh-engines
                      :force true
                      :priorities [engine-extract-dest]
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
                        map-springfiles-search-result
                        ((fnil < 0) (:tries (get http-download map-springfiles-url)) resource/max-tries)
                        (not download-springfiles-map-task)
                        (not (:spring-lobby/refresh-maps tasks-by-type)))
                   (do
                     (log/info "Adding task to download map" map-name "from springfiles")
                     {:spring-lobby/task-type :spring-lobby/download-springfiles
                      :resource-type :spring-lobby/map
                      :springname map-name
                      :search-result (get springfiles-search-results map-name)
                      :spring-isolation-dir spring-root})
                   (and map-name
                        (not (:spring-lobby/refresh-maps tasks-by-type))
                        (u/check-cooldown cooldowns [:refresh-maps]))
                   {:spring-lobby/task-type :spring-lobby/refresh-maps
                    :spring-root spring-root}
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
                        engine-dir-exists
                        sdp-file-exists)
                   (do
                     (log/info "Refreshing mods to pick up" sdp-file)
                     {:spring-lobby/task-type :spring-lobby/refresh-mods
                      :delete-invalid-sdp true
                      :priorities [sdp-file]
                      :spring-root spring-root})
                   (and rapid-id
                        (not rapid-task)
                        (not update-rapid-task)
                        engine-file
                        engine-dir-exists
                        (not sdp-file-exists)
                        (u/check-cooldown cooldowns [:rapid spring-root-path rapid-id]))
                   (do
                     (log/info "Adding task to auto download rapid" rapid-id)
                     {:spring-lobby/task-type :spring-lobby/rapid-download
                      :engine-file engine-file
                      :engine-dir-exists engine-dir-exists
                      :mod-name mod-name
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
                   (and mod-name
                        (not rapid-id)
                        (not rapid-task)
                        (not (contains? springfiles-search-results mod-name))
                        (not search-springfiles-mod-task))
                   (do
                     (log/info "Adding task to search springfiles for mod" mod-name)
                     {:spring-lobby/task-type :spring-lobby/search-springfiles
                      :springname mod-name
                      :resource-type :spring-lobby/mod
                      :spring-isolation-dir spring-root})
                   (and mod-name
                        (not rapid-id)
                        (not rapid-task)
                        mod-springfiles-search-result
                        ((fnil < 0) (:tries (get http-download mod-springfiles-url)) resource/max-tries)
                        (not download-springfiles-mod-task)
                        (not (:spring-lobby/refresh-mods tasks-by-type)))
                   (do
                     (log/info "Adding task to download mod" mod-name "from springfiles")
                     {:spring-lobby/task-type :spring-lobby/download-springfiles
                      :resource-type :spring-lobby/mod
                      :springname mod-name
                      :search-result (get springfiles-search-results mod-name)
                      :spring-isolation-dir spring-root})
                   (and (not rapid-id)
                        (not rapid-task)
                        (not update-rapid-task)
                        engine-file
                        engine-dir-exists
                        (u/check-cooldown cooldowns [:rapid spring-root-path mod-name])
                        (not (some #(re-find % mod-name) resource/no-rapid)))
                   (do
                     (log/info "Adding task to auto download rapid blind for" (str "'" mod-name "'"))
                     {:spring-lobby/task-type :spring-lobby/rapid-download
                      :engine-file engine-file
                      :engine-dir-exists engine-dir-exists
                      :mod-name mod-name
                      :rapid-id mod-name
                      :spring-isolation-dir spring-root})
                   (and (not rapid-id)
                        (not rapid-task)
                        engine-file
                        engine-dir-exists
                        (not update-rapid-task)
                        (not (string/blank? mod-name))
                        (u/check-cooldown cooldowns [:update-rapid spring-root-path])
                        (not (some #(re-find % mod-name) resource/no-rapid)))
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
                            :engine-dir-exists engine-dir-exists
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
