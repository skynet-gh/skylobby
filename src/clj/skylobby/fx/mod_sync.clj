(ns skylobby.fx.mod-sync
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.download :refer [download-sources-by-name]]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.sync :refer [sync-pane]]
    [skylobby.resource :as resource]
    [spring-lobby.fs :as fs]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def no-springfiles
  [#"Beyond All Reason"
   #"Total Atomization Prime"
   #"Tech Annihilation"])

(def no-rapid
  [#"Evolution RTS"
   #"Total Atomization Prime"])


(defn mod-download-source [mod-name]
  (cond
    (string/blank? mod-name) nil
    (string/includes? mod-name "Total Atomization Prime") "TAP GitHub releases"
    :else nil))


(defn- mod-sync-pane-impl
  [{:fx/keys [context]
    :keys [battle-modname engine-version mod-name spring-isolation-dir]}]
  (let [copying (fx/sub-val context :copying)
        downloadables-by-url (fx/sub-val context :downloadables-by-url)
        file-cache (fx/sub-val context :file-cache)
        http-download (fx/sub-val context :http-download)
        importables-by-path (fx/sub-val context :importables-by-path)
        rapid-data-by-version (fx/sub-val context :rapid-data-by-version)
        rapid-download (fx/sub-val context :rapid-download)
        springfiles-search-results (fx/sub-val context :springfiles-search-results)
        tasks-by-type (fx/sub-ctx context skylobby.fx/tasks-by-type-sub)
        mod-name (or mod-name battle-modname)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-isolation-dir mod-name)
        mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        no-mod-details (not (resource/details? mod-details))
        refresh-mods-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/refresh-mods)
        mod-details-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/mod-details)
        update-download-sources (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/update-downloadables)
                                     (map :download-source-name)
                                     set)
        mod-update-tasks (concat refresh-mods-tasks mod-details-tasks)
        rapid-tasks-by-id (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/rapid-download)
                               (map (juxt :rapid-id identity))
                               (into {}))
        mod-file (:file mod-details)
        engine-details (fx/sub-ctx context sub/indexed-engine spring-isolation-dir engine-version)
        engine-file (:file engine-details)
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
                      :task {:spring-lobby/task-type :spring-lobby/refresh-mods}}
     :refresh-in-progress (seq mod-update-tasks)
     :issues
     (concat
       (let [severity (if no-mod-details
                        (if indexed-mod
                          -1 2)
                        (if (= mod-name
                               (:mod-name indexed-mod))
                          0 1))]
         [{:severity severity
           :text "info"
           :human-text mod-name
           :tooltip (if (zero? severity)
                      canonical-path
                      (if indexed-mod
                        (str "Loading mod details for '" mod-name "'")
                        (str "Game '" mod-name "' not found locally")))}])
       (when (and no-mod-details (not indexed-mod))
         (concat
           (let [downloadable (->> downloadables-by-url
                                   vals
                                   (filter (comp #{:spring-lobby/mod} :resource-type))
                                   (filter (partial resource/could-be-this-mod? mod-name))
                                   first)
                 download-url (:download-url downloadable)
                 download (get http-download download-url)
                 possible-source-name (mod-download-source mod-name)
                 in-progress (if downloadable
                               (or (:running download)
                                   (contains? download-tasks-by-url download-url))
                               (get update-download-sources possible-source-name))
                 {:keys [download-source-name download-url]} downloadable
                 dest (resource/resource-dest spring-isolation-dir downloadable)
                 dest-path (fs/canonical-path dest)
                 dest-exists (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir downloadable))
                 springname mod-name
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
             (if (or downloadable possible-source-name)
               [{:severity (if dest-exists -1 2)
                 :text "download"
                 :human-text (if in-progress
                               (if downloadable
                                 (u/download-progress download)
                                 (str "Refreshing " possible-source-name))
                               (if no-mod-details
                                 (if downloadable
                                   (str "Download from " download-source-name)
                                   (if possible-source-name
                                     (str "Update download source " possible-source-name)
                                     (str "No download for " mod-name)))
                                 (:mod-name mod-details)))
                 :in-progress in-progress
                 :tooltip (if in-progress
                            (if downloadable
                              (str "Downloading " (u/download-progress download))
                              (str "Refreshing " possible-source-name))
                            (if dest-exists
                              (str "Downloaded to " dest-path)
                              (if downloadable
                                (str "Download from " download-source-name " at " download-url)
                                (if possible-source-name
                                  (str "Update download source " possible-source-name)
                                  (str "No http download found for " mod-name)))))
                 :action
                 (when-not dest-exists
                   (if downloadable
                     {:event/type :spring-lobby/add-task
                      :task
                      {:spring-lobby/task-type :spring-lobby/http-downloadable
                       :downloadable downloadable
                       :spring-isolation-dir spring-isolation-dir}}
                     {:event/type :spring-lobby/add-task
                      :task
                      (merge
                        {:spring-lobby/task-type :spring-lobby/update-downloadables
                         :force true}
                        (get download-sources-by-name possible-source-name))}))}]
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
           (when (and mod-name (not (some #(re-find % mod-name) no-rapid)))
             (let [rapid-data (get rapid-data-by-version mod-name)
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
                                   (contains? rapid-tasks rapid-id)
                                   (and package-exists
                                        (seq mod-update-tasks)))]
               [{:severity 2
                 :text "rapid"
                 :human-text
                             (if engine-file
                               (if rapid-update-tasks
                                 "Updating rapid packages..."
                                 (if rapid-id
                                   (if in-progress
                                     (str (u/download-progress rapid-download))
                                     (str "Download rapid " rapid-id))
                                   "No rapid download found, update packages"))
                               "Needs engine first to download with rapid")
                 :tooltip (if rapid-id
                            (if engine-file
                              (if package-exists
                                (str sdp-file)
                                (str "Use rapid downloader to get resource id " rapid-id
                                     " using engine " (:engine-version engine-details)))
                              "Rapid requires an engine to work, get engine first")
                            (str "No rapid download found for " mod-name))
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
                           :mod-name mod-name
                           :spring-isolation-dir spring-isolation-dir}})}]))
           (let [importable (some->> importables-by-path
                                     vals
                                     (filter (comp #{:spring-lobby/mod} :resource-type))
                                     (filter (partial resource/could-be-this-mod? mod-name))
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
                          (str "No local import found for " mod-name))
               :in-progress (get copying resource-file)
               :action (when importable
                         {:event/type :spring-lobby/add-task
                          :task
                          {:spring-lobby/task-type :spring-lobby/import
                           :importable importable
                           :spring-isolation-dir spring-isolation-dir}})}])))
       (when (= :directory
                (::fs/source indexed-mod))
         (let [battle-mod-git-ref (u/mod-git-ref mod-name)
               severity (if (= mod-name
                               (:mod-name indexed-mod))
                          0 1)
               in-progress (->> (get tasks-by-type :spring-lobby/git-mod)
                                (filter (comp #{(fs/canonical-path mod-file)} fs/canonical-path :file))
                                seq
                                boolean)]
           (concat
             [{:severity severity
               :human-text (str "You have " (:mod-name indexed-mod))}
              (merge
                {:severity severity
                 :text "git"
                 :in-progress (or in-progress (seq mod-update-tasks))}
                (if (= battle-mod-git-ref "$VERSION")
                  ; unspecified git commit ^
                  {:human-text (str "Unspecified git ref " battle-mod-git-ref)
                   :tooltip (str "SpringLobby does not specify version, "
                                 "yours may not be compatible")}
                  {:human-text (if (zero? severity)
                                 (str "git at ref " battle-mod-git-ref)
                                 (if in-progress
                                   (str "Resetting " (fs/filename (:file mod-details))
                                        " git to ref " battle-mod-git-ref)
                                   (str "Reset " (fs/filename (:file mod-details))
                                        " git to ref " battle-mod-git-ref)))
                   :action
                   {:event/type :spring-lobby/add-task
                    :task
                    {:spring-lobby/task-type :spring-lobby/git-mod
                     :file mod-file
                     :battle-mod-git-ref battle-mod-git-ref}}}))]
             (when (and (not (zero? severity))
                        (not= battle-mod-git-ref "$VERSION"))
               [(merge
                  {:severity 1
                   :text "rehost"
                   :human-text "Or rehost to change game version"
                   :tooltip (str "Leave battle and host again to use game "
                                 (:mod-name mod-details))})])))))}))

(defn mod-sync-pane [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :mod-sync-pane
      (mod-sync-pane-impl state))))
