(ns skylobby.fx.map-sync
  (:require
    [skylobby.fx.sync :refer [sync-pane]]
    [skylobby.resource :as resource]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]))


(defn map-sync-pane
  [{:keys [battle-map battle-map-details copying downloadables-by-url file-cache http-download
           import-tasks importables-by-path map-update-tasks maps spring-isolation-dir update-maps]}]
  (let [
        no-map-details (not (seq battle-map-details))
        map-exists (some (comp #{battle-map} :map-name) maps)]
    {:fx/type sync-pane
     :h-box/margin 8
     :resource "Map"
     :browse-action {:event/type :spring-lobby/desktop-browse-dir
                     :file (fs/maps-dir spring-isolation-dir)}
     :refresh-action {:event/type :spring-lobby/add-task
                      :task {:spring-lobby/task-type :spring-lobby/reconcile-maps}}
     :refresh-in-progress (or (seq map-update-tasks) update-maps)
     :issues
     (concat
       (let [severity (cond
                        no-map-details
                        (if map-exists
                          -1 2)
                        :else 0)]
         [{:severity severity
           :text "info"
           :human-text battle-map
           :tooltip (if (zero? severity)
                      (fs/canonical-path (:file battle-map-details))
                      (str "Map '" battle-map "' not found locally"))}])
       (when (and no-map-details (not map-exists))
         (concat
           (let [downloadable (->> downloadables-by-url
                                   vals
                                   (filter (comp #{:spring-lobby/map} :resource-type))
                                   (filter (partial resource/could-be-this-map? battle-map))
                                   first)
                 url (:download-url downloadable)
                 download (get http-download url)
                 in-progress (:running download)
                 dest (resource/resource-dest spring-isolation-dir downloadable)
                 dest-exists (fs/file-exists? file-cache dest)
                 severity (if dest-exists -1 2)]
             [{:severity severity
               :text "download"
               :human-text (if in-progress
                             (u/download-progress download)
                             (if downloadable
                               (if dest-exists
                                 (str "Downloaded " (fs/filename dest))
                                 (str "Download from " (:download-source-name downloadable)))
                               (str "No download for " battle-map)))
               :tooltip (if in-progress
                          (str "Downloading " (u/download-progress download))
                          (if dest-exists
                            (str "Downloaded to " (fs/canonical-path dest))
                            (str "Download " url)))
               :in-progress in-progress
               :action (when (and downloadable (not dest-exists))
                         {:event/type :spring-lobby/http-downloadable
                          :downloadable downloadable
                          :spring-isolation-dir spring-isolation-dir})}])
           (let [importable (some->> importables-by-path
                                     vals
                                     (filter (comp #{:spring-lobby/map} :resource-type))
                                     (filter (partial resource/could-be-this-map? battle-map))
                                     first)
                 resource-file (:resource-file importable)
                 resource-path (fs/canonical-path resource-file)
                 in-progress (boolean
                               (or (-> copying (get resource-path) :status boolean)
                                   (contains? import-tasks resource-path)))
                 dest (resource/resource-dest spring-isolation-dir importable)
                 dest-exists (fs/file-exists? file-cache dest)]
             [{:severity (if dest-exists -1 2)
               :text "import"
               :human-text (if importable
                             (str "Import from " (:import-source-name importable))
                             "No import found")
               :tooltip (if importable
                          (str "Copy map archive from " resource-path)
                          (str "No local import found for map " battle-map))
               :in-progress in-progress
               :action
               (when (and importable
                          (not (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir importable))))
                 {:event/type :spring-lobby/add-task
                  :task
                  {:spring-lobby/task-type :spring-lobby/import
                   :importable importable
                   :spring-isolation-dir spring-isolation-dir}})}]))))}))
