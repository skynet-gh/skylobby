(ns skylobby.fx.map-sync
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.sub :as sub]
    [skylobby.fx.sync :refer [sync-pane]]
    [skylobby.http :as http]
    [skylobby.resource :as resource]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(defn- map-sync-pane-impl
  [{:fx/keys [context]
    :keys [battle-map map-name spring-isolation-dir]}]
  (let [copying (fx/sub-val context :copying)
        downloadables-by-url (fx/sub-val context :downloadables-by-url)
        file-cache (fx/sub-val context :file-cache)
        http-download (fx/sub-val context :http-download)
        importables-by-path (fx/sub-val context :importables-by-path)
        springfiles-search-results (fx/sub-val context :springfiles-search-results)
        tasks-by-type (fx/sub-ctx context skylobby.fx/tasks-by-type-sub)
        map-name (or map-name battle-map)
        indexed-map (fx/sub-ctx context sub/indexed-map spring-isolation-dir map-name)
        imports (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/import)
                     (map (comp fs/canonical-path :resource-file :importable))
                     set)
        map-details (fx/sub-ctx context skylobby.fx/map-details-sub indexed-map)
        refresh-maps-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/refresh-maps)
        map-details-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/map-details)
        map-update-tasks (concat refresh-maps-tasks map-details-tasks)
        no-map-details (not (resource/details? map-details))
        tries (:tries map-details)
        at-max-tries (and (number? tries)
                          (>= tries resource/max-tries))]
    {:fx/type sync-pane
     :h-box/margin 8
     :resource "Map"
     :browse-action {:event/type :spring-lobby/desktop-browse-dir
                     :file (fs/maps-dir spring-isolation-dir)}
     :refresh-action {:event/type :spring-lobby/add-task
                      :task {:spring-lobby/task-type :spring-lobby/refresh-maps}}
     :refresh-in-progress (seq map-update-tasks)
     :issues
     (concat
       (let [severity (cond
                        no-map-details
                        (if indexed-map
                          -1 2)
                        :else 0)]
         [{:severity severity
           :text "info"
           :human-text map-name
           :tooltip (if (zero? severity)
                      (fs/canonical-path (:file map-details))
                      (if indexed-map
                        (str "Loading map details for '" map-name "'")
                        (str "Map '" map-name "' not found locally")))}])
       (when (and no-map-details at-max-tries (empty? map-update-tasks))
         [{:severity 2
           :text "retry"
           :human-text "Max tries exceeded, click to retry"
           :tooltip "Attempt to reload map details"
           :action
           {:event/type :spring-lobby/add-task
            :task
            {:spring-lobby/task-type :spring-lobby/map-details
             :map-name map-name
             :map-file (:file indexed-map)
             :tries 0}}}])
       (when (and no-map-details (not indexed-map))
         (concat
           (let [
                 http-download-tasks (->> (get tasks-by-type :spring-lobby/http-downloadable)
                                          (map (comp :download-url :downloadable))
                                          set)
                 downloadables (->> downloadables-by-url
                                    vals
                                    (filter (comp #{:spring-lobby/map} :resource-type))
                                    (filter (partial resource/could-be-this-map? map-name)))]
             (if (seq downloadables)
               (map
                 (fn [downloadable]
                   (let [
                         url (:download-url downloadable)
                         download (get http-download url)
                         in-progress (or (:running download) (contains? http-download-tasks url))
                         dest (resource/resource-dest spring-isolation-dir downloadable)
                         dest-exists (fs/file-exists? file-cache dest)
                         severity (if dest-exists -1 2)]
                     {:severity severity
                      :text "download"
                      :human-text (if in-progress
                                    (u/download-progress download)
                                    (if downloadable
                                      (if dest-exists
                                        (str "Downloaded " (fs/filename dest))
                                        (str "Download from " (:download-source-name downloadable)))
                                      (str "No download for " map-name)))
                      :tooltip (if in-progress
                                 (str "Downloading " (u/download-progress download))
                                 (if dest-exists
                                   (str "Downloaded to " (fs/canonical-path dest))
                                   (str "Download " url)))
                      :in-progress in-progress
                      :action (when (and downloadable (not dest-exists))
                                {:event/type :spring-lobby/add-task
                                 :task
                                 {:spring-lobby/task-type :spring-lobby/http-downloadable
                                  :downloadable downloadable
                                  :spring-isolation-dir spring-isolation-dir}})}))
                 downloadables)
               (let [
                     springfiles-download-tasks (->> (get tasks-by-type :spring-lobby/download-springfiles)
                                                     (map :springname)
                                                     set)
                     springname map-name
                     springfiles-searched (contains? springfiles-search-results springname)
                     springfiles-search-result (get springfiles-search-results springname)
                     springfiles-mirror-set (set (:mirrors springfiles-search-result))
                     dest-exists (some
                                   (fn [url]
                                     (let [filename (http/filename url)
                                           dest (resource/resource-dest spring-isolation-dir {:resource-filename filename
                                                                                              :resource-type :spring-lobby/map})]
                                       (when (fs/file-exists? file-cache dest)
                                         dest)))
                                   springfiles-mirror-set)
                     springfiles-download (->> http-download
                                               (filter (comp springfiles-mirror-set first))
                                               first
                                               second)
                     springfiles-in-progress (or (:running springfiles-download)
                                                 (some springfiles-mirror-set http-download-tasks)
                                                 (contains? springfiles-download-tasks map-name))
                     severity (if dest-exists -1 2)]
                 (when springname
                   [{:severity severity
                     :text "springfiles"
                     :human-text (if springfiles-in-progress
                                   (u/download-progress springfiles-download)
                                   (if springfiles-searched
                                     (if springfiles-search-result
                                       (if dest-exists
                                         (str "Downloaded " (fs/filename dest-exists))
                                         "Download from springfiles")
                                       "Not found on springfiles")
                                     "Search springfiles"))
                     :tooltip (when dest-exists
                                (str "Downloaded to " (fs/canonical-path dest-exists)))
                     :in-progress springfiles-in-progress
                     :action
                     (when (or (not springfiles-searched)
                               (and springfiles-search-result (not dest-exists)))
                       {:event/type :spring-lobby/add-task
                        :task
                        (if springfiles-searched
                          {:spring-lobby/task-type :spring-lobby/download-springfiles
                           :resource-type :spring-lobby/map
                           :search-result springfiles-search-result
                           :springname springname
                           :spring-isolation-dir spring-isolation-dir}
                          {:spring-lobby/task-type :spring-lobby/search-springfiles
                           :springname springname})})}]))))
           (let [importable (some->> importables-by-path
                                     vals
                                     (filter (comp #{:spring-lobby/map} :resource-type))
                                     (filter (partial resource/could-be-this-map? map-name))
                                     first)
                 resource-file (:resource-file importable)
                 resource-path (fs/canonical-path resource-file)
                 in-progress (boolean
                               (or (-> copying (get resource-path) :status boolean)
                                   (contains? imports resource-path)))
                 dest (resource/resource-dest spring-isolation-dir importable)
                 dest-exists (fs/file-exists? file-cache dest)]
             [{:severity (if dest-exists -1 2)
               :text "import"
               :human-text (if importable
                             (str "Import from " (:import-source-name importable))
                             "No import found")
               :tooltip (if importable
                          (str "Copy map archive from " resource-path)
                          (str "No local import found for map " map-name))
               :in-progress in-progress
               :action
               (when importable
                 (if (not (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir importable)))
                   {:event/type :spring-lobby/add-task
                    :task
                    {:spring-lobby/task-type :spring-lobby/import
                     :importable importable
                     :spring-isolation-dir spring-isolation-dir}}
                   {:event/type :spring-lobby/add-task
                    :task
                    {:spring-lobby/task-type :spring-lobby/update-file-cache
                     :file (resource/resource-dest spring-isolation-dir importable)}}))}]))))}))

(defn map-sync-pane [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :map-sync-pane
      (map-sync-pane-impl state))))
