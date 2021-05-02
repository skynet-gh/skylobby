(ns skylobby.fx.mod-sync
  (:require
    [skylobby.fx.sync :refer [sync-pane]]
    [skylobby.resource :as resource]
    [spring-lobby.fs :as fs]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.util :as u]))


(def no-springfiles
  [#"Beyond All Reason"])


(defn mod-sync-pane
  [{:keys [battle-modname battle-mod-details copying downloadables-by-url engine-details engine-file
           file-cache gitting http-download importables-by-path indexed-mod mod-update-tasks
           rapid-data-by-version rapid-download rapid-tasks-by-id spring-isolation-dir
           springfiles-search-results tasks-by-type]}]
  (let [no-mod-details (not (resource/details? battle-mod-details))
        mod-file (:file battle-mod-details)
        canonical-path (fs/canonical-path mod-file)
        download-tasks-by-url (->> (get tasks-by-type :spring-lobby/http-downloadable)
                                   (map (juxt (comp :download-url :downloadable) identity))
                                   (into {}))]
    {:fx/type sync-pane
     :h-box/margin 8
     :resource "Game"
     :browse-action {:event/type :spring-lobby/desktop-browse-dir
                     :file (fs/mods-dir spring-isolation-dir)}
     :refresh-action {:event/type :spring-lobby/add-task
                      :task {:spring-lobby/task-type :spring-lobby/reconcile-mods}}
     :refresh-in-progress mod-update-tasks
     :issues
     (concat
       (let [severity (cond
                        no-mod-details
                        (if indexed-mod
                          -1 2)
                        :else 0)]
         [{:severity severity
           :text "info"
           :human-text battle-modname
           :tooltip (if (zero? severity)
                      canonical-path
                      (if indexed-mod
                        (str "Loading mod details for '" battle-modname "'")
                        (str "Game '" battle-modname "' not found locally")))}])
       (when (and no-mod-details (not indexed-mod))
         (concat
           (let [downloadable (->> downloadables-by-url
                                   vals
                                   (filter (comp #{:spring-lobby/mod} :resource-type))
                                   (filter (partial resource/could-be-this-mod? battle-modname))
                                   first)
                 download-url (:download-url downloadable)
                 download (get http-download download-url)
                 in-progress (or (:running download)
                                 (contains? download-tasks-by-url download-url))
                 {:keys [download-source-name download-url]} downloadable
                 file-exists (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir downloadable))
                 springname battle-modname
                 springfiles-searched (contains? springfiles-search-results springname)
                 springfiles-search-result (get springfiles-search-results springname)
                 springfiles-mirror-set (set (:mirrors springfiles-search-result))
                 springfiles-download (->> http-download
                                           (filter (comp springfiles-mirror-set first))
                                           first
                                           second)
                 springfiles-in-progress (or (:running springfiles-download)
                                             (->> download-tasks-by-url
                                                  keys
                                                  (filter springfiles-mirror-set)
                                                  seq))]
             (if downloadable
               [{:severity (if file-exists -1 2)
                 :text "download"
                 :human-text (if in-progress
                               (u/download-progress download)
                               (if no-mod-details
                                 (if downloadable
                                   (str "Download from " download-source-name)
                                   (str "No download for " battle-modname))
                                 (:mod-name battle-mod-details)))
                 :in-progress in-progress
                 :tooltip (if downloadable
                            (str "Download from " download-source-name " at " download-url)
                            (str "No http download found for " battle-modname))
                 :action
                 (when downloadable
                   {:event/type :spring-lobby/add-task
                    :task
                    {:spring-lobby/task-type :spring-lobby/http-downloadable
                     :downloadable downloadable
                     :spring-isolation-dir spring-isolation-dir}})}]
               (when (and springname (not (some #(re-find % springname) no-springfiles)))
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
                         :resource-type :spring-lobby/mod
                         :search-result springfiles-search-result
                         :springname springname
                         :spring-isolation-dir spring-isolation-dir}
                        {:spring-lobby/task-type :spring-lobby/search-springfiles
                         :springname springname})})}])))
           (let [rapid-data (get rapid-data-by-version battle-modname)
                 rapid-id (:id rapid-data)
                 rapid-download (get rapid-download rapid-id)
                 running (some? (get rapid-tasks-by-id rapid-id))
                 sdp-file (rapid/sdp-file spring-isolation-dir (str (:hash rapid-data) ".sdp"))
                 package-exists (fs/file-exists? file-cache sdp-file)
                 rapid-tasks (->> (get tasks-by-type :spring-lobby/rapid-download)
                                  (map :rapid-id)
                                  set)
                 rapid-update-tasks (->> tasks-by-type
                                         (filter (comp #{:spring-lobby/update-rapid-packages :spring-lobby/update-rapid} first))
                                         (mapcat second)
                                         seq)
                 in-progress (or running
                                 rapid-update-tasks
                                 (contains? rapid-tasks rapid-id))]
             [{:severity 2
               :text "rapid"
               :human-text (if rapid-id
                             (if engine-file
                               (cond
                                 rapid-update-tasks "Rapid updating..."
                                 in-progress (str (u/download-progress rapid-download))
                                 :else (str "Download rapid " rapid-id))
                               "Needs engine first to download with rapid")
                             (if rapid-update-tasks
                               "Rapid updating..."
                               (if engine-file
                                 "No rapid download, update rapid"
                                 "Needs engine first to download with rapid")))
               :tooltip (if rapid-id
                          (if engine-file
                            (if package-exists
                              (str sdp-file)
                              (str "Use rapid downloader to get resource id " rapid-id
                                   " using engine " (:engine-version engine-details)))
                            "Rapid requires an engine to work, get engine first")
                          (str "No rapid download found for" battle-modname))
               :in-progress in-progress
               :action
               (cond
                 (not engine-file) nil
                 (and rapid-id engine-file)
                 {:event/type :spring-lobby/add-task
                  :task
                  {:spring-lobby/task-type :spring-lobby/rapid-download
                   :rapid-id rapid-id
                   :engine-file engine-file
                   :spring-isolation-dir spring-isolation-dir}}
                 package-exists
                 {:event/type :spring-lobby/add-task
                  :task
                  {:spring-lobby/task-type :spring-lobby/update-file-cache
                   :file sdp-file}}
                 :else
                 {:event/type :spring-lobby/add-task
                  :task {:spring-lobby/task-type :spring-lobby/update-rapid
                         :engine-version (:engine-version engine-details)
                         :force true
                         :mod-name battle-modname
                         :spring-isolation-dir spring-isolation-dir}})}])
           (let [importable (some->> importables-by-path
                                     vals
                                     (filter (comp #{:spring-lobby/mod} :resource-type))
                                     (filter (partial resource/could-be-this-mod? battle-modname))
                                     first)
                 resource-file (:resource-file importable)
                 dest (resource/resource-dest spring-isolation-dir importable)
                 dest-exists (fs/file-exists? file-cache dest)]
             [{:severity (if dest-exists -1 2)
               :text "import"
               :human-text (if importable
                             (str "Import from " (:import-source-name importable))
                             "No import found")
               :tooltip (if importable
                          (str "Copy game from " (:import-source-name importable)
                               " at " resource-file)
                          (str "No local import found for " battle-modname))
               :in-progress (get copying resource-file)
               :action (when importable
                         {:event/type :spring-lobby/add-task
                          :task
                          {:spring-lobby/task-type :spring-lobby/import
                           :importable importable
                           :spring-isolation-dir spring-isolation-dir}})}])))
       (when (and (= :directory
                     (::fs/source battle-mod-details)))
         (let [battle-mod-git-ref (u/mod-git-ref battle-modname)
               severity (if (= battle-modname
                               (:mod-name battle-mod-details))
                          0 1)]
           (concat
             [(merge
                {:severity severity
                 :text "git"
                 :in-progress (-> gitting (get canonical-path) :status)}
                (if (= battle-mod-git-ref "$VERSION")
                  ; unspecified git commit ^
                  {:human-text (str "Unspecified git ref " battle-mod-git-ref)
                   :tooltip (str "SpringLobby does not specify version, "
                                 "yours may not be compatible")}
                  {:human-text (if (zero? severity)
                                 (str "git at ref " battle-mod-git-ref)
                                 (str "Reset " (fs/filename (:file battle-mod-details))
                                      " git to ref " battle-mod-git-ref))
                   :action
                   {:event/type :spring-lobby/git-mod
                    :file mod-file
                    :battle-mod-git-ref battle-mod-git-ref}}))]
             (when (and (not (zero? severity))
                        (not= battle-mod-git-ref "$VERSION"))
               [(merge
                  {:severity 1
                   :text "rehost"
                   :human-text "Or rehost to change game version"
                   :tooltip (str "Leave battle and host again to use game "
                                 (:mod-name battle-mod-details))})])))))}))
