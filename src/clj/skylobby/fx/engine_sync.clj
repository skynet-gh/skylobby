(ns skylobby.fx.engine-sync
  (:require
    [clojure.java.io :as io]
    [skylobby.fx.sync :refer [sync-pane]]
    [skylobby.resource :as resource]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]))


(defn engine-sync-pane
  [{:keys [copying downloadables-by-url engine-details engine-file engine-update-tasks engine-version extract-tasks extracting file-cache http-download importables-by-path spring-isolation-dir update-engines]}]
  {:fx/type sync-pane
   :h-box/margin 8
   :resource "Engine"
   :refresh-action {:event/type :spring-lobby/add-task
                    :task {:spring-lobby/task-type :spring-lobby/reconcile-engines}}
   :refresh-in-progress (or (seq engine-update-tasks) update-engines)
   :browse-action {:event/type :spring-lobby/desktop-browse-dir
                   :file (or engine-file
                             (fs/engines-dir spring-isolation-dir))}
   :issues
   (concat
     (let [severity (if engine-details
                      0
                      (if (or (seq engine-update-tasks) update-engines)
                        -1 2))]
       [{:severity severity
         :text "info"
         :human-text (str "Spring " engine-version)
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
             in-progress (:running download)
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
                           (u/download-progress download)
                           (if downloadable
                             (if dest-exists
                               (str "Downloaded " (fs/filename dest))
                               (str "Download from " (:download-source-name downloadable)))
                             "No download found"))
             :tooltip (if in-progress
                        (str "Downloading " (u/download-progress download))
                        (if dest-exists
                          (str "Downloaded to " dest-path)
                          (str "Download " url)))
             :in-progress in-progress
             :action (when (and downloadable (not dest-exists))
                       {:event/type :spring-lobby/http-downloadable
                        :downloadable downloadable
                        :spring-isolation-dir spring-isolation-dir})}]
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
               :spring-isolation-dir spring-isolation-dir}})}])))})
