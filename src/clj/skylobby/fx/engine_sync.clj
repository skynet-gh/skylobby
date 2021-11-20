(ns skylobby.fx.engine-sync
  (:require
    [cljfx.api :as fx]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.download :refer [download-sources-by-name]]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.sync :refer [sync-pane]]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(defn engine-download-source [engine-version]
  (cond
    (string/blank? engine-version) nil
    (string/includes? engine-version "BAR") "BAR GitHub spring"
    :else "SpringRTS buildbot"))


(defn engine-sync-pane-impl
  [{:fx/keys [context]
    :keys [engine-version spring-isolation-dir]}]
  (let [copying (fx/sub-val context :copying)
        downloadables-by-url (fx/sub-val context :downloadables-by-url)
        indexed-engines (fx/sub-ctx context sub/indexed-engines spring-isolation-dir engine-version)
        spring-root-path (fs/canonical-path spring-isolation-dir)
        engine-override (fs/canonical-path (fx/sub-val context get-in [:engine-overrides spring-root-path engine-version]))
        engine-details (or (first (filter (comp #{engine-override} fs/canonical-path :file) indexed-engines))
                           (fx/sub-ctx context sub/indexed-engine spring-isolation-dir engine-version))
        engine-file (:file engine-details)
        extracting (fx/sub-val context :extracting)
        file-cache (fx/sub-val context :file-cache)
        http-download (fx/sub-val context :file-cache)
        importables-by-path (fx/sub-val context :importables-by-path)
        springfiles-search-results (fx/sub-val context :springfiles-search-results)
        engine-update-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/refresh-engines)
        download-tasks (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/download-and-extract)
                            (map (comp :download-url :downloadable))
                            set)
        extract-tasks (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/extract-7z)
                           (map (comp fs/canonical-path :file))
                           set)
        download-source-update-tasks (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/update-downloadables)
                                          (map :download-source-name)
                                          set)
        refresh-in-progress (seq engine-update-tasks)]
    {:fx/type sync-pane
     :h-box/margin 8
     :resource "Engine"
     :in-progress refresh-in-progress
     :browse-action {:event/type :spring-lobby/desktop-browse-dir
                     :file (or engine-file
                               (fs/engines-dir spring-isolation-dir))}
     :issues
     (concat
       (let [severity (if engine-details
                        0
                        (if (seq engine-update-tasks)
                          -1 2))]
         [{:severity severity
           :text "info"
           :human-text (str "Spring " engine-version)
           :choice engine-file
           :choices (map :file indexed-engines)
           :on-choice-changed {:event/type :spring-lobby/assoc-in
                               :path [:engine-overrides spring-root-path engine-version]}
           :tooltip (if (zero? severity)
                      (fs/canonical-path (:file engine-details))
                      (str "Engine '" engine-version "' not found locally"))}])
       (when (and (not engine-details) (empty? engine-update-tasks))
         (let [downloadable (->> downloadables-by-url
                                 vals
                                 (filter (comp #{:spring-lobby/engine} :resource-type))
                                 (filter (partial resource/could-be-this-engine? engine-version))
                                 first)
               url (:download-url downloadable)
               download (get http-download url)
               download-source-name (engine-download-source engine-version)
               in-progress (or (:running download)
                               (contains? download-tasks url)
                               (and (not downloadable)
                                    download-source-name
                                    (contains? download-source-update-tasks download-source-name)))
               dest (resource/resource-dest spring-isolation-dir downloadable)
               dest-path (fs/canonical-path dest)
               dest-exists (fs/file-exists? file-cache dest)
               severity (if dest-exists -1 2)
               resource-filename (:resource-filename downloadable)
               extract-target (when (and spring-isolation-dir resource-filename)
                                (io/file spring-isolation-dir "engine" (fs/without-extension resource-filename)))
               extract-exists (fs/file-exists? file-cache extract-target)]
           (concat
             [{:severity severity
               :text "download"
               :human-text (if in-progress
                             (if (and (not downloadable) download-source-name)
                               (str "Updating download source " download-source-name)
                               (u/download-progress download))
                             (if downloadable
                               (if dest-exists
                                 (str "Downloaded " (fs/filename dest))
                                 (str "Download from " (:download-source-name downloadable)))
                               (if download-source-name
                                 (str "Update download source " download-source-name)
                                 "No download found")))
               :tooltip (if in-progress
                          (if (and (not downloadable) download-source-name)
                            (str "Updating download source " download-source-name)
                            (str "Downloading " (u/download-progress download)))
                          (if dest-exists
                            (str "Downloaded to " dest-path)
                            (str "Download " url)))
               :in-progress in-progress
               :action (when-not dest-exists
                         (cond
                           downloadable
                           {:event/type :spring-lobby/add-task
                            :task
                            {:spring-lobby/task-type :spring-lobby/download-and-extract
                             :downloadable downloadable
                             :spring-isolation-dir spring-isolation-dir}}
                           download-source-name
                           {:event/type :spring-lobby/add-task
                            :task
                            (merge
                              {:spring-lobby/task-type :spring-lobby/update-downloadables
                               :force true}
                              (get download-sources-by-name download-source-name))}
                           :else nil))}]
             (when dest-exists
               [{:severity (if extract-exists -1 2)
                 :text "extract"
                 :in-progress (or (get extracting dest-path)
                                  (contains? extract-tasks dest-path))
                 :human-text "Extract engine archive"
                 :tooltip (str "Click to extract " dest-path)
                 :action {:event/type :spring-lobby/extract-7z
                          :file dest
                          :dest extract-target}}]))))
       (when refresh-in-progress
         [{:severity -1
           :text "refresh"
           :human-text "Refreshing engines"
           :tooltip "Refreshing engines"
           :in-progress true}])
       (when (and (not engine-details) (not (engine-download-source engine-version)))
         (let [springname (str "Spring " engine-version)
               springfiles-searched (contains? springfiles-search-results springname)
               springfiles-search-result (get springfiles-search-results springname)
               springfiles-download (->> http-download
                                         (filter (comp (set (:mirrors springfiles-search-result)) first))
                                         first
                                         second)
               springfiles-in-progress (:running springfiles-download)]
           [{:severity 2
             :text "springfiles"
             :human-text (if springfiles-in-progress
                           (u/download-progress springfiles-download)
                           (if springfiles-searched
                             (if springfiles-search-result
                               "Download from springfiles"
                               "Not found on springfiles")
                             "Search springfiles"))
             :in-progress springfiles-in-progress
             :action
             (when (or (not springfiles-searched) springfiles-search-result)
               {:event/type :spring-lobby/add-task
                :task
                (if springfiles-searched
                  {:spring-lobby/task-type :spring-lobby/download-springfiles
                   :resource-type :spring-lobby/engine
                   :search-result springfiles-search-result
                   :springname springname
                   :spring-isolation-dir spring-isolation-dir}
                  {:spring-lobby/task-type :spring-lobby/search-springfiles
                   :category (cond
                               (fs/windows?) "engine_windows*"
                               (fs/linux?) "engine_linux*"
                               :else "**")
                   :springname springname})})}]))
       (when-not engine-details
         (let [importable (some->> importables-by-path
                                   vals
                                   (filter (comp #{:spring-lobby/engine} :resource-type))
                                   (filter (partial resource/could-be-this-engine? engine-version))
                                   first)
               {:keys [import-source-name resource-file]} importable
               resource-path (fs/canonical-path resource-file)
               dest (resource/resource-dest spring-isolation-dir importable)
               dest-exists (fs/file-exists? file-cache dest)]
           (when importable
             [{:severity (if dest-exists -1 2)
               :text "import"
               :human-text (if importable
                             (str "Import from " import-source-name)
                             "No import found")
               :tooltip (if importable
                          (str "Copy engine dir from " import-source-name " at " resource-path)
                          (str "No local import found for " engine-version))
               :in-progress (-> copying (get resource-path) :status)
               :action
               (when (and importable
                          (not (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir importable))))
                 {:event/type :spring-lobby/add-task
                  :task
                  {:spring-lobby/task-type :spring-lobby/import
                   :importable importable
                   :spring-isolation-dir spring-isolation-dir}})}]))))}))

(defn engine-sync-pane [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :engine-sync-pane
      (engine-sync-pane-impl state))))
