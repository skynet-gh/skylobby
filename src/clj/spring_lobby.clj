(ns spring-lobby
  (:require
    [chime.core :as chime]
    [clj-http.client :as clj-http]
    [cljfx.api :as fx]
    [cljfx.component :as fx.component]
    [cljfx.ext.node :as fx.ext.node]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [cljfx.lifecycle :as fx.lifecycle]
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.set]
    [clojure.string :as string]
    [com.evocomputing.colors :as colors]
    [crouton.html :as html]
    [hawk.core :as hawk]
    java-time
    [me.raynes.fs :as raynes-fs]
    [shams.priority-queue :as pq]
    [spring-lobby.battle :as battle]
    [spring-lobby.client :as client]
    [spring-lobby.client.message :as message]
    [spring-lobby.fs :as fs]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.git :as git]
    [spring-lobby.http :as http]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [version-clj.core :as version])
  (:import
    (java.awt Desktop)
    (javafx.application Platform)
    (javafx.embed.swing SwingFXUtils)
    (javafx.scene.input KeyCode)
    (javafx.scene.paint Color)
    (manifold.stream SplicedStream)
    (org.apache.commons.io.input CountingInputStream))
  (:gen-class))


(set! *warn-on-reflection* true)


(def stylesheets
  [(str (io/resource "dark.css"))])

(def main-window-width 1920)
(def main-window-height 1000)

(def download-window-width 1600)
(def download-window-height 800)

(def battle-window-width 1600)
(def battle-window-height 800)

(def start-pos-r 10.0)

(def minimap-size 512)


(defn slurp-app-edn
  "Returns data loaded from a .edn file in this application's root directory."
  [edn-filename]
  (try
    (let [config-file (io/file (fs/app-root) edn-filename)]
      (when (.exists config-file)
        (-> config-file slurp edn/read-string)))
    (catch Exception e
      (log/warn e "Exception loading app edn file" edn-filename))))


(defn initial-tasks []
  (pq/priority-queue (constantly 1) :variant :set))

(defn initial-file-events []
  (clojure.lang.PersistentQueue/EMPTY))


(defn initial-state []
  (merge
    (slurp-app-edn "config.edn")
    (slurp-app-edn "maps.edn")
    (slurp-app-edn "engines.edn")
    (slurp-app-edn "mods.edn")
    (slurp-app-edn "download.edn")
    {:file-events (initial-file-events)
     :tasks (initial-tasks)
     :minimap-type "minimap"}))


(def ^:dynamic *state
  (atom (initial-state)))


(defn spit-app-edn
  "Writes the given data as edn to the given file in the application directory."
  [data filename]
  (let [app-root (io/file (fs/app-root))
        file (io/file app-root filename)]
    (when-not (.exists app-root)
      (.mkdirs app-root))
    (spit file (with-out-str (pprint data)))))


(defn add-watch-state-to-edn
  "Adds a watcher to *state that writes the data, returned by applying filter-fn, when that data
  changes, to output-filename in the app directory."
  [state-atom watcher-kw filter-fn output-filename]
  (add-watch state-atom
    watcher-kw
    (fn [_k _ref old-state new-state]
      (try
        (let [old-data (filter-fn old-state)
              new-data (filter-fn new-state)]
          (when (not= old-data new-data)
            (log/debug "Updating" output-filename)
            (spit-app-edn new-data output-filename)))
        (catch Exception e
          (log/error e "Error in" watcher-kw "state watcher"))))))


(def config-keys
  [:username :password :server-url :engine-version :mod-name :map-name
   :battle-title :battle-password
   :bot-username :bot-name :bot-version
   :scripttags :preferred-color])


(defn select-config [state]
  (select-keys state config-keys))

(defn select-maps [state]
  (select-keys state [:maps]))

(defn select-engines [state]
  (select-keys state [:engines]))

(defn select-mods [state]
  (select-keys state [:mods]))

(defn select-download [state]
  (select-keys state
    [:engine-versions-cache :map-files-cache
     :engine-branch :maps-index-url :rapid-repo :mods-index-url]))

(defn select-importables [state]
  (select-keys state
    [:importables-by-path]))


(defn safe-read-map-cache [map-name]
  (log/info "Reading map cache for" (str "'" map-name "'"))
  (try
    (when-let [map-file (some->> *state deref :maps
                                 (filter (comp #{map-name} :map-name))
                                 first
                                 :filename
                                 fs/map-file)]
      (fs/read-map-data map-file))
    (catch Exception e
      (log/warn e "Error loading map cache for" (str "'" map-name "'")))))

(def ^java.io.File mods-cache-root
  (io/file (fs/app-root) "mods-cache"))

(defn mod-cache-file [mod-name]
  (io/file mods-cache-root (str mod-name ".edn")))

(defn read-mod-data
  ([f]
   (read-mod-data f nil))
  ([f opts]
   (let [mod-data
         (if (string/ends-with? (.getName f) ".sdp")
           (rapid/read-sdp-mod f opts)
           (fs/read-mod-file f opts))
         mod-name (spring/mod-name mod-data)]
     (assoc mod-data :mod-name mod-name))))

#_
(read-mod-data (io/file "/mnt/c/Users/craig/AppData/Local/Programs/Beyond-All-Reason/data/packages/ce1411570bf8ed64222a1ee241a22234.sdp"))

(defn update-mod [state-atom file]
  (let [mod-data (read-mod-data file {:modinfo-only false})
        mod-name (:mod-name mod-data)
        cache-file (mod-cache-file mod-name)
        mod-details (select-keys mod-data [:filename :absolute-path :mod-name ::fs/source
                                           :git-commit-id])]
    (log/info "Caching" mod-name "to" cache-file)
    (spit cache-file (with-out-str (pprint mod-data)))
    (let [state @state-atom
          battle-id (-> state :battle :battle-id)
          battle-modname (-> state :battles (get battle-id) :battle-modname)
          battle-mod-details (:battle-mod-details state)]
      (when (or (= (:absolute-path mod-data) (:absolute-path battle-mod-details))
                (= (:mod-name mod-details) battle-modname)) ; can this ever happen?
        (log/info "Updating battle mod details")
        (swap! state-atom assoc :battle-mod-details mod-data))) ; TODO avoid dupe swap
    (swap! state-atom update :mods
           (fn [mods]
             (-> (remove (comp #{(:absolute-path mod-details)} :absolute-path) mods)
                 (conj mod-details)
                 set)))
    mod-data))


(defn- parse-mod-name-git [mod-name]
  (or (re-find #"(.+)\s([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\sgit:([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\s(\$VERSION)$" mod-name)))

(defn mod-name-sans-git [mod-name]
  (when mod-name
    (if-let [[_all mod-prefix _git] (parse-mod-name-git mod-name)]
      mod-prefix
      mod-name)))

(defn mod-git-ref
  "Returns the git ref from the given mod name, or nil if it does not parse."
  [mod-name]
  (when-let [[_all _mod-prefix git] (parse-mod-name-git mod-name)]
    git))

#_
(re-find #"(.+)\s([0-9a-f]+)$" "Beyond All Reason $VERSION")
#_
(re-find #"(.+)\s(\$VERSION)$" "Beyond All Reason $VERSION")
#_
(mod-name-sans-git "Beyond All Reason $VERSION")


(defn force-update-battle-mod
  ([]
   (force-update-battle-mod *state))
  ([state-atom]
   (let [{:keys [battle battles mods]} @state-atom
         battle-id (:battle-id battle)
         battle-modname (-> battles (get battle-id) :battle-modname)
         _ (log/debug "Force updating battle mod details for" battle-modname)
         battle-modname-sans-git (mod-name-sans-git battle-modname)
         mod-name-set (set [battle-modname battle-modname-sans-git])
         filter-fn (comp mod-name-set mod-name-sans-git :mod-name)
         mod-details (some->> mods
                              (filter filter-fn)
                              first
                              :absolute-path
                              io/file
                              read-mod-data)]
     (swap! *state assoc :battle-mod-details mod-details)
     mod-details)))

(defn add-watchers
  "Adds all *state watchers."
  [state-atom]
  (remove-watch state-atom :config)
  (remove-watch state-atom :maps)
  (remove-watch state-atom :engines)
  (remove-watch state-atom :mods)
  (remove-watch state-atom :download)
  (remove-watch state-atom :importables)
  (remove-watch state-atom :battle-map-details)
  (remove-watch state-atom :battle-mod-details)
  (remove-watch state-atom :fix-missing-resource)
  (add-watch-state-to-edn state-atom :config select-config "config.edn")
  (add-watch-state-to-edn state-atom :maps select-maps "maps.edn")
  (add-watch-state-to-edn state-atom :engines select-engines "engines.edn")
  (add-watch-state-to-edn state-atom :mods select-mods "mods.edn")
  (add-watch-state-to-edn state-atom :download select-download "download.edn")
  (add-watch-state-to-edn state-atom :importables select-importables "importables.edn")
  (add-watch state-atom :battle-map-details
    (fn [_k _ref old-state new-state]
      (try
        (let [old-battle-id (-> old-state :battle :battle-id)
              new-battle-id (-> new-state :battle :battle-id)
              old-battle-map (-> old-state :battles (get old-battle-id) :battle-map)
              new-battle-map (-> new-state :battles (get new-battle-id) :battle-map)]
          (when (or (not= old-battle-id new-battle-id)
                    (not= old-battle-map new-battle-map)
                    (and new-battle-map
                         (not (:battle-map-details new-state))
                         (->> new-state :maps (filter (comp #{new-battle-map} :map-name)) first)))
            (log/debug "Updating battle map details for" new-battle-map
                       "was" old-battle-map)
            (let [map-details (safe-read-map-cache new-battle-map)]
              (swap! *state assoc :battle-map-details map-details))))
        (catch Exception e
          (log/error e "Error in :battle-map-details state watcher")))))
  (add-watch state-atom :battle-mod-details
    (fn [_k _ref old-state new-state]
      (try
        (let [old-battle-id (-> old-state :battle :battle-id)
              new-battle-id (-> new-state :battle :battle-id)
              old-battle-mod (-> old-state :battles (get old-battle-id) :battle-modname)
              new-battle-mod (-> new-state :battles (get new-battle-id) :battle-modname)
              new-battle-mod-sans-git (mod-name-sans-git new-battle-mod)
              mod-name-set (set [new-battle-mod new-battle-mod-sans-git])
              filter-fn (comp mod-name-set mod-name-sans-git :mod-name)]
          (when (or (not= old-battle-id new-battle-id)
                    (not= old-battle-mod new-battle-mod)
                    (and new-battle-mod
                         (not (:battle-mod-details new-state))
                         (->> new-state :mods
                              (filter filter-fn)
                              first)))
            (log/debug "Updating battle mod details for" new-battle-mod "was" old-battle-mod)
            (when-let [mod-details (some->> new-state
                                            :mods
                                            (filter filter-fn)
                                            first
                                            :absolute-path
                                            io/file
                                            read-mod-data)]
              (swap! *state assoc :battle-mod-details mod-details))))
        (catch Exception e
          (log/error e "Error in :battle-map-details state watcher")))))
  (add-watch state-atom :fix-missing-resource
    (fn [_k _ref _old-state new-state]
      (try
        (let [{:keys [engine-version engines map-name maps mod-name mods]} new-state
              engine-fix (when engine-version
                           (when-not (->> engines
                                          (filter (comp #{engine-version} :engine-version))
                                          first)
                             (-> engines first :engine-version)))
              mod-fix (when mod-name
                        (when-not (->> mods
                                       (filter (comp #{mod-name} :mod-name))
                                       first)
                          (-> mods first :mod-name)))
              map-fix (when map-name
                        (when-not (->> maps
                                       (filter (comp #{map-name} :map-name))
                                       first)
                          (-> maps first :map-name)))]
          (when (or engine-fix mod-fix map-fix)
            (swap! state-atom
                   (fn [state]
                     (cond-> state
                       engine-fix (assoc :engine-version engine-fix)
                       mod-fix (assoc :mod-name mod-fix)
                       map-fix (assoc :map-name map-fix))))))
        (catch Exception e
          (log/error e "Error in :battle-map-details state watcher"))))))

(defn add-hawk! [state-atom]
  (log/info "Adding hawk file watcher")
  (hawk/watch!
    #_ ; TODO look into alternatives for file watching
    {:watcher :polling
     :sensitivity :low} ;:medium} ;:high} ;:low}
    [{:paths [(fs/download-dir)
              (fs/isolation-dir)
              (fs/spring-root)]
      :handler (fn [ctx e]
                 (try
                   (log/trace "Hawk event:" e)
                   (swap! state-atom update :file-events conj e)
                   (catch Exception e
                     (log/error e "Error in hawk handler"))
                   (finally
                     ctx)))}]))


(defmulti task-handler ::task-type)

(defmethod task-handler ::update-mod
  [{:keys [file]}]
  (update-mod *state file))

(defmethod task-handler :default [task]
  (when task
    (log/warn "Unknown task type" task)))


; https://www.eidel.io/2019/01/22/thread-safe-queues-clojure/
(defn handle-task! [state-atom]
  (let [[before _after] (swap-vals! state-atom update :tasks
                                    (fn [tasks]
                                      (if-not (empty? tasks)
                                        (pop tasks)
                                        tasks)))
        tasks (:tasks before)
        task (when-not (empty? tasks)
               (peek tasks))]
    (task-handler task)
    task))

(defn tasks-chimer-fn [state-atom]
  (log/info "Starting tasks chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/instant)
            (java-time/duration 1 :seconds))
          (fn [_chimestamp]
            (handle-task! state-atom))
          {:error-handler
           (fn [e]
             (log/error e "Error handling task")
             true)})]
    (fn [] (.close chimer))))

(defn handle-all-tasks! [state-atom]
  (while (handle-task! state-atom)))


(defn add-task! [state-atom task]
  (if task
    (do
      (log/info "Adding task" (pr-str task))
      (swap! state-atom update :tasks conj task))
    (log/warn "Attempt to add nil task" task)))


(def import-sources
  [{:import-source-name "Spring"
    :file (fs/spring-root)}
   {:import-source-name "Beyond All Reason"
    :file (fs/bar-root)}])

(defn file-event-task [file-event]
  (let [{:keys [file]} file-event
        root (fs/isolation-dir)
        engines (io/file root "engine")
        games (io/file root "games")
        maps (io/file root "maps")]
    (condp (fn [f x] (f x)) file
      not (log/info "no file")
      (fn [file]
        (or (fs/child? games file) ; update possible mod file
            (and (fs/child? (io/file root "packages") file)
                 (string/ends-with? (.getName file) ".sdp"))))
      {::task-type ::update-mod
       :file file}
      ; or mod in directory / git format
      (partial fs/descendant? games)
      (loop [parent file] ; find directory in games
        (when parent
          (if (fs/child? games parent)
            {::task-type ::update-mod
             :file parent}
            (recur (.getParentFile parent)))))
      (partial fs/descendant? maps)
      (loop [parent file] ; find file in maps
        (when parent
          (if (fs/child? maps parent)
            {::task-type ::update-map
             :file parent}
            (recur (.getParentFile parent)))))
      (partial fs/descendant? engines)
      (loop [parent file] ; find directory in engines
        (when parent
          (if (fs/child? maps parent)
            {::task-type ::update-engine
             :file parent}
            (recur (.getParentFile parent)))))
      (fn [file]
        (some (comp #(when (fs/descendant? % file) file) :file) import-sources))
      :>>
      (fn [import-source]
        (log/info "Looks like" file "is a descendant of import source" import-source))
      any?
      (log/warn "Nothing to do for" file-event))))

; https://www.eidel.io/2019/01/22/thread-safe-queues-clojure/
(defn pop-file-event! [state-atom]
  (let [[before _after] (swap-vals! state-atom update :file-events pop)]
    (-> before :file-events peek)))

(defn handle-all-file-events! [state-atom]
  (let [events (loop [all []]
                 (if-let [event (pop-file-event! state-atom)]
                   (recur (conj all event))
                   all))
        tasks (set (filter some? (map file-event-task events)))]
    (when (seq events)
      (log/info "From" (count events) "file events got" (count tasks) "tasks"))
    (doseq [task tasks] ; TODO all at once?
      (add-task! state-atom task))))

(defn file-events-chimer-fn [state-atom]
  (log/info "Starting file events chimer")
  (let [hawk-atom (atom (add-hawk! state-atom))
        chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/instant)
            (java-time/duration 1 :seconds))
          (fn [_chimestamp]
            (let [hawk @hawk-atom]
              (when-not (.isAlive (:thread hawk))
                (log/warn "Hawk watcher died, starting a new one")
                (hawk/stop! hawk)
                (reset! hawk-atom (add-hawk! state-atom))))
            (handle-all-file-events! state-atom))
          {:error-handler
           (fn [e]
             (log/error e "Error handling file event")
             true)})]
    (fn []
      (let [hawk @hawk-atom]
        (when hawk
          (hawk/stop! hawk)))
      (.close chimer))))


(defn reconcile-engines
  "Reads engine details and updates missing engines in :engines in state."
  ([]
   (reconcile-engines *state))
  ([state-atom]
   (let [before (u/curr-millis)
         engine-dirs (fs/engine-dirs)
         known-absolute-paths (->> state-atom deref :engines (map :absolute-path) (filter some?) set)
         to-add (remove (comp known-absolute-paths #(.getAbsolutePath ^java.io.File %)) engine-dirs)
         absolute-path-set (set (map #(.getAbsolutePath ^java.io.File %) engine-dirs))
         missing-files (set
                         (concat
                           (->> known-absolute-paths
                                (remove (comp #(.exists ^java.io.File %) io/file)))
                           (->> known-absolute-paths
                                (remove (comp (partial fs/descendant? (fs/isolation-dir)) io/file)))))
         to-remove (set
                     (concat missing-files
                             (remove absolute-path-set known-absolute-paths)))]
     (log/info "Found" (count to-add) "engines to load in" (- (u/curr-millis) before) "ms")
     (doseq [engine-dir to-add]
       (log/info "Detecting engine data for" engine-dir)
       (let [engine-data (fs/engine-data engine-dir)]
         (swap! state-atom update :engines
                (fn [engines]
                  (set (conj engines engine-data))))))
     (log/debug "Removing" (count to-remove) "engines")
     (swap! state-atom update :engines
            (fn [engines]
              (set (remove
                     (comp to-remove :absolute-path)
                     engines))))
     {:to-add-count (count to-add)
      :to-remove-count (count to-remove)})))

(defn remove-bad-mods [state-atom]
  (swap! state-atom update :mods
         (fn [mods]
           (->> mods
                (remove (comp string/blank? :mod-name))
                (filter (comp #(.exists %) io/file :absolute-path))
                set))))

(defn remove-all-duplicate-mods
  "Removes all copies of any mod that shares :absolute-path with another mod."
  [state-atom]
  (log/info "Removing duplicate mods")
  (swap! state-atom update :mods
         (fn [mods]
           (let [freqs (frequencies (map :absolute-path mods))]
             (->> mods
                  (remove (comp pos? dec freqs :absolute-path))
                  set)))))

(defn reconcile-mods
  "Reads mod details and updates missing mods in :mods in state."
  [state-atom]
  (remove-all-duplicate-mods state-atom)
  (let [before (u/curr-millis)
        mods (->> state-atom deref :mods)
        {:keys [rapid archive directory]} (group-by ::fs/source mods)
        known-file-paths (set (map :absolute-path (concat archive directory)))
        known-rapid-paths (set (map :absolute-path rapid))
        mod-files (fs/mod-files)
        sdp-files (rapid/sdp-files)
        _ (log/info "Found" (count mod-files) "files and"
                    (count sdp-files) "rapid archives to scan for mods")
        to-add-file (remove (comp known-file-paths #(.getAbsolutePath ^java.io.File %)) mod-files)
        to-add-rapid (remove (comp known-rapid-paths #(.getAbsolutePath ^java.io.File %)) sdp-files)
        all-paths (filter some? (concat known-file-paths known-rapid-paths))
        missing-files (set
                        (concat
                          (->> all-paths
                               (remove (comp #(.exists ^java.io.File %) io/file)))
                          (->> all-paths
                               (remove (comp (partial fs/descendant? (fs/isolation-dir)) io/file)))))]
    (when-not (.exists mods-cache-root)
      (.mkdirs mods-cache-root))
    (log/info "Found" (count to-add-file) "mod files and" (count to-add-rapid)
              "rapid files to scan for mods in" (- (u/curr-millis) before) "ms")
    (doseq [file (concat to-add-file to-add-rapid)]
      (log/info "Reading mod from" file)
      (update-mod *state file))
    (log/info "Removing mods with no name, and" (count missing-files) "mods with missing files")
    (swap! state-atom update :mods
           (fn [mods]
             (->> mods
                  (remove (comp string/blank? :mod-name))
                  (remove (comp missing-files :absolute-path))
                  set)))
    {:to-add-file-count (count to-add-file)
     :to-add-rapid-count (count to-add-rapid)}))


(def ^java.io.File maps-cache-root
  (io/file (fs/app-root) "maps-cache"))

(defn map-cache-file [map-name]
  (io/file maps-cache-root (str map-name ".edn")))

(defn reconcile-maps
  "Reads map details and caches for maps missing from :maps in state."
  [state-atom]
  (let [before (u/curr-millis)
        map-files (fs/map-files)
        known-filenames (->> state-atom deref :maps (map :filename) set)
        todo (remove (comp known-filenames #(.getName ^java.io.File %)) map-files)
        missing-filenames (->> known-filenames
                               (remove (comp #(.exists ^java.io.File %) fs/map-file))
                               set)]
    (log/info "Found" (count todo) "maps to load in" (- (u/curr-millis) before) "ms")
    (when-not (.exists maps-cache-root)
      (.mkdirs maps-cache-root))
    (doseq [map-file todo]
      (log/info "Reading" map-file)
      (let [{:keys [map-name] :as map-data} (fs/read-map-data map-file {:header-only true})
            map-cache-file (map-cache-file (:map-name map-data))]
        (if map-name
          (do
            (log/info "Caching" map-file "to" map-cache-file)
            (spit map-cache-file (with-out-str (pprint map-data)))
            (swap! state-atom update :maps
                   (fn [maps]
                     (set (conj maps (select-keys map-data [:filename :map-name]))))))
          (log/warn "No map name found for" map-file))))
    (log/debug "Removing maps with no name, and" (count missing-filenames) "maps with missing files")
    (swap! state-atom update :maps
           (fn [maps]
             (->> maps
                  (remove (comp string/blank? :map-name))
                  (remove (comp missing-filenames :filename))
                  set)))
    {:todo-count (count todo)}))

(defn force-update-battle-map
  ([]
   (force-update-battle-map *state))
  ([state-atom]
   (reconcile-maps state-atom)
   (let [{:keys [battle battles maps]} @state-atom
         battle-id (:battle-id battle)
         battle-map (-> battles (get battle-id) :battle-map)
         _ (log/debug "Force updating battle map details for" battle-map)
         filter-fn (comp #{battle-map} :map-name)
         map-details (some->> maps
                              (filter filter-fn)
                              first
                              :absolute-path
                              io/file
                              fs/read-map-data)]
     (swap! *state assoc :battle-map-details map-details)
     map-details)))

(defmethod task-handler ::reconcile-engines [_]
  (reconcile-engines *state))

(defmethod task-handler ::reconcile-mods [_]
  (reconcile-mods *state))

(defmethod task-handler ::reconcile-maps [_]
  (reconcile-maps *state))


(defmethod task-handler ::update-map
  [{:keys [_file]}]
  (reconcile-maps *state)) ; TODO specific map


(defmulti event-handler :event/type)


(defmethod event-handler ::reload-engines [_e]
  (future
    (try
      (reconcile-engines *state)
      (catch Exception e
        (log/error e "Error reloading engines")))))


(defmethod event-handler ::reload-mods [_e]
  (future
    (try
      (reconcile-mods *state)
      (catch Exception e
        (log/error e "Error reloading mods")))))

(defmethod event-handler ::reload-maps [_e]
  (future
    (try
      (reconcile-maps *state)
      (catch Exception e
        (log/error e "Error reloading maps")))))


(defmethod event-handler ::select-battle [e]
  (swap! *state assoc :selected-battle (-> e :fx/event :battle-id)))


(defmethod event-handler ::on-mouse-clicked-battles-row
  [{:fx/keys [^javafx.scene.input.MouseEvent event]}]
  (when (< 1 (.getClickCount event))
    (event-handler {:event/type ::join-battle})))


(defn battles-table [{:keys [battles users]}]
  {:fx/type fx.ext.table-view/with-selection-props
   :props {:selection-mode :single
           :on-selected-item-changed {:event/type ::select-battle}}
   :desc
   {:fx/type :table-view
    :on-mouse-clicked {:event/type ::on-mouse-clicked-battles-row}
    :column-resize-policy :constrained ; TODO auto resize
    :items (vec (vals battles))
    :columns
    [{:fx/type :table-column
      :text "Battle Name"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-title i))})}}
     {:fx/type :table-column
      :text "Host"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:host-username i))})}}
     {:fx/type :table-column
      :text "Status"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe
       (fn [i]
         (let [status (select-keys i [:battle-type :battle-passworded])]
           (if (:battle-passworded status)
             {:text ""
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-lock:16:white"}}
             {:text ""
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-lock-open:16:white"}})))}}
     {:fx/type :table-column
      :text "Country"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:country (get users (:host-username i))))})}}
     {:fx/type :table-column
      :text "?"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-rank i))})}}
     {:fx/type :table-column
      :text "Players"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (count (:users i)))})}}
     {:fx/type :table-column
      :text "Max"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-maxplayers i))})}}
     {:fx/type :table-column
      :text "Spectators"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-spectators i))})}}
     {:fx/type :table-column
      :text "Running"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (->> i :host-username (get users) :client-status :ingame str)})}}
     {:fx/type :table-column
      :text "Game"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-modname i))})}}
     {:fx/type :table-column
      :text "Map"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-map i))})}}
     {:fx/type :table-column
      :text "Engine"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [i] {:text (str (:battle-engine i) " " (:battle-version i))})}}]}})

(defn users-table [{:keys [users]}]
  {:fx/type :table-view
   :column-resize-policy :constrained ; TODO auto resize
   :items (vec (vals users))
   :columns
   [{:fx/type :table-column
     :text "Username"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:username i))})}}
    {:fx/type :table-column
     :text "Status"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [i]
        (let [status (select-keys (:client-status i) [:bot :access :away :ingame])]
          (cond
            (:bot status)
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-robot:16:white"}}
            (:away status)
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-sleep:16:white"}}
            (:access status)
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-account-key:16:white"}}
            :else
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-account:16:white"}})))}}
    {:fx/type :table-column
     :text "Country"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:country i))})}}
    {:fx/type :table-column
     :text "Rank"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:rank (:client-status i)))})}}
    {:fx/type :table-column
     :text "Lobby Client"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:user-agent i))})}}]})

(defn update-disconnected []
  (log/warn (ex-info "stacktrace" {}) "Updating state after disconnect")
  (swap! *state dissoc
         :battle :battles :channels :client :client-deferred :my-channels :users
         :last-failed-message)
  nil)

(defmethod event-handler ::print-state [_e]
  (pprint *state))

(defmethod event-handler ::show-rapid-downloader [_e]
  (swap! *state assoc :show-rapid-downloader true))

(defmethod event-handler ::show-http-downloader [_e]
  (swap! *state assoc :show-http-downloader true))

(defmethod event-handler ::disconnect [_e]
  (let [state @*state]
    (when-let [client (:client state)]
      (when-let [f (:connected-loop state)]
        (future-cancel f))
      (when-let [f (:print-loop state)]
        (future-cancel f))
      (when-let [f (:ping-loop state)]
        (future-cancel f))
      (client/disconnect client)))
  (update-disconnected))

(defn connected-loop [state-atom client-deferred]
  (swap! state-atom assoc
         :connected-loop
         (future
           (try
             (let [^SplicedStream client @client-deferred]
               (client/connect state-atom client)
               (swap! state-atom assoc :client client :login-error nil)
               (loop []
                 (if (and client (not (.isClosed client)))
                   (when-not (Thread/interrupted)
                     (log/debug "Client is still connected")
                     (async/<!! (async/timeout 20000))
                     (recur))
                   (when-not (Thread/interrupted)
                     (log/info "Client was disconnected")
                     (update-disconnected))))
               (log/info "Connect loop closed"))
             (catch Exception e
               (log/error e "Connect loop error")
               (when-not (or (Thread/interrupted) (instance? java.lang.InterruptedException e))
                 (swap! state-atom assoc :login-error (str (.getMessage e)))
                 (update-disconnected))))
           nil)))

(defmethod event-handler ::connect [_e]
  (let [server-url (:server-url @*state)
        client-deferred (client/client server-url)]
    (swap! *state assoc :client-deferred client-deferred)
    (connected-loop *state client-deferred)))


(defn client-buttons
  [{:keys [client client-deferred username password login-error server-url]}]
  {:fx/type :h-box
   :alignment :top-left
   :style {:-fx-font-size 16}
   :children
   [{:fx/type :button
     :text (if client
             "Disconnect"
             (if client-deferred
               "Connecting..."
               "Connect"))
     :disable (boolean (and (not client) client-deferred))
     :on-action {:event/type (if client ::disconnect ::connect)}}
    {:fx/type :v-box
     :alignment :center-left
     :children
     [{:fx/type :label
       :alignment :center
       :text " Login: "}]}
    {:fx/type :text-field
     :text username
     :prompt-text "Username"
     :disable (boolean (or client client-deferred))
     :on-text-changed {:event/type ::username-change}}
    {:fx/type :password-field
     :text password
     :prompt-text "Password"
     :disable (boolean (or client client-deferred))
     :on-text-changed {:event/type ::password-change}}
    {:fx/type :v-box
     :alignment :center-left
     :children
     [{:fx/type :label
       :alignment :center
       :text " Server: "}]}
    {:fx/type :text-field
     :text server-url
     :prompt-text "server:port"
     :disable (boolean (or client client-deferred))
     :on-text-changed {:event/type ::server-url-change}}
    {:fx/type :v-box
     :h-box/hgrow :always
     :alignment :center
     :children
     [{:fx/type :label
       :text (str login-error)
       :style {:-fx-text-fill "#FF0000"
               :-fx-max-width "360px"}}]}]})


(defmethod event-handler ::username-change
  [{:fx/keys [event]}]
  (swap! *state assoc :username event))

(defmethod event-handler ::password-change
  [{:fx/keys [event]}]
  (swap! *state assoc :password event))

(defmethod event-handler ::server-url-change
  [{:fx/keys [event]}]
  (swap! *state assoc :server-url event))



(defn open-battle
  [client {:keys [battle-type nat-type battle-password host-port max-players mod-hash rank map-hash
                  engine engine-version map-name title mod-name]
           :or {battle-type 0
                nat-type 0
                battle-password "*"
                host-port 8452
                max-players 8
                rank 0
                engine "Spring"}}]
  (swap! *state assoc :battle {})
  (message/send-message client
    (str "OPENBATTLE " battle-type " " nat-type " " battle-password " " host-port " " max-players
         " " mod-hash " " rank " " map-hash " " engine "\t" engine-version "\t" map-name "\t" title
         "\t" mod-name)))


(defmethod event-handler ::host-battle
  [{:keys [client scripttags host-battle-state]}]
  (let [{:keys [engine-version map-name mod-name]} host-battle-state]
    (when-not (or (string/blank? engine-version)
                  (string/blank? mod-name)
                  (string/blank? map-name))
      (open-battle client host-battle-state)
      (when (seq scripttags)
        (message/send-message client (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags)))))))


(defmethod event-handler ::leave-battle [_e]
  (message/send-message (:client @*state) "LEAVEBATTLE"))

(defmethod event-handler ::pop-out-battle [_e]
  (swap! *state assoc :pop-out-battle true))

(defmethod event-handler ::pop-in-battle [_e]
  (swap! *state assoc :pop-out-battle false))

(defmethod event-handler ::join-battle [_e]
  (let [{:keys [battles battle-password selected-battle]} @*state]
    (when selected-battle
      (message/send-message (:client @*state)
        (str "JOINBATTLE " selected-battle
             (when (= "1" (-> battles (get selected-battle) :battle-passworded)) ; TODO
               (str " " battle-password)))))))


(defn update-filter-fn [^javafx.scene.input.KeyEvent event]
  (fn [x]
    (if (= KeyCode/BACK_SPACE (.getCode event))
      (apply str (drop-last x))
      (str x (.getText event)))))

(defmethod event-handler ::maps-key-pressed [{:fx/keys [event]}]
  (swap! *state update :map-input-prefix (update-filter-fn event)))

(defmethod event-handler ::maps-hidden [_e]
  (swap! *state dissoc :map-input-prefix))

(defn map-list
  [{:keys [disable map-name maps on-value-changed map-input-prefix]}]
  {:fx/type :h-box
   :alignment :center-left
   :children
   (concat
     (if (empty? maps)
       [{:fx/type :label
         :text "No maps "}]
       [(let [filter-lc (if map-input-prefix (string/lower-case map-input-prefix) "")
              filtered-maps
              (->> maps
                   (map :map-name)
                   (filter #(string/includes? (string/lower-case %) filter-lc))
                   (sort String/CASE_INSENSITIVE_ORDER))]
          {:fx/type :combo-box
           :value (str map-name)
           :items filtered-maps
           :disable (boolean disable)
           :placeholder {:fx/type :label
                         :text "< choose a map >"}
           :on-value-changed on-value-changed
           :cell-factory
           {:fx/cell-type :list-cell
            :describe
            (fn [map-name]
              {:text (str map-name)
               ;:graphic nil
               #_
               {:fx/type :image-view
                :image {:url (str (io/as-url (io/file (fs/map-minimap map-name))))
                        :background-loading true}
                :fit-width 64
                :fit-height 64
                :preserve-ratio true}})}
           :on-key-pressed {:event/type ::maps-key-pressed}
           :on-hidden {:event/type ::maps-hidden}
           :tooltip {:fx/type :tooltip
                     :show-delay [10 :ms]
                     :text (or map-input-prefix "Choose map")}})])
     [{:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Open maps directory"}}
       :desc
       {:fx/type :button
        :on-action {:event/type ::desktop-browse-dir
                    :file (io/file (fs/isolation-dir) "maps")}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal "mdi-folder:16:white"}}}
      {:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Browse and download maps with http"}}
       :desc
       {:fx/type :button
        :on-action {:event/type ::show-http-downloader}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal (str "mdi-download:16:white")}}}]
     (when (seq maps)
       [{:fx/type fx.ext.node/with-tooltip-props
         :props
         {:tooltip
          {:fx/type :tooltip
           :show-delay [10 :ms]
           :text "Random map"}}
         :desc
         {:fx/type :button
          :disable disable
          :on-action (fn [& _]
                       (event-handler
                         (let [random-map-name (:map-name (rand-nth (seq maps)))]
                           (assoc on-value-changed :map-name random-map-name))))
          :graphic
          {:fx/type font-icon/lifecycle
           :icon-literal (str "mdi-dice-" (inc (rand-nth (take 6 (iterate inc 0)))) ":16:white")}}}]
      [{:fx/type fx.ext.node/with-tooltip-props
        :props
        {:tooltip
         {:fx/type :tooltip
          :show-delay [10 :ms]
          :text "Reload maps"}}
        :desc
        {:fx/type :button
         :on-action {:event/type ::reload-maps}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal "mdi-refresh:16:white"}}}]))})

(defmethod event-handler ::engines-key-pressed [{:fx/keys [event]}]
  (swap! *state update :engine-filter (update-filter-fn event)))

(defmethod event-handler ::engines-hidden [_e]
  (swap! *state dissoc :engine-filter))

(defmethod event-handler ::mods-key-pressed [{:fx/keys [event]}]
  (swap! *state update :mod-filter (update-filter-fn event)))

(defmethod event-handler ::mods-hidden [_e]
  (swap! *state dissoc :mod-filter))

(defmethod event-handler ::desktop-browse-dir
  [{:keys [file]}]
  (if (fs/wsl-or-windows?)
    (let [runtime (Runtime/getRuntime) ; TODO hacky?
          command ["explorer.exe" (fs/wslpath file)]
          ^"[Ljava.lang.String;" cmdarray (into-array String command)]
      (log/info "Running" (pr-str command))
      (.exec runtime cmdarray nil nil))
    (let [desktop (Desktop/getDesktop)]
      (.browseFileDirectory desktop file))))

(defmethod event-handler ::show-importer [_e]
  (swap! *state assoc :show-importer true))

(defn battles-buttons
  [{:keys [battle battles battle-password battle-title client engine-version mod-name map-name maps
           engines mods map-input-prefix engine-filter mod-filter pop-out-battle selected-battle]
    :as state}]
  {:fx/type :v-box
   :alignment :top-left
   :children
   [{:fx/type :h-box
     :alignment :center-left
     :style {:-fx-font-size 16}
     :children
     [{:fx/type :label
       :text "Resources: "}
      {:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Import local resources from SpringLobby and Beyond All Reason"}}
       :desc
       {:fx/type :button
        :on-action {:event/type ::show-importer}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal (str "mdi-file-import:16:white")}}}
      {:fx/type :h-box
       :alignment :center-left
       :children
       (concat
         [{:fx/type :label
           :text " Engine: "}]
         (if (empty? engines)
           [{:fx/type :label
             :text "No engines "}]
           (let [filter-lc (if engine-filter (string/lower-case engine-filter) "")
                 filtered-engines (->> engines
                                       (map :engine-version)
                                       (filter #(string/includes? (string/lower-case %) filter-lc))
                                       sort)]
             [{:fx/type :combo-box
               :value (str engine-version)
               :items filtered-engines
               :disable (boolean battle)
               :placeholder {:fx/type :label
                             :text "< choose an engine >"}
               :on-value-changed {:event/type ::version-change}
               :cell-factory
               {:fx/cell-type :list-cell
                :describe (fn [engine] {:text (str engine)})}
               :on-key-pressed {:event/type ::engines-key-pressed}
               :on-hidden {:event/type ::engines-hidden}
               :tooltip {:fx/type :tooltip
                         :show-delay [10 :ms]
                         :text (or engine-filter "Choose engine")}}]))
         [{:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Open engine directory"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::desktop-browse-dir
                        :file (io/file (fs/isolation-dir) "engine")}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-folder:16:white"}}}
          {:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Browse and download engines with http"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::show-http-downloader}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal (str "mdi-download:16:white")}}}]
         [{:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Browse and download engines with http"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::show-http-downloader}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal (str "mdi-download:16:white")}}}
          {:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Reload engines"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::reload-engines}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-refresh:16:white"}}}])}
      {:fx/type :h-box
       :alignment :center-left
       :children
       (concat
         [{:fx/type :label
           :alignment :center-left
           :text " Game: "}]
         (if (empty? mods)
           [{:fx/type :label
             :text "No games "}]
           (let [filter-lc (if mod-filter (string/lower-case mod-filter) "")
                 filtered-mods (->> mods
                                    (map :mod-name)
                                    (filter string?)
                                    (filter #(string/includes? (string/lower-case %) filter-lc))
                                    (sort version/version-compare))]
             [{:fx/type :combo-box
               :value mod-name
               :items filtered-mods
               :disable (boolean battle)
               :placeholder {:fx/type :label
                             :text "< choose a game >"}
               :on-value-changed {:event/type ::mod-change}
               :cell-factory
               {:fx/cell-type :list-cell
                :describe (fn [mod-name] {:text (str mod-name)})}
               :on-key-pressed {:event/type ::mods-key-pressed}
               :on-hidden {:event/type ::mods-hidden}
               :tooltip {:fx/type :tooltip
                         :show-delay [10 :ms]
                         :text (or mod-filter "Choose game")}}]))
         [{:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Open games directory"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::desktop-browse-dir
                        :file (io/file (fs/isolation-dir) "games")}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-folder:16:white"}}}
          {:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Browse and download more with Rapid"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::show-rapid-downloader}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal (str "mdi-download:16:white")}}}
          {:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Reload games"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::reload-mods}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-refresh:16:white"}}}])}
      {:fx/type :label
       :alignment :center-left
       :text " Map: "}
      {:fx/type map-list
       :disable (boolean battle)
       :map-name map-name
       :maps maps
       :map-input-prefix map-input-prefix
       :on-value-changed {:event/type ::map-change}}]}
    {:fx/type :h-box
     :style {:-fx-font-size 16}
     :alignment :center-left
     :children
     (concat
       (when (and client (not battle))
         (let [host-battle-state
               (-> state
                   (clojure.set/rename-keys {:battle-title :title})
                   (select-keys [:battle-password :title :engine-version
                                 :mod-name :map-name])
                   (assoc :mod-hash -1
                          :map-hash -1))
               host-battle-action (merge
                                    {:event/type ::host-battle
                                     :host-battle-state host-battle-state}
                                    (select-keys state [:client :scripttags]))]
           [{:fx/type :button
             :text "Host Battle"
             :disable (boolean
                        (or (string/blank? engine-version)
                            (string/blank? map-name)
                            (string/blank? mod-name)))
             :on-action host-battle-action}
            {:fx/type :label
             :text " Battle Name: "}
            {:fx/type :text-field
             :text (str battle-title)
             :prompt-text "Battle Title"
             :on-action host-battle-action
             :on-text-changed {:event/type ::battle-title-change}}
            {:fx/type :label
             :text " Battle Password: "}
            {:fx/type :text-field
             :text (str battle-password)
             :prompt-text "Battle Password"
             :on-action host-battle-action
             :on-text-changed {:event/type ::battle-password-change}}])))}
    {:fx/type :h-box
     :alignment :center-left
     :style {:-fx-font-size 16}
     :children
     (concat
       (when battle
         [{:fx/type :button
           :text "Leave Battle"
           :on-action {:event/type ::leave-battle}}
          {:fx/type :pane
           :h-box/margin 8}
          (if pop-out-battle
            {:fx/type :button
             :text "Pop In Battle "
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-window-maximize:16:white"}
             :on-action {:event/type ::pop-in-battle}}
            {:fx/type :button
             :text "Pop Out Battle "
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-open-in-new:16:white"}
             :on-action {:event/type ::pop-out-battle}})])
       (when (and (not battle) selected-battle (-> battles (get selected-battle)))
         (let [needs-password (= "1" (-> battles (get selected-battle) :battle-passworded))]
           (concat
             [{:fx/type :button
               :text "Join Battle"
               :disable (boolean (and needs-password (string/blank? battle-password)))
               :on-action {:event/type ::join-battle}}]
             (when needs-password
               [{:fx/type :label
                 :text " Battle Password: "}
                {:fx/type :text-field
                 :text (str battle-password)
                 :prompt-text "Battle Password"
                 :on-action {:event/type ::host-battle}
                 :on-text-changed {:event/type ::battle-password-change}}])))))}]})


(defmethod event-handler ::battle-password-change
  [{:fx/keys [event]}]
  (swap! *state assoc :battle-password event))

(defmethod event-handler ::battle-title-change
  [{:fx/keys [event]}]
  (swap! *state assoc :battle-title event))

(defmethod event-handler ::minimap-type-change
  [{:fx/keys [event]}]
  (swap! *state assoc :minimap-type event))

(defmethod event-handler ::version-change
  [{:fx/keys [event]}]
  (swap! *state assoc :engine-version event))

(defmethod event-handler ::mod-change
  [{:fx/keys [event]}]
  (swap! *state assoc :mod-name event))

(defmethod event-handler ::map-change
  [{:fx/keys [event] :keys [map-name] :as e}]
  (log/info e)
  (let [map-name (or map-name event)]
    (swap! *state assoc
           :map-name map-name
           :map-details (safe-read-map-cache map-name))))

(defmethod event-handler ::battle-map-change
  [{:fx/keys [event] :keys [map-name]}]
  (let [spectator-count 0 ; TODO
        locked 0
        map-hash -1 ; TODO
        map-name (or map-name event)
        m (str "UPDATEBATTLEINFO " spectator-count " " locked " " map-hash " " map-name)]
    (swap! *state assoc
           :battle-map-details (safe-read-map-cache map-name))
    (message/send-message (:client @*state) m)))

(defmethod event-handler ::kick-battle
  [{:keys [bot-name username]}]
  (when-let [client (:client @*state)]
    (if bot-name
      (message/send-message client (str "REMOVEBOT " bot-name))
      (message/send-message client (str "KICKFROMBATTLE " username)))))


(defn available-name [existing-names desired-name]
  (if-not (contains? (set existing-names) desired-name)
    desired-name
    (recur
      existing-names
      (if-let [[_ prefix n suffix] (re-find #"(.*)(\d+)(.*)" desired-name)]
        (let [nn (inc (u/to-number n))]
          (str prefix nn suffix))
        (str desired-name 0)))))

(defmethod event-handler ::add-bot [{:keys [battle bot-username bot-name bot-version]}]
  (let [existing-bots (keys (:bots battle))
        bot-username (available-name existing-bots bot-username)
        bot-status (client/encode-battle-status
                     (assoc client/default-battle-status
                            :ready true
                            :mode 1
                            :sync 1
                            :id (battle/available-team-id battle)
                            :ally (battle/available-ally battle)
                            :side (rand-nth [0 1])))
        bot-color (u/random-color)
        message (str "ADDBOT " bot-username " " bot-status " " bot-color " " bot-name "|" bot-version)]
    (message/send-message (:client @*state) message)))

(defmethod event-handler ::change-bot-username
  [{:fx/keys [event]}]
  (swap! *state assoc :bot-username event))

(defmethod event-handler ::change-bot-name
  [{:keys [bots] :fx/keys [event]}]
  (let [bot-name event
        bot-version (-> (group-by :bot-name bots)
                        (get bot-name)
                        first
                        :bot-version)]
    (swap! *state assoc :bot-name bot-name :bot-version bot-version)))

(defmethod event-handler ::change-bot-version
  [{:fx/keys [event]}]
  (swap! *state assoc :bot-version event))


(defmethod event-handler ::start-battle [_e]
  (future
    (try
      (spring/start-game @*state)
      (catch Exception e
        (log/error e "Error starting battle")))))


(defn fix-color
  "Returns the rgb int color represention for the given Spring bgr int color."
  [spring-color]
  (let [spring-color-int (if spring-color (u/to-number spring-color) 0)
        [r g b _a] (:rgba (colors/create-color spring-color-int))
        reversed (colors/create-color
                   {:r b
                    :g g
                    :b r})]
    (Color/web (format "#%06x" (colors/rgb-int reversed)))))


(defn minimap-starting-points
  [battle-details map-details scripttags minimap-width minimap-height]
  (let [{:keys [map-width map-height]} (-> map-details :smf :header)
        teams (spring/teams battle-details)
        team-by-key (->> teams
                         (map second)
                         (map (juxt (comp spring/team-name :battle-status) identity))
                         (into {}))
        battle-team-keys (spring/team-keys teams)
        map-teams (spring/map-teams map-details)
        missing-teams (clojure.set/difference
                        (set (map spring/normalize-team battle-team-keys))
                        (set (map (comp spring/normalize-team first) map-teams)))
        midx (if map-width (quot (* spring/map-multiplier map-width) 2) 0)
        midz (if map-height (quot (* spring/map-multiplier map-height) 2) 0)
        choose-before-game (= "3" (some-> scripttags :game :startpostype str))
        all-teams (if choose-before-game
                    (concat map-teams (map (fn [team] [team {}]) missing-teams))
                    map-teams)]
    (when (and (number? map-width)
               (number? map-height)
               (number? minimap-width)
               (number? minimap-height))
      (->> all-teams
           (map
             (fn [[team-kw {:keys [startpos]}]]
               (let [{:keys [x z]} startpos
                     [_all team] (re-find #"(\d+)" (name team-kw))
                     normalized (spring/normalize-team team-kw)
                     scriptx (when choose-before-game
                               (some-> scripttags :game normalized :startposx u/to-number))
                     scriptz (when choose-before-game
                               (some-> scripttags :game normalized :startposz u/to-number))
                     scripty (when choose-before-game
                               (some-> scripttags :game normalized :startposy u/to-number))
                     ; ^ SpringLobby seems to use startposy
                     x (or scriptx x midx)
                     z (or scriptz scripty z midz)]
                 (when (and (number? x) (number? z))
                   {:x (- (* (/ x (* spring/map-multiplier map-width)) minimap-width)
                          (/ start-pos-r 2))
                    :y (- (* (/ z (* spring/map-multiplier map-height)) minimap-height)
                          (/ start-pos-r 2))
                    :team team
                    :color (or (-> team-by-key team-kw :team-color fix-color)
                               Color/WHITE)}))))
           (filter some?)))))

; https://github.com/cljfx/cljfx/issues/76#issuecomment-645563116
(def ext-recreate-on-key-changed
  "Extension lifecycle that recreates its component when lifecycle's key is changed

  Supported keys:
  - `:key` (required) - a value that determines if returned component should be recreated
  - `:desc` (required) - a component description with additional lifecycle semantics"
  (reify fx.lifecycle/Lifecycle
    (create [_ {:keys [key desc]} opts]
      (with-meta {:key key
                  :child (fx.lifecycle/create fx.lifecycle/dynamic desc opts)}
                 {`fx.component/instance #(-> % :child fx.component/instance)}))
    (advance [this component {:keys [key desc] :as this-desc} opts]
      (if (= (:key component) key)
        (update component :child #(fx.lifecycle/advance fx.lifecycle/dynamic % desc opts))
        (do (fx.lifecycle/delete this component opts)
            (fx.lifecycle/create this this-desc opts))))
    (delete [_ component opts]
      (fx.lifecycle/delete fx.lifecycle/dynamic (:child component) opts))))


(defmethod event-handler ::minimap-mouse-pressed
  [{:fx/keys [^javafx.scene.input.MouseEvent event] :keys [starting-points startpostype]}]
  (when (= "Choose before game" startpostype)
    (let [ex (.getX event)
          ey (.getY event)]
      (when-let [target (some
                          (fn [{:keys [x y] :as target}]
                            (when (and
                                    (< x ex (+ x (* 2 start-pos-r)))
                                    (< y ey (+ y (* 2 start-pos-r))))
                              target))
                          starting-points)]
        (swap! *state assoc :drag-team {:team (:team target)
                                        :x (- ex start-pos-r)
                                        :y (- ey start-pos-r)})))))


(defmethod event-handler ::minimap-mouse-dragged
  [{:fx/keys [^javafx.scene.input.MouseEvent event]}]
  (swap! *state
         (fn [state]
           (if (:drag-team state)
             (update state :drag-team assoc
                     :x (- (.getX event) start-pos-r)
                     :y (- (.getY event) start-pos-r))
             state))))

(defmethod event-handler ::minimap-mouse-released
  [{:keys [minimap-width minimap-height map-details]}]
  (when-let [{:keys [team x y]} (-> *state deref :drag-team)]
    (let [{:keys [map-width map-height]} (-> map-details :smf :header)
          x (int (* (/ x minimap-width) map-width spring/map-multiplier))
          z (int (* (/ y minimap-height) map-height spring/map-multiplier))
          scripttags {:game
                      {(keyword (str "team" team))
                       {:startposx x
                        :startposy z ; for SpringLobby bug
                        :startposz z}}}]
      (log/debug scripttags)
      (swap! *state update :scripttags u/deep-merge scripttags)
      (swap! *state update-in [:battle :scripttags] u/deep-merge scripttags)
      (message/send-message (:client @*state) (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags)))))
  (swap! *state dissoc :drag-team))


(defn minimap-dimensions [map-smf-header]
  (let [{:keys [map-width map-height]} map-smf-header]
    (when (and map-width)
      (let [ratio-x (/ minimap-size map-width)
            ratio-y (/ minimap-size map-height)
            min-ratio (min ratio-x ratio-y)
            normal-x (/ ratio-x min-ratio)
            normal-y (/ ratio-y min-ratio)
            invert-x (/ min-ratio ratio-x)
            invert-y (/ min-ratio ratio-y)
            convert-x (if (< ratio-y ratio-x) invert-x normal-x)
            convert-y (if (< ratio-x ratio-y) invert-y normal-y)
            minimap-width (* minimap-size convert-x)
            minimap-height (* minimap-size convert-y)]
        {:minimap-width minimap-width
         :minimap-height minimap-height}))))


(def ok-green "#008000")
(def warn-yellow "#FFD700")
(def error-red "#DD0000")
(def severity-styles
  {0 {:-fx-base ok-green
      :-fx-background ok-green
      :-fx-background-color ok-green}
   1 {:-fx-base warn-yellow
      :-fx-background warn-yellow
      :-fx-background-color warn-yellow}
   2 {:-fx-base error-red
      :-fx-background error-red
      :-fx-background-color error-red}})

(defn resource-sync-pane
  [{:keys [browse-action delete-action issues refresh-action resource]}]
  (let [worst-severity (reduce
                         (fn [worst {:keys [severity]}]
                           (max worst severity))
                         0
                         issues)]
    {:fx/type :v-box
     :style (merge
              (get severity-styles worst-severity)
              {:-fx-background-radius 3
               :-fx-border-color "#666666"
               :-fx-border-radius 3
               :-fx-border-style "solid"
               :-fx-border-width 1})
     :children
     (concat
       [{:fx/type :h-box
         :children
         (concat
           [{:fx/type :label
             :h-box/margin 4
             :text (str resource
                        (if (zero? worst-severity) " synced"
                          " status:"))
             :style {:-fx-font-size 16}}
            {:fx/type :pane
             :h-box/hgrow :always}]
           (when refresh-action
             [{:fx/type :button
               :on-action refresh-action
               :tooltip
               {:fx/type :tooltip
                :show-delay [10 :ms]
                :style {:-fx-font-size 14}
                :text "Force refresh this resource"}
               :h-box/margin 4
               :style
               {:-fx-base "black"
                :-fx-background "black"
                :-fx-background-color "black"}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-refresh:16:white"}}])
           (when delete-action
             [{:fx/type :button
               :on-action delete-action
               :tooltip
               {:fx/type :tooltip
                :show-delay [10 :ms]
                :style {:-fx-font-size 14}
                :text "Delete this resource"}
               :h-box/margin 4
               :style
               {:-fx-base "black"
                :-fx-background "black"
                :-fx-background-color "black"}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-delete:16:white"}}])
           (when browse-action
             [{:fx/type :button
               :on-action browse-action
               :tooltip
               {:fx/type :tooltip
                :show-delay [10 :ms]
                :style {:-fx-font-size 14}
                :text "Browse this resource"}
               :h-box/margin 4
               :style
               {:-fx-base "black"
                :-fx-background "black"
                :-fx-background-color "black"}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-folder:16:white"}}]))}]
       (map
         (fn [{:keys [action in-progress human-text severity text tooltip]}]
           (let [font-style {:-fx-font-size 12}
                 display-text (or human-text
                                  (str text " " resource))]
             {:fx/type fx.ext.node/with-tooltip-props
              :v-box/margin 2
              :props
              (when tooltip
                {:tooltip
                 {:fx/type :tooltip
                  :show-delay [10 :ms]
                  :style {:-fx-font-size 14}
                  :text tooltip}})
              :desc
              (if (or (zero? severity) (not action))
                {:fx/type :label
                 :text display-text
                 :style font-style
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal
                  (str "mdi-"
                       (if (zero? severity) "check" "exclamation")
                       ":16:"
                       (if (= 1 worst-severity) "black" "white"))}}
                {:fx/type :v-box
                 :style (merge
                          (get severity-styles severity)
                          font-style)
                 :children
                 [{:fx/type :button
                   :v-box/margin 8
                   :text display-text
                   :disable (boolean in-progress)
                   :on-action action}]})}))
         issues))}))


(defmethod event-handler ::copy-file
  [{:keys [source dest] :as e}]
  (if source
    (do
      (log/info "Copying from" source "to" dest)
      (swap! *state assoc-in [:copying (.getAbsolutePath source)] {:status true})
      (future
        (try
          (spring/java-nio-copy source dest) ; TODO move to fs
          (catch Exception e
            (log/error e "Error copying from" source "to" dest))
          (finally
            (swap! *state assoc-in [:copying (.getAbsolutePath source)] {:status false})))))
    (log/warn "No source to copy for event" e)))

(defmethod event-handler ::copy-map ; TODO duplicate of ::copy-file ?
  [{:keys [map-filename engine-version]}]
  (log/info "Copying map" map-filename "to" engine-version)
  (swap! *state assoc-in [:copying map-filename] {:status true})
  (future
    (try
      (spring/copy-map map-filename engine-version)
      (catch Exception e
        (log/error e "Error copying map" map-filename "to isolation dir for" engine-version))
      (finally
        (swap! *state assoc-in [:copying map-filename] {:status false})))))

(defmethod event-handler ::import-map ; TODO duplicate of ::copy-file ?
  [{:keys [source dest]}]
  (when source
    (let [filename (.getName source)
          dest (or dest (fs/map-file filename))]
      (log/info "Importing map" filename "from" source "to" dest)
      (swap! *state assoc-in [:copying filename] {:status true})
      (future
        (try
          (spring/java-nio-copy source dest)
          (catch Exception e
            (log/error e "Error importing map" filename "from" source "to" dest))
          (finally
            (swap! *state assoc-in [:copying filename] {:status false})))))))

(defmethod event-handler ::copy-mod ; TODO duplicate of ::copy-file ?
  [{:keys [mod-details engine-version]}]
  (let [mod-filename (:filename mod-details)]
    (log/info "Copying mod" mod-filename "to" engine-version)
    (swap! *state assoc-in [:copying mod-filename] {:status true})
    (future
      (try
        (spring/copy-mod mod-details engine-version)
        (catch Exception e
          (log/error e "Error copying mod" mod-filename "to isolation dir for" engine-version))
        (finally
          (swap! *state assoc-in [:copying mod-filename] {:status false}))))))

(defmethod event-handler ::git-mod
  [{:keys [battle-mod-git-ref absolute-path]}]
  (when (and absolute-path battle-mod-git-ref)
    (log/info "Checking out mod" absolute-path "to" battle-mod-git-ref)
    (swap! *state assoc-in [:gitting absolute-path] {:status true})
    (future
      (try
        (git/fetch (io/file absolute-path))
        (git/reset-hard (io/file absolute-path) battle-mod-git-ref)
        (reconcile-mods *state)
        (catch Exception e
          (log/error e "Error during git reset" absolute-path "to" battle-mod-git-ref))
        (finally
          (swap! *state assoc-in [:gitting absolute-path] {:status false}))))))

(defmethod event-handler ::archive-mod
  [{:keys [mod-details engine-version]}]
  (let [mod-filename (:filename mod-details)]
    (log/info "Archiving mod" mod-filename "to" engine-version)
    (swap! *state assoc-in [:archiving mod-filename] {:status true})
    (future
      (try
        (spring/archive-mod mod-details engine-version)
        (catch Exception e
          (log/error e "Error archiving mod" mod-filename "to isolation dir for" engine-version)
          (raynes-fs/delete
            (spring/mod-isolation-archive-file mod-details engine-version)))
        (finally
          (swap! *state assoc-in [:archiving mod-filename] {:status false}))))))

(defmethod event-handler ::copy-engine
  [{:keys [engines engine-version]}]
  (log/info "Copying engine" engine-version "to isolation dir")
  (swap! *state assoc-in [:copying engine-version] {:status true})
  (future
    (try
      (spring/copy-engine engines engine-version)
      (catch Exception e
        (log/error e "Error copying engine" engine-version "to isolation dir"))
      (finally
        (swap! *state assoc-in [:copying engine-version] {:status false})))))

(defmethod event-handler ::clean-engine
  [{:keys [engines engine-version]}]
  (let [engine-details (spring/engine-details engines engine-version)
        absolute-path (:absolute-path engine-details)
        engine-dir (io/file absolute-path)]
    (log/info "Cleaning engine" engine-version "at" engine-dir)
    (swap! *state assoc-in [:cleaning engine-version] {:status true})
    (future
      (try
        (raynes-fs/delete-dir (io/file engine-dir "packages"))
        (raynes-fs/delete-dir (io/file engine-dir "pool"))
        (catch Exception e
          (log/error e "Error cleaning engine" engine-version "at" engine-dir))
        (finally
          (swap! *state assoc-in [:cleaning engine-version] {:status false}))))))


(defn engine-dest [engine-version]
  (when engine-version
    (io/file (fs/spring-root) "engine" (http/engine-archive engine-version))))

#_
(defn engine-download-file [engine-version]
  (when engine-version
    (io/file (fs/download-dir) "engine" (http/engine-archive engine-version))))


(def minimap-types
  ["minimap" "metalmap"])

(defmethod event-handler ::minimap-scroll
  [_e]
  (swap! *state
         (fn [{:keys [minimap-type] :as state}]
           (let [next-index (mod
                              (inc (.indexOf minimap-types minimap-type))
                              (count minimap-types))
                 next-type (get minimap-types next-index)]
             (assoc state :minimap-type next-type)))))

(defn git-clone-mod [repo-url]
  (swap! *state assoc-in [:git-clone repo-url :status] true)
  (future
    (try
      (let [[_all dir] (re-find #"/([^/]+)\.git" repo-url)]
        (git/clone-repo repo-url (io/file (fs/isolation-dir) "games" dir)
          {:on-begin-task (fn [title total-work]
                            (let [m (str title " " total-work)]
                              (swap! *state assoc-in [:git-clone repo-url :message] m)))}))
      (reconcile-mods *state)
      (catch Exception e
        (log/error e "Error cloning git repo" repo-url))
      (finally
        (swap! *state assoc-in [:git-clone repo-url :status] false)))))

(defmethod event-handler ::download-mod
  [{:keys [engine-dir-filename git-url mod-name rapid-id]}]
  (cond
    rapid-id
    (event-handler {:event/type ::rapid-download
                    :engine-dir-filename engine-dir-filename
                    :rapid-id rapid-id})
    ; TODO lots of complex logic for where to get each BA version...
    git-url (git-clone-mod git-url)
    :else
    (log/warn "No known method to download mod" (str "'" mod-name "'"))))

(defmethod event-handler ::download-map
  [{:keys [map-name]}]
  (let [url (http/map-url map-name)
        dest (fs/map-file (fs/map-filename map-name))
        http-future (event-handler {:event/type ::http-download
                                    :dest dest
                                    :url url})]
    (future
      (try
        @http-future
        (reconcile-maps *state)
        (swap! *state assoc :battle-map-details (safe-read-map-cache map-name))
        (catch Exception e
          (log/error e "Error downloading map"))))))


(defn scale-minimap-image [minimap-width minimap-height minimap-image]
  (when minimap-image
    (let [^sun.awt.image.ToolkitImage scaled
          (.getScaledInstance ^java.awt.Image minimap-image
            minimap-width minimap-height java.awt.Image/SCALE_SMOOTH)
          _ (.getWidth scaled)
          _ (.getHeight scaled)]
      (.getBufferedImage scaled))))


(defn battle-players-and-bots
  "Returns the sequence of all players and bots for a battle."
  [{:keys [battle users]}]
  (concat
    (mapv
      (fn [[k v]] (assoc v :username k :user (get users k)))
      (:users battle))
    (mapv
      (fn [[k v]]
        (assoc v
               :bot-name k
               :user {:client-status {:bot true}}))
      (:bots battle))))


(defn update-battle-status
  "Sends a message to update battle status for yourself or a bot of yours."
  [{:keys [client]} {:keys [is-bot id]} battle-status team-color]
  (when client
    (let [player-name (or (:bot-name id) (:username id))
          prefix (if is-bot
                   (str "UPDATEBOT " player-name) ; TODO normalize
                   "MYBATTLESTATUS")]
      (log/debug player-name (pr-str battle-status) team-color)
      (message/send-message client
        (str prefix
             " "
             (client/encode-battle-status battle-status)
             " "
             team-color)))))

(defn update-color [id {:keys [is-me is-bot] :as opts} color-int]
  (if (or is-me is-bot)
    (update-battle-status @*state (assoc opts :id id) (:battle-status id) color-int)
    (message/send-message (:client @*state)
      (str "FORCETEAMCOLOR " (:username id) " " color-int))))

(defn update-team [id {:keys [is-me is-bot] :as opts} player-id]
  (if (or is-me is-bot)
    (update-battle-status @*state (assoc opts :id id) (assoc (:battle-status id) :id player-id) (:team-color id))
    (message/send-message (:client @*state)
      (str "FORCETEAMNO " (:username id) " " player-id))))

(defn update-ally [id {:keys [is-me is-bot] :as opts} ally]
  (if (or is-me is-bot)
    (update-battle-status @*state (assoc opts :id id) (assoc (:battle-status id) :ally ally) (:team-color id))
    (message/send-message (:client @*state)
      (str "FORCEALLYNO " (:username id) " " ally))))

(defn update-handicap [id {:keys [is-bot] :as opts} handicap]
  (if is-bot
    (update-battle-status @*state (assoc opts :id id) (assoc (:battle-status id) :handicap handicap) (:team-color id))
    (message/send-message (:client @*state)
      (str "HANDICAP " (:username id) " " handicap))))

(defn apply-battle-status-changes
  [id {:keys [is-me is-bot] :as opts} status-changes]
  (if (or is-me is-bot)
    (update-battle-status @*state (assoc opts :id id) (merge (:battle-status id) status-changes) (:team-color id))
    (doseq [[k v] status-changes]
      (let [msg (case k
                  :id "FORCETEAMNO"
                  :ally "FORCEALLYNO"
                  :handicap "HANDICAP")]
        (message/send-message (:client @*state) (str msg " " (:username id) " " v))))))


(defmethod event-handler ::battle-randomize-colors
  [e]
  (let [players-and-bots (battle-players-and-bots e)]
    (doseq [id players-and-bots]
      (let [is-bot (boolean (:bot-name id))
            is-me (= (:username e) (:username id))]
        (update-color id {:is-me is-me :is-bot is-bot} (u/random-color))))))

(defmethod event-handler ::battle-teams-ffa
  [e]
  (let [players-and-bots (battle-players-and-bots e)]
    (doall
      (map-indexed
        (fn [i id]
          (let [is-bot (boolean (:bot-name id))
                is-me (= (:username e) (:username id))]
            (apply-battle-status-changes id {:is-me is-me :is-bot is-bot} {:id i :ally i})))
        players-and-bots))))

(defn n-teams [e n]
  (let [players-and-bots (battle-players-and-bots e)
        per-partition (int (Math/ceil (/ (count players-and-bots) n)))
        by-ally (->> players-and-bots
                     (shuffle)
                     (map-indexed vector)
                     (partition-all per-partition)
                     vec)]
    (log/debug by-ally)
    (doall
      (map-indexed
        (fn [a players]
          (log/debug a (pr-str players))
          (doall
            (map
              (fn [[i id]]
                (let [is-bot (boolean (:bot-name id))
                      is-me (= (:username e) (:username id))]
                  (apply-battle-status-changes id {:is-me is-me :is-bot is-bot} {:id i :ally a})))
              players)))
        by-ally))))

(defmethod event-handler ::battle-teams-2
  [e]
  (n-teams e 2))

(defmethod event-handler ::battle-teams-3
  [e]
  (n-teams e 3))

(defmethod event-handler ::battle-teams-4
  [e]
  (n-teams e 4))

(defmethod event-handler ::battle-teams-humans-vs-bots
  [{:keys [battle users username]}]
  (let [players (mapv
                  (fn [[k v]] (assoc v :username k :user (get users k)))
                  (:users battle))
        bots (mapv
               (fn [[k v]]
                 (assoc v
                        :bot-name k
                        :user {:client-status {:bot true}}))
               (:bots battle))]
    (doall
      (map-indexed
        (fn [i player]
          (let [is-me (= username (:username player))]
            (apply-battle-status-changes player {:is-me is-me :is-bot false} {:id i :ally 0})))
        players))
    (doall
      (map-indexed
        (fn [b bot]
          (let [i (+ (count players) b)]
            (apply-battle-status-changes bot {:is-me false :is-bot true} {:id i :ally 1})))
        bots))))


(defn spring-color
  "Returns the spring bgr int color format from a javafx color."
  [^javafx.scene.paint.Color color]
  (colors/rgba-int
    (colors/create-color
      {:r (Math/round (* 255 (.getBlue color)))  ; switch blue to red
       :g (Math/round (* 255 (.getGreen color)))
       :b (Math/round (* 255 (.getRed color)))   ; switch red to blue
       :a 0})))


(defn nickname [{:keys [ai-name bot-name owner username]}]
  (if bot-name
    (str bot-name " (" ai-name ", " owner ")")
    (str username)))


(defn battle-players-table
  [{:keys [am-host battle-modname host-username players username]}]
  {:fx/type :table-view
   :column-resize-policy :constrained ; TODO auto resize
   :items (or players [])
   :columns
   [{:fx/type :table-column
     :text "Nickname"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [{:keys [owner] :as id}]
        (merge
          {:text (nickname id)}
          (when (and (not= username (:username id))
                     (or am-host
                         (= owner username)))
            {:graphic
             {:fx/type :button
              :on-action
              (merge
                {:event/type ::kick-battle}
                (select-keys id [:username :bot-name]))
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-account-remove:16:white"}}})))}}
    {:fx/type :table-column
     :text "Country"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:country (:user i)))})}}
    {:fx/type :table-column
     :text "Status"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [i]
        (let [status (merge
                       (select-keys (:client-status (:user i)) [:bot])
                       (select-keys (:battle-status i) [:ready])
                       {:host (= (:username i) host-username)})]
          (cond
            (:bot status)
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-robot:16:white"}}
            (:ready status)
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-account-check:16:white"}}
            (:host status)
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-account-key:16:white"}}
            :else
            {:text ""
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-account:16:white"}})))}}
    {:fx/type :table-column
     :text "Ingame"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:ingame (:client-status (:user i))))})}}
    {:fx/type :table-column
     :text "Spectator"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [i]
        {:text ""
         :graphic
         {:fx/type ext-recreate-on-key-changed
          :key (nickname i)
          :desc
          {:fx/type :check-box
           :selected (not (:mode (:battle-status i)))
           :on-selected-changed {:event/type ::battle-spectate-change
                                 :is-me (= (:username i) username)
                                 :is-bot (-> i :user :client-status :bot)
                                 :id i}
           :disable (not (or (and am-host (:mode (:battle-status i)))
                             (= (:username i) username)
                             (= (:owner i) username)))}}})}}
    {:fx/type :table-column
     :text "Faction"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [i]
        {:text ""
         :graphic
         {:fx/type ext-recreate-on-key-changed
          :key (nickname i)
          :desc
          {:fx/type :choice-box
           :value (->> i :battle-status :side (get (spring/sides battle-modname)) str)
           :on-value-changed {:event/type ::battle-side-changed
                              :is-me (= (:username i) username)
                              :is-bot (-> i :user :client-status :bot)
                              :id i}
           :items (vals (spring/sides battle-modname))
           :disable (not (or am-host
                             (= (:username i) username)
                             (= (:owner i) username)))}}})}}
    {:fx/type :table-column
     :text "Rank"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:rank (:client-status (:user i))))})}}
    {:fx/type :table-column
     :text "TrueSkill"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [_i] {:text ""})}}
    {:fx/type :table-column
     :text "Color"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [{:keys [team-color] :as i}]
        {:text ""
         :graphic
         {:fx/type ext-recreate-on-key-changed
          :key (nickname i)
          :desc
          {:fx/type :color-picker
           :value (fix-color team-color)
           :on-action {:event/type ::battle-color-action
                       :is-me (= (:username i) username)
                       :is-bot (-> i :user :client-status :bot)
                       :id i}
           :disable (not (or am-host
                             (= (:username i) username)
                             (= (:owner i) username)))}}})}}
    {:fx/type :table-column
     :text "Team"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [i]
        {:text ""
         :graphic
         {:fx/type ext-recreate-on-key-changed
          :key (nickname i)
          :desc
          {:fx/type :choice-box
           :value (str (:id (:battle-status i)))
           :on-value-changed {:event/type ::battle-team-changed
                              :is-me (= (:username i) username)
                              :is-bot (-> i :user :client-status :bot)
                              :id i}
           :items (map str (take 16 (iterate inc 0)))
           :disable (not (or am-host
                             (= (:username i) username)
                             (= (:owner i) username)))}}})}}
    {:fx/type :table-column
     :text "Ally"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [i]
        {:text ""
         :graphic
         {:fx/type ext-recreate-on-key-changed
          :key (nickname i)
          :desc
          {:fx/type :choice-box
           :value (str (:ally (:battle-status i)))
           :on-value-changed {:event/type ::battle-ally-changed
                              :is-me (= (:username i) username)
                              :is-bot (-> i :user :client-status :bot)
                              :id i}
           :items (map str (take 16 (iterate inc 0)))
           :disable (not (or am-host
                             (= (:username i) username)
                             (= (:owner i) username)))}}})}}
    {:fx/type :table-column
     :text "Bonus"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [i]
        {:text ""
         :graphic
         {:fx/type ext-recreate-on-key-changed
          :key (nickname i)
          :desc
          {:fx/type :text-field
           :disable (not am-host)
           :text-formatter
           {:fx/type :text-formatter
            :value-converter :integer
            :value (int (or (:handicap (:battle-status i)) 0))
            :on-value-changed {:event/type ::battle-handicap-change
                               :is-bot (-> i :user :client-status :bot)
                               :id i}}}}})}}]})

(defmethod event-handler ::isolation-type-change [{:fx/keys [event]}]
  (log/info event))

(defmethod event-handler ::force-update-battle-map [{:fx/keys [event]}]
  (log/info event)
  (force-update-battle-map *state))

(defmethod event-handler ::delete-map [{:fx/keys [event]}]
  (log/info event))

(defmethod event-handler ::force-update-battle-mod [{:fx/keys [event]}]
  (log/info event)
  (force-update-battle-mod *state))

(defmethod event-handler ::delete-mod [{:fx/keys [event]}]
  (log/info event))

(defmethod event-handler ::delete-engine
  [{:keys [engines engine-version]}]
  (if-let [engine-dir (some->> engines
                               (filter (comp #{engine-version} :engine-version))
                               first
                               :absolute-path
                               io/file)]
    (do
      (log/info "Deleting engine dir" engine-dir)
      (raynes-fs/delete-dir engine-dir)
      (reconcile-engines *state))
    (log/warn "No engine dir for" (pr-str engine-version) "found in" (with-out-str (pprint engines)))))

(defmethod event-handler ::nuke-data-dir
  [_e]
  (future
    (try
      (log/info "Nuking data dir!" (fs/isolation-dir))
      (raynes-fs/delete-dir (fs/isolation-dir))
      (catch Exception e
        (log/error e "Error nuking data dir")))))

#_
(-> user/*state deref :battle-map-details keys)

#_
(->> user/*state deref :importables-by-path
     vals
     ;(filter (comp #{::map} :resource-type))
     (filter (comp #{"Claymore_v2"} :resource-name)))

(defn battle-view
  [{:keys [battle battles battle-map-details battle-mod-details bot-name bot-username bot-version
           copying drag-team engines extracting git-clone gitting http-download importables-by-path
           isolation-type
           map-input-prefix maps minimap-type rapid-data-by-version rapid-download users username]
    :as state}]
  (let [{:keys [host-username battle-map battle-modname]} (get battles (:battle-id battle))
        host-user (get users host-username)
        am-host (= username host-username)
        scripttags (:scripttags battle)
        startpostype (->> scripttags
                          :game
                          :startpostype
                          spring/startpostype-name)
        {:keys [smf]} battle-map-details
        {:keys [minimap-width minimap-height] :or {minimap-width minimap-size minimap-height minimap-size}} (minimap-dimensions (:header smf))
        battle-details (spring/battle-details {:battle battle :battles battles :users users})
        starting-points (minimap-starting-points battle-details battle-map-details scripttags minimap-width minimap-height)
        engine-version (:battle-version battle-details)
        engine-details (spring/engine-details engines engine-version)
        engine-absolute-path (:absolute-path engine-details)
        engine-dir-file (when engine-absolute-path (io/file engine-absolute-path))
        ;engine-dir-filename (spring/engine-dir-filename engines engine-version)
        ;engine-archive-file (engine-dest engine-version)
        engine-download-file (http/engine-download-file engine-version)
        bots (fs/bots engine-absolute-path)
        minimap-image (case minimap-type
                        "metalmap" (:metalmap-image smf)
                        ; else
                        (scale-minimap-image minimap-width minimap-height (:minimap-image smf)))
        bots (concat bots
                     (->> battle-mod-details :luaai
                          (map second)
                          (map (fn [ai]
                                 {:bot-name (:name ai)
                                  :bot-version "<game>"}))))
        bot-names (map :bot-name bots)
        bot-versions (map :bot-version
                          (get (group-by :bot-name bots)
                               bot-name))
        bot-name (some #{bot-name} bot-names)
        bot-version (some #{bot-version} bot-versions)]
    {:fx/type :h-box
     :alignment :top-left
     :children
     [{:fx/type :v-box
       :h-box/hgrow :always
       :children
       [{:fx/type battle-players-table
         :am-host am-host
         :host-username host-username
         :players (battle-players-and-bots state)
         :username username
         :battle-modname battle-modname}
        {:fx/type :h-box
         :children
         [{:fx/type :v-box
           :children
           [{:fx/type :h-box
             :alignment :top-left
             :children
             [{:fx/type :button
               :text "Add Bot"
               :disable (or (string/blank? bot-username)
                            (string/blank? bot-name)
                            (string/blank? bot-version))
               :on-action {:event/type ::add-bot
                           :battle battle
                           :bot-username bot-username
                           :bot-name bot-name
                           :bot-version bot-version}}
              {:fx/type :text-field
               :prompt-text "Bot Name"
               :text (str bot-username)
               :on-text-changed {:event/type ::change-bot-username}}
              {:fx/type :choice-box
               :value bot-name
               :disable (empty? bot-names)
               :on-value-changed {:event/type ::change-bot-name
                                  :bots bots}
               :items bot-names}
              {:fx/type :choice-box
               :value bot-version
               :disable (string/blank? bot-name)
               :on-value-changed {:event/type ::change-bot-version}
               :items (or bot-versions [])}]}
            {:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type :label
               :text " Isolation: "}
              {:fx/type :choice-box
               :value (name (or isolation-type :engine))
               :items (map name [:engine :shared])
               :on-value-changed {:event/type ::isolation-type-change}}]}
            {:fx/type :pane
             :v-box/vgrow :always}
            #_
            {:fx/type :h-box
             :children
             [{:fx/type :button
               :style (merge
                        (get severity-styles 2)
                        {:-fx-font-size 24})
               :h-box/margin 16
               :tooltip
               {:fx/type :tooltip
                :show-delay [10 :ms]
                :style {:-fx-font-size 16}
                :text "Nuke data directory"}
               :on-action {:event/type ::nuke-data-dir}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-nuke:32:white"}}]}
            {:fx/type :h-box
             :children
             [(let [map-filename (:filename battle-map-details)
                    map-isolation-file (spring/map-isolation-file      ; needs to be read from maps
                                         map-filename engine-version)] ; TODO allow different names
                {:fx/type resource-sync-pane
                 :h-box/margin 8
                 :resource "Map" ;battle-map ; (str "map (" battle-map ")")
                 ;:delete-action {:event/type ::delete-map}
                 :browse-action {:event/type ::desktop-browse-dir
                                 :file (or map-isolation-file (fs/maps-dir))}
                 :refresh-action {:event/type ::force-update-battle-map}
                 :issues
                 (let [map-download-file nil ; TODO
                       importable (some->> importables-by-path
                                           vals
                                           (filter (comp #{::map} :resource-type))
                                           (filter (comp #{battle-map} :resource-name))
                                           first)]
                   (concat
                     (let [url (http/map-url battle-map) ; TODO this is only a guess
                           download (get http-download url)
                           in-progress (:running download)
                           text (or (when in-progress (:message download))
                                    "download")
                           severity (if battle-map-details 0 2)]
                       [{:severity severity
                         :text text
                         :human-text (if (zero? severity)
                                       (:map-name battle-map-details)
                                       (str "Download map " (:map-name battle-map-details))) ; TODO filename
                         :tooltip (if (zero? severity)
                                    map-filename ; TODO map absolute path here
                                    (str "Download from " url))
                         :in-progress in-progress
                         :action {:event/type ::download-map
                                  :map-name battle-map}}])
                     (when (and map-download-file
                                (.exists map-download-file) ; FIXME IO IN RENDER
                                (not battle-map-details))
                       (let [in-progress (-> copying (get map-filename) :status)]
                         [{:severity 2
                           :text "copy"
                           :human-text "Copy map from downloads"
                           :tooltip (str "Copy from" map-download-file " to " map-isolation-file)
                           :in-progress in-progress
                           :action {:event/type ::copy-map
                                    :map-filename map-filename
                                    :engine-version engine-version}}]))
                     (when (and (not battle-map-details)
                                importable)
                       (when-let [resource-file (:resource-file importable)]
                         (let [absolute-path (.getAbsolutePath resource-file)]
                           [{:severity 2
                             :text "import"
                             :human-text (str "Import from " (:import-source-name importable))
                             :tooltip (str "Copy map archive from " absolute-path)
                             :in-progress (-> copying (get absolute-path) :status)
                             :action {:event/type ::copy-file
                                      :source resource-file
                                      :dest (fs/map-file (.getName resource-file))}}])))))})
              (let [absolute-path (:absolute-path battle-mod-details)
                    mod-file (when absolute-path (io/file absolute-path))]
                {:fx/type resource-sync-pane
                 :h-box/margin 8
                 :resource "Game" ;battle-modname ; (str "game (" battle-modname ")")
                 ;:delete-action {:event/type ::delete-mod}
                 :browse-action {:event/type ::desktop-browse-dir
                                 :file (or mod-file (fs/mods-dir))}
                 :refresh-action {:event/type ::force-update-battle-mod}
                 :issues
                 (concat
                   (let [git-url (cond
                                   (string/starts-with? battle-modname "Beyond All Reason")
                                   git/bar-repo-url
                                   (string/starts-with? battle-modname "Balanced Annihilation")
                                   git/ba-repo-url)
                         rapid-id (:id (get rapid-data-by-version battle-modname))
                         in-progress (or (-> rapid-download (get rapid-id) :running)
                                         (-> git-clone (get git-url) :status))
                         download-via :todo]
                     [{:severity (if battle-mod-details 0 2)
                       :text "download"
                       :human-text (if battle-mod-details
                                     (:mod-name battle-mod-details)
                                     (str "Download using" download-via))
                       :in-progress in-progress
                       :tooltip (str "Located at " absolute-path)
                       :action {:event/type ::download-mod
                                :mod-name battle-modname
                                :rapid-id rapid-id
                                :git-url git-url
                                :engine-dir-filename (spring/engine-dir-filename engines engine-version)}}])
                   (when-not battle-mod-details
                     (when-let [importable (some->> importables-by-path
                                                    vals
                                                    (filter (comp #{::mod} :resource-type))
                                                    (filter (comp #{battle-modname} :resource-name))
                                                    first)]
                       (let [resource-file (:resource-file importable)]
                         [{:severity 2
                           :text "import"
                           :human-text (str "Import from" resource-file)
                           :tooltip (str "Copy game dir from" (:import-source-name importable)
                                         " at " resource-file)
                           :in-progress (get copying resource-file)
                           :action {:event/type ::copy-file
                                    :source resource-file
                                    :dest (fs/mod-file (.getName resource-file))}}])))
                   (when (and (not battle-mod-details) mod-file)
                     (let [in-progress (-> copying (get (:filename battle-mod-details)) :status)]
                       [{:severity (if (and (.exists mod-file)
                                            (not in-progress))
                                     0 1)
                         :text "copy"
                         :in-progress in-progress
                         :action {:event/type ::copy-mod
                                  :mod-details battle-mod-details
                                  :engine-version engine-version}}]))
                   (when (and (= :directory
                                 (::fs/source battle-mod-details)))
                     (let [battle-mod-git-ref (mod-git-ref battle-modname)
                           severity (if (= battle-modname
                                           (:mod-name battle-mod-details))
                                      0 1)]
                       [(merge
                          {:severity severity
                           :text "git"
                           :in-progress (-> gitting (get absolute-path) :status)}
                          (if (= battle-mod-git-ref "$VERSION")
                            ; unspecified git commit ^
                            {:human-text (str "Unspecified git ref " battle-mod-git-ref)
                             :tooltip (str "SpringLobby does not specify version, "
                                           "yours may not be compatible")}
                            {:human-text (if (zero? severity)
                                           (str "git at ref " battle-mod-git-ref)
                                           (str "Reset git to ref " battle-mod-git-ref))
                             :action
                             {:event/type ::git-mod
                              :absolute-path absolute-path
                              :battle-mod-git-ref battle-mod-git-ref}}))])))})
              {:fx/type resource-sync-pane
               :h-box/margin 8
               :resource "Engine" ; engine-version ; (str "engine (" engine-version ")")
               ;:delete-action {:event/type ::delete-engine
               ;                :engines engines
               ;                :engine-version engine-version
               :browse-action {:event/type ::desktop-browse-dir
                               :file (or engine-dir-file
                                         (fs/engines-dir))}
               :issues
               (if (and (not (.exists engine-download-file)) ; FIXME IO IN RENDER
                        (or (not engine-dir-file)
                            (not (.exists engine-dir-file)))) ; FIXME IO IN RENDER
                 (if-let [importable (some->> importables-by-path
                                              vals
                                              (filter (comp #{::engine} :resource-type))
                                              (filter (comp #{engine-version} :resource-name))
                                              first)]
                   (let [resource-file (:resource-file importable)]
                     [{:severity 2
                       :text "import"
                       :human-text (str "Import from" resource-file)
                       :tooltip (str "Copy engine dir from" (:import-source-name importable)
                                     " at " resource-file)
                       :in-progress (get copying resource-file)
                       :action {:event/type ::import-engine ; TODO just ::import ?
                                :importable importable}}])
                   (let [url (http/engine-url engine-version)
                         download (get http-download url)
                         in-progress (:running download)
                         text (or (when in-progress (:message download))
                                  "download")
                         download-file engine-download-file]
                     [{:severity 2
                       :text text
                       :human-text "Download using http"
                       :tooltip (str "Download " url)
                       :in-progress in-progress
                       :action {:event/type ::http-download
                                :url url
                                :dest download-file}}]))
                 (let [severity (if (and engine-dir-file
                                         (.exists engine-dir-file))
                                  0 2)] ; FIXME IO IN RENDER
                   [{:severity severity
                     :text "extract"
                     :in-progress (get extracting engine-download-file)
                     :human-text (if (zero? severity)
                                   (:engine-version engine-details)
                                   "Extract engine archive")
                     :tooltip (if (zero? severity)
                                (str "Engine dir at " engine-absolute-path)
                                (str "Click to extract " engine-download-file))
                     :action {:event/type ::extract-7z
                              :file engine-download-file
                              :dest (io/file (fs/isolation-dir) "engine" engine-version)}}]))}]} ; TODO fn for engine dir
            {:fx/type :h-box
             :alignment :center-left
             :style {:-fx-font-size 24}
             :children
             [(let [{:keys [battle-status] :as me} (-> battle :users (get username))]
                {:fx/type :check-box
                 :selected (-> battle-status :ready boolean)
                 :style {:-fx-padding "10px"}
                 :on-selected-changed (merge me
                                        {:event/type ::battle-ready-change
                                         :username username})})
              {:fx/type :label
               :text " Ready"}
              {:fx/type :pane
               :h-box/hgrow :always}
              {:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :style {:-fx-font-size 12}
                 :text (if am-host
                         "You are the host, start battle for everyone"
                         (str "Waiting for host " host-username))}}
               :desc
               (let [iam-ingame (-> users (get username) :client-status :ingame)]
                 {:fx/type :button
                  :text (if iam-ingame
                          "Game started"
                          (str (if am-host "Start" "Join") " Game"))
                  :disable (boolean (or (and (not am-host)
                                             (not (-> host-user :client-status :ingame)))
                                        iam-ingame))
                  :on-action {:event/type ::start-battle}})}]}]}
          {:fx/type :pane
           :h-box/hgrow :always}
          {:fx/type :v-box
           :alignment :top-left
           :h-box/hgrow :always
           :children
           [{:fx/type :label
             :text "modoptions"}
            {:fx/type :table-view
             :column-resize-policy :constrained
             :items (or (some->> battle-mod-details
                                 :modoptions
                                 (map second)
                                 (filter :key)
                                 (map #(update % :key (comp keyword string/lower-case)))
                                 (sort-by :key)
                                 (remove (comp #{"section"} :type)))
                        [])
             :columns
             [{:fx/type :table-column
               :text "Key"
               :cell-value-factory identity
               :cell-factory
               {:fx/cell-type :table-cell
                :describe
                (fn [i]
                  {:text ""
                   :graphic
                   {:fx/type fx.ext.node/with-tooltip-props
                    :props
                    {:tooltip
                     {:fx/type :tooltip
                      :show-delay [10 :ms]
                      :text (str (:name i) "\n\n" (:desc i))}}
                    :desc
                    (merge
                      {:fx/type :label
                       :text (or (some-> i :key name str)
                                 "")}
                      (when-let [v (-> battle :scripttags :game :modoptions (get (:key i)))]
                        (when (not (spring-script/tag= i v))
                          {:style {:-fx-font-weight :bold}})))}})}}
              {:fx/type :table-column
               :text "Value"
               :cell-value-factory identity
               :cell-factory
               {:fx/cell-type :table-cell
                :describe
                (fn [i]
                  (let [v (-> battle :scripttags :game :modoptions (get (:key i)))]
                    (case (:type i)
                      "bool"
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (str (:key i))
                        :desc
                        {:fx/type fx.ext.node/with-tooltip-props
                         :props
                         {:tooltip
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
                           :text (str (:name i) "\n\n" (:desc i))}}
                         :desc
                         {:fx/type :check-box
                          :selected (u/to-bool (or v (:def i)))
                          :on-selected-changed {:event/type ::modoption-change
                                                :modoption-key (:key i)}
                          :disable (not am-host)}}}}
                      "number"
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (str (:key i))
                        :desc
                        {:fx/type fx.ext.node/with-tooltip-props
                         :props
                         {:tooltip
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
                           :text (str (:name i) "\n\n" (:desc i))}}
                         :desc
                         {:fx/type :text-field
                          :disable (not am-host)
                          :text-formatter
                          {:fx/type :text-formatter
                           :value-converter :number
                           :value (u/to-number (or v (:def i)))
                           :on-value-changed {:event/type ::modoption-change
                                              :modoption-key (:key i)}}}}}}
                      "list"
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (str (:key i))
                        :desc
                        {:fx/type fx.ext.node/with-tooltip-props
                         :props
                         {:tooltip
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
                           :text (str (:name i) "\n\n" (:desc i))}}
                         :desc
                         {:fx/type :choice-box
                          :disable (not am-host)
                          :value (or v (:def i))
                          :on-value-changed {:event/type ::modoption-change
                                             :modoption-key (:key i)}
                          :items (or (map (comp :key second) (:items i))
                                     [])}}}}
                      {:text (str (:def i))})))}}]}]}
          {:fx/type :v-box
           :alignment :top-left
           :children
           [{:fx/type :label
             :text "script.txt preview"}
            {:fx/type :text-area
             :editable false
             :text (str (string/replace (spring/battle-script-txt @*state) #"\t" "  "))
             :style {:-fx-font-family "monospace"}
             :v-box/vgrow :always}]}]}]}
      {:fx/type :v-box
       :alignment :top-left
       :children
       [
        {:fx/type :stack-pane
         :on-scroll {:event/type ::minimap-scroll}
         :style
         {:-fx-min-width minimap-size
          :-fx-max-width minimap-size
          :-fx-min-height minimap-size
          :-fx-max-height minimap-size}
         :children
         (concat
           (if minimap-image
             (let [image (SwingFXUtils/toFXImage minimap-image nil)]
               [{:fx/type :image-view
                 :image image
                 :fit-width minimap-width
                 :fit-height minimap-height
                 :preserve-ratio true}])
             [{:fx/type :v-box
               :alignment :center
               :children
               [
                {:fx/type :label
                 :text (str battle-map)
                 :style {:-fx-font-size 16}}
                {:fx/type :label
                 :text "(not found)"
                 :alignment :center}]}])
           [(merge
              (when am-host
                {:on-mouse-pressed {:event/type ::minimap-mouse-pressed
                                    :startpostype startpostype
                                    :starting-points starting-points}
                 :on-mouse-dragged {:event/type ::minimap-mouse-dragged
                                    :startpostype startpostype
                                    :starting-points starting-points}
                 :on-mouse-released {:event/type ::minimap-mouse-released
                                     :startpostype startpostype
                                     :map-details battle-map-details
                                     :minimap-width minimap-width
                                     :minimap-height minimap-height}})
              {:fx/type :canvas
               :width minimap-width
               :height minimap-height
               :draw
               (fn [^javafx.scene.canvas.Canvas canvas]
                 (let [gc (.getGraphicsContext2D canvas)
                       border-color (if (= "minimap" minimap-type)
                                      Color/BLACK Color/WHITE)]
                   (.clearRect gc 0 0 minimap-width minimap-height)
                   (.setFill gc Color/RED)
                   (doseq [{:keys [x y team color]} starting-points]
                     (let [drag (when (and drag-team
                                           (= team (:team drag-team)))
                                  drag-team)
                           x (or (:x drag) x)
                           y (or (:y drag) y)
                           xc (- x (if (= 1 (count team))
                                     (* start-pos-r -0.6)
                                     (* start-pos-r -0.2)))
                           yc (+ y (/ start-pos-r 0.75))]
                       (cond
                         (#{"Fixed" "Choose before game"} startpostype)
                         (do
                           (.beginPath gc)
                           (.rect gc x y
                                  (* 2 start-pos-r)
                                  (* 2 start-pos-r))
                           (.setFill gc color)
                           (.fill gc)
                           (.setStroke gc border-color)
                           (.stroke gc)
                           (.closePath gc)
                           (.setStroke gc Color/BLACK)
                           (.strokeText gc team xc yc)
                           (.setFill gc Color/WHITE)
                           (.fillText gc team xc yc))
                         :else
                         (.fillOval gc x y start-pos-r start-pos-r))))))})])}
        {:fx/type :h-box
         :alignment :center-left
         :children
         [
          {:fx/type :label
           :text (str " Size: "
                      (when-let [{:keys [map-width map-height]} (-> battle-map-details :smf :header)]
                        (str
                          (when map-width (quot map-width 64))
                          " x "
                          (when map-height (quot map-height 64)))))}
          {:fx/type :pane
           :h-box/hgrow :always}
          {:fx/type :combo-box
           :value minimap-type
           :items minimap-types
           :on-value-changed {:event/type ::minimap-type-change}}]}
        {:fx/type :h-box
         :style {:-fx-max-width minimap-size}
         :children
         [{:fx/type map-list
           :disable (not am-host)
           :map-name battle-map
           :maps maps
           :map-input-prefix map-input-prefix
           :on-value-changed {:event/type ::battle-map-change}}]}
        {:fx/type :h-box
         :alignment :center-left
         :children
         (concat
           [{:fx/type :label
             :alignment :center-left
             :text " Start Positions: "}
            {:fx/type :choice-box
             :value startpostype
             :items (map str (vals spring/startpostypes))
             :disable (not am-host)
             :on-value-changed {:event/type ::battle-startpostype-change}}]
           (when (= "Choose before game" startpostype)
             [{:fx/type :button
               :text "Reset"
               :disable (not am-host)
               :on-action {:event/type ::reset-start-positions}}]))}
        {:fx/type :h-box
         :alignment :center-left
         :children
         (concat
           (when am-host
             [{:fx/type :button
               :text "FFA"
               :on-action {:event/type ::battle-teams-ffa
                           :battle battle
                           :users users
                           :username username}}
              {:fx/type :button
               :text "2 teams"
               :on-action {:event/type ::battle-teams-2
                           :battle battle
                           :users users
                           :username username}}
              {:fx/type :button
               :text "3 teams"
               :on-action {:event/type ::battle-teams-3
                           :battle battle
                           :users users
                           :username username}}
              {:fx/type :button
               :text "4 teams"
               :on-action {:event/type ::battle-teams-4
                           :battle battle
                           :users users
                           :username username}}
              {:fx/type :button
               :text "Humans vs Bots"
               :on-action {:event/type ::battle-teams-humans-vs-bots
                           :battle battle
                           :users users
                           :username username}}]))}]}]}))


(defmethod event-handler ::battle-startpostype-change
  [{:fx/keys [event]}]
  (let [startpostype (get spring/startpostypes-by-name event)]
    (swap! *state assoc-in [:scripttags :game :startpostype] startpostype)
    (swap! *state assoc-in [:battle :scripttags :game :startpostype] startpostype)
    (message/send-message (:client @*state) (str "SETSCRIPTTAGS game/startpostype=" startpostype))))

(defmethod event-handler ::reset-start-positions
  [_e]
  (let [team-ids (take 16 (iterate inc 0))
        scripttag-keys (map (fn [i] (str "game/team" i)) team-ids)]
    (doseq [i team-ids]
      (let [team (keyword (str "team" i))]
        (swap! *state update-in [:scripttags :game] dissoc team)
        (swap! *state update-in [:battle :scripttags :game] dissoc team)))
    (message/send-message (:client @*state) (str "REMOVESCRIPTTAGS " (string/join " " scripttag-keys)))))

(defmethod event-handler ::modoption-change
  [{:keys [modoption-key] :fx/keys [event]}]
  (let [value (str event)]
    (swap! *state assoc-in [:scripttags :game :modoptions modoption-key] (str event))
    (swap! *state assoc-in [:battle :scripttags :game :modoptions modoption-key] (str event))
    (message/send-message (:client @*state) (str "SETSCRIPTTAGS game/modoptions/" (name modoption-key) "=" value))))

(defmethod event-handler ::battle-ready-change
  [{:fx/keys [event] :keys [battle-status team-color] :as id}]
  (update-battle-status @*state {:id id} (assoc battle-status :ready event) team-color))


(defmethod event-handler ::battle-spectate-change
  [{:keys [id is-me is-bot] :fx/keys [event] :as data}]
  (if (or is-me is-bot)
    (update-battle-status @*state data
      (assoc (:battle-status id) :mode (not event))
      (:team-color id))
    (message/send-message (:client @*state)
      (str "FORCESPECTATORMODE " (:username id)))))

(defmethod event-handler ::battle-side-changed
  [{:keys [id] :fx/keys [event] :as data}]
  (when-let [side (try (Integer/parseInt event) (catch Exception _e))]
    (if (not= side (-> id :battle-status :side))
      (do
        (log/info "Updating side for" id "from" (-> id :battle-status :side) "to" side)
        (update-battle-status @*state data (assoc (:battle-status id) :side side) (:team-color id)))
      (log/debug "No change for side"))))

(defmethod event-handler ::battle-team-changed
  [{:keys [id] :fx/keys [event] :as data}]
  (when-let [player-id (try (Integer/parseInt event) (catch Exception _e))]
    (if (not= player-id (-> id :battle-status :id))
      (do
        (log/info "Updating team for" id "from" (-> id :battle-status :side) "to" player-id)
        (update-team id data player-id))
      (log/debug "No change for team"))))

(defmethod event-handler ::battle-ally-changed
  [{:keys [id] :fx/keys [event] :as data}]
  (when-let [ally (try (Integer/parseInt event) (catch Exception _e))]
    (if (not= ally (-> id :battle-status :ally))
      (do
        (log/info "Updating ally for" id "from" (-> id :battle-status :ally) "to" ally)
        (update-ally id data ally))
      (log/debug "No change for ally"))))

(defmethod event-handler ::battle-handicap-change
  [{:keys [id] :fx/keys [event] :as data}]
  (when-let [handicap (max 0
                        (min 100
                          event))]
    (if (not= handicap (-> id :battle-status :handicap))
      (do
        (log/info "Updating handicap for" id "from" (-> id :battle-status :ally) "to" handicap)
        (update-handicap id data handicap))
      (log/debug "No change for handicap"))))

(defmethod event-handler ::battle-color-action
  [{:keys [id is-me] :fx/keys [^javafx.event.Event event] :as opts}]
  (let [^javafx.scene.control.ComboBoxBase source (.getSource event)
        javafx-color (.getValue source)
        color-int (spring-color javafx-color)]
    (when is-me
      (swap! *state assoc :preferred-color color-int))
    (update-color id opts color-int)))

(defmethod event-handler ::rapid-repo-change
  [{:fx/keys [event]}]
  (future
    (try
      (swap! *state assoc :rapid-repo event)
      (let [versions (->> (rapid/versions event)
                          (sort-by :version version/version-compare)
                          reverse
                          doall)]
        (swap! *state assoc :rapid-versions-cached versions))
      (catch Exception e
        (log/error e)))))

(defmethod event-handler ::rapid-download
  [{:keys [engine-dir-filename rapid-id]}]
  (swap! *state assoc-in [:rapid-download rapid-id] {:running true
                                                     :message "Preparing to run pr-downloader"})
  (future
    (try
      (let [pr-downloader-file (io/file (fs/spring-root) "engine" engine-dir-filename (fs/executable "pr-downloader"))
            command [(.getAbsolutePath pr-downloader-file)
                     "--filesystem-writepath" (fs/wslpath (fs/spring-root))
                     "--rapid-download" rapid-id]
            runtime (Runtime/getRuntime)]
        (log/info "Running '" command "'")
        (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
              ^"[Ljava.lang.String;" envp nil
              ^java.lang.Process process (.exec runtime cmdarray envp (fs/spring-root))]
          (future
            (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (swap! *state assoc-in [:rapid-download rapid-id :message] line)
                    (log/info "(pr-downloader" rapid-id "out)" line)
                    (recur))
                  (log/info "pr-downloader" rapid-id "stdout stream closed")))))
          (future
            (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (swap! *state assoc-in [:rapid-download rapid-id :message] line)
                    (log/info "(pr-downloader" rapid-id "err)" line)
                    (recur))
                  (log/info "pr-downloader" rapid-id "stderr stream closed")))))
          (.waitFor process)
          (swap! *state assoc-in [:rapid-download rapid-id :running] false)
          (swap! *state assoc :sdp-files-cached (doall (rapid/sdp-files)))))
      (catch Exception e
        (log/error e "Error downloading" rapid-id)
        (swap! *state assoc-in [:rapid-download rapid-id :message] (.getMessage e)))
      (finally
        (swap! *state assoc-in [:rapid-download rapid-id :running] false)
        (reconcile-mods *state)))))

(defmethod event-handler ::engine-branch-change
  [{:keys [engine-branch] :fx/keys [event]}]
  (let [engine-branch (or engine-branch event)]
    (swap! *state assoc :engine-branch engine-branch)
    (future
      (try
        (log/debug "Getting engine versions for branch" engine-branch)
        (let [versions (->> (http/springrts-buildbot-files [engine-branch])
                            (sort-by :filename version/version-compare)
                            reverse
                            doall)]
          (log/debug "Got engine versions" (pr-str versions))
          (swap! *state assoc :engine-versions-cached versions))
        (catch Exception e
          (log/error e))))))

(defmethod event-handler ::maps-index-change
  [{:keys [maps-index-url] :fx/keys [event]}]
  (let [maps-index-url (or maps-index-url event)]
    (swap! *state assoc :maps-index-url maps-index-url)
    (future
      (try
        (log/debug "Getting maps from" maps-index-url)
        (let [map-files (->> (http/files (html/parse maps-index-url))
                             (sort-by :filename)
                             doall)]
          (log/debug "Got maps" (pr-str map-files))
          (swap! *state assoc :map-files-cache map-files))
        (catch Exception e
          (log/error e))))))

(defmethod event-handler ::mods-index-change
  [{:keys [mods-index-url] :fx/keys [event]}]
  (let [mods-index-url (or mods-index-url event)]
    (swap! *state assoc :mods-index-url mods-index-url)
    (future
      (try
        (log/debug "Getting mods from" mods-index-url)
        (let [mod-files (->> (http/files (html/parse mods-index-url))
                             (sort-by :filename)
                             doall)]
          (log/debug "Got mods" (pr-str mod-files))
          (swap! *state assoc :mod-files-cache mod-files))
        (catch Exception e
          (log/error e))))))



; https://github.com/dakrone/clj-http/pull/220/files
(defn print-progress-bar
  "Render a simple progress bar given the progress and total. If the total is zero
   the progress will run as indeterminated."
  ([progress total] (print-progress-bar progress total {}))
  ([progress total {:keys [bar-width]
                    :or   {bar-width 10}}]
   (if (pos? total)
     (let [pct (/ progress total)
           render-bar (fn []
                        (let [bars (Math/floor (* pct bar-width))
                              pad (- bar-width bars)]
                          (str (clojure.string/join (repeat bars "="))
                               (clojure.string/join (repeat pad " ")))))]
       (print (str "[" (render-bar) "] "
                   (int (* pct 100)) "% "
                   progress "/" total)))
     (let [render-bar (fn [] (clojure.string/join (repeat bar-width "-")))]
       (print (str "[" (render-bar) "] "
                   progress "/?"))))))

(defn insert-at
  "Addes value into a vector at an specific index."
  [v idx value]
  (-> (subvec v 0 idx)
      (conj value)
      (into (subvec v idx))))

(defn insert-after
  "Finds an item into a vector and adds val just after it.
   If needle is not found, the input vector will be returned."
  [^clojure.lang.APersistentVector v needle value]
  (let [index (.indexOf v needle)]
    (if (neg? index)
      v
      (insert-at v (inc index) value))))

(defn wrap-downloaded-bytes-counter
  "Middleware that provides an CountingInputStream wrapping the stream output"
  [client]
  (fn [req]
    (let [resp (client req)
          counter (CountingInputStream. (:body resp))]
      (merge resp {:body                     counter
                   :downloaded-bytes-counter counter}))))


(defmethod event-handler ::http-download
  [{:keys [dest url]}]
  (swap! *state assoc-in [:http-download url] {:running true
                                               :message "Preparing to download..."})
  (log/info "Request to download" url "to" dest)
  (future
    (try
      (let [parent (.getParentFile dest)]
        (.mkdirs parent))
      (clj-http/with-middleware
        (-> clj-http/default-middleware
            (insert-after clj-http/wrap-url wrap-downloaded-bytes-counter)
            (conj clj-http/wrap-lower-case-headers))
        (let [request (clj-http/get url {:as :stream})
              ^String content-length (get-in request [:headers "content-length"] "0")
              length (Integer/valueOf content-length)
              buffer-size (* 1024 10)]
          (with-open [^java.io.InputStream input (:body request)
                      output (io/output-stream dest)]
            (let [buffer (make-array Byte/TYPE buffer-size)
                  ^CountingInputStream counter (:downloaded-bytes-counter request)]
              (loop []
                (let [size (.read input buffer)]
                  (when (pos? size)
                    (.write output buffer 0 size)
                    (when counter
                      (let [msg (with-out-str
                                  (print-progress-bar
                                    (.getByteCount counter)
                                    length))]
                        (swap! *state assoc-in [:http-download url :message] msg)))
                    (recur))))))))
      (catch Exception e
        (log/error e "Error downloading" url "to" dest))
      (finally
        (swap! *state assoc-in [:http-download url :running] false)
        (log/info "Finished downloading" url "to" dest)))))


(defmethod event-handler ::extract-7z
  [{:keys [file dest]}]
  (future
    (try
      (swap! *state assoc-in [:extracting file] true)
      (if dest
        (fs/extract-7z file dest)
        (fs/extract-7z file))
      (reconcile-engines *state)
      (catch Exception e
        (log/error e "Error extracting 7z" file))
      (finally
        (swap! *state assoc-in [:extracting file] false)))))


(defn import-type [t source f]
  {:resource-file f
   :resource-type t
   :import-source-name source})

(defmethod task-handler ::scan-imports
  [{root :file import-source-name :import-source-name}]
  (log/info "Scanning for possible imports from" root)
  (let [map-files (fs/map-files root)
        mod-files (fs/mod-files root)
        engine-dirs (fs/engine-dirs root)
        sdp-files (rapid/sdp-files root)
        importables (concat
                      (map (partial import-type ::map import-source-name) map-files)
                      (map (partial import-type ::mod import-source-name) (concat mod-files sdp-files))
                      (map (partial import-type ::engine import-source-name) engine-dirs))
        importables-by-path (->> importables
                                 (map (juxt #(.getAbsolutePath (:resource-file %)) identity))
                                 (into {}))]
    (log/info "Found imports" {:map-files (count map-files)
                               :mod-files (count mod-files)
                               :engine-dirs (count engine-dirs)
                               :sdp-files (count sdp-files)})
    (swap! *state update :importables-by-path merge importables-by-path)
    (doseq [{:keys [resource-file resource-type] :as importable} importables]
      (try
        (log/info "Finding name for importable" importable)
        (let [resource-name (case resource-type
                              ::map (:map-name (fs/read-map-data resource-file))
                              ::mod (:mod-name (read-mod-data resource-file))
                              ::engine (:engine-version (fs/engine-data resource-file))
                              ::sdp (:mod-name (read-mod-data resource-file)))]
          (swap! *state update-in [:importables-by-path (.getAbsolutePath resource-file)]
                 assoc :resource-name resource-name
                 :resource-updated (u/curr-millis))
          (Thread/sleep 100))
        (catch Exception e
          (log/error e "Error reading importable" importable))))))

(defmethod event-handler ::import-source-change
  [{:fx/keys [event]}]
  (swap! *state assoc :import-source-name (:import-source-name event))
  #_
  (add-task! *state {::task-type ::scan-imports
                     :file (:file event)
                     :import-source-name event}))

(defn import-source-cell
  [{:keys [file import-source-name]}]
  {:text (str import-source-name
              (when file
                (str " ( at " (.getAbsolutePath file) " )")))})

(defn import-window
  [{:keys [import-source-name importables-by-path show-importer]}]
  (let [import-source (->> import-sources
                           (filter (comp #{import-source-name} :import-source-name))
                           first)]
    {:fx/type :stage
     :x 400
     :y 400
     :showing show-importer
     :title "alt-spring-lobby Importer"
     :on-close-request (fn [& args]
                         (log/debug args)
                         (swap! *state assoc :show-importer false))
     :width download-window-width
     :height download-window-height
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      {:fx/type :v-box
       :children
       [{:fx/type :h-box
         :alignment :center-left
         :style {:-fx-font-size 16}
         :children
         (concat
           [{:fx/type :label
             :text " Import from: "}
            {:fx/type :combo-box
             :value import-source
             :items import-sources
             :button-cell import-source-cell
             ;:placeholder {:import-source-name " < pick a source > "} TODO figure out placeholders
             :cell-factory
             {:fx/cell-type :list-cell
              :describe import-source-cell}
             :on-value-changed {:event/type ::import-source-change}
             :tooltip {:fx/type :tooltip
                       :show-delay [10 :ms]
                       :text "Choose import source"}}]
           (when import-source
             [{:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :text "Open import source directory"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::desktop-browse-dir
                            :file (:file import-source)}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-folder:16:white"}}}]))}
        {:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :v-box/vgrow :always
         :items (or (vals importables-by-path) [])
         :columns
         [{:fx/type :table-column
           :text "Type"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:resource-type i))})}}
          {:fx/type :table-column
           :text "Name"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:resource-name i))})}}
          {:fx/type :table-column
           :text "Path"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:resource-file i))})}}
          {:fx/type :table-column
           :text "Import"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [_i] {:text "TODO Import buttons"})}}]}]}}}))


(defn rapid-download-window
  [{:keys [engine-version engines rapid-download rapid-repo rapid-repos-cached rapid-versions-cached
           rapid-versions-by-hash sdp-files-cached show-rapid-downloader]}]
  (let [sdp-files (or sdp-files-cached [])
        sdp-hashes (set (map rapid/sdp-hash sdp-files))]
    {:fx/type :stage
     :showing show-rapid-downloader
     :title "alt-spring-lobby Rapid Downloader"
     :on-close-request (fn [& args]
                         (log/debug args)
                         (swap! *state assoc :show-rapid-downloader false))
     :width download-window-width
     :height download-window-height
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      {:fx/type :v-box
       :children
       [{:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Repo: "}
          {:fx/type :choice-box
           :value (str rapid-repo)
           :items (or rapid-repos-cached [])
           :on-value-changed {:event/type ::rapid-repo-change}}
          {:fx/type :label
           :text " Engine for pr-downloader: "}
          {:fx/type :choice-box
           :value (str engine-version)
           :items (or (->> engines
                           (map :engine-version)
                           sort)
                      [])
           :on-value-changed {:event/type ::version-change}}]}
        {:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :items (or rapid-versions-cached [])
         :columns
         [{:fx/type :table-column
           :text "ID"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:id i))})}}
          {:fx/type :table-column
           :text "Hash"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:hash i))})}}
          {:fx/type :table-column
           :text "Version"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:version i))})}}
          {:fx/type :table-column
           :text "Download"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              (let [download (get rapid-download (:id i))]
                (merge
                  {:text (str (:message download))
                   :style {:-fx-font-family "monospace"}}
                  (cond
                    (sdp-hashes (:hash i))
                    {:graphic
                     {:fx/type font-icon/lifecycle
                      :icon-literal "mdi-check:16:white"}}
                    (:running download)
                    nil
                    :else
                    {:graphic
                     {:fx/type :button
                      :on-action {:event/type ::rapid-download
                                  :rapid-id (:id i)
                                  :engine-dir-filename (spring/engine-dir-filename engines engine-version)}
                      :graphic
                      {:fx/type font-icon/lifecycle
                       :icon-literal "mdi-download:16:white"}}}))))}}]}
        {:fx/type :label
         :text " Packages"}
        {:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :items sdp-files
         :columns
         [{:fx/type :table-column
           :text "Filename"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [^java.io.File i]
              {:text (str (.getName i))})}}
          {:fx/type :table-column
           :text "ID"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (->> i
                          rapid/sdp-hash
                          (get rapid-versions-by-hash)
                          :id
                          str)})}}
          {:fx/type :table-column
           :text "Version"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (->> i
                          rapid/sdp-hash
                          (get rapid-versions-by-hash)
                          :version
                          str)})}}]}]}}}))


(defn http-download-window
  [{:keys [engine-branch engine-versions-cached engines extracting http-download map-files-cache
           maps-index-url mod-files-cache mods-index-url show-http-downloader]}]
  (let [engine-branches http/engine-branches]
    {:fx/type :stage
     :showing show-http-downloader
     :title "alt-spring-lobby HTTP Downloader"
     :on-close-request (fn [& args]
                         (log/debug args)
                         (swap! *state assoc :show-http-downloader false))
     :width download-window-width
     :height download-window-height
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      {:fx/type :v-box
       :children
       [{:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Engine branch: "}
          {:fx/type :choice-box
           :value (str engine-branch)
           :items (or engine-branches [])
           :on-value-changed {:event/type ::engine-branch-change}}
          {:fx/type :button
           :on-action {:event/type ::engine-branch-change
                       :engine-branch engine-branch}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-refresh:16:white"}}]}
        {:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :items (or (filter :url engine-versions-cached)
                    [])
         :columns
         [{:fx/type :table-column
           :text "Link"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:filename i))})}}
          {:fx/type :table-column
           :text "Archive URL"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              (if-let [url (:url i)]
                (let [[_all version] (re-find #"(.*)/" url)
                      engine-path (http/engine-path engine-branch version)]
                  {:text (str engine-path)})
                {:text "-"}))}}
          {:fx/type :table-column
           :text "Date"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:date i))})}}
          {:fx/type :table-column
           :text "Size"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:size i))})}}
          {:fx/type :table-column
           :text "Download"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              (if-let [url (:url i)]
                (let [[_all version] (re-find #"(.*)/" url)
                      url (http/engine-url version)
                      download (get http-download url)
                      dest (engine-dest version)
                      engine-version (str version
                                       (when (not= http/default-engine-branch engine-branch)
                                         (str " " engine-branch)))]
                  (merge
                    {:text (str (:message download))
                     :style {:-fx-font-family "monospace"}}
                    (cond
                      (not dest)
                      nil
                      (.exists dest)
                      {:graphic
                       (if (some #{engine-version} (map :engine-version engines))
                         {:fx/type font-icon/lifecycle
                          :icon-literal "mdi-check:16:white"}
                         {:fx/type :button
                          :disable (boolean (or (:running download)
                                                (get extracting dest)))
                          :on-action {:event/type ::extract-7z
                                      :file dest}
                          :tooltip {:fx/type :tooltip
                                    :show-delay [10 :ms]
                                    :text (if (get extracting dest)
                                            "Extracting..."
                                            (str "Extract " version))}
                          :graphic
                          {:fx/type font-icon/lifecycle
                           :icon-literal "mdi-package-variant:16:white"}})}
                      (:running download)
                      nil
                      :else
                      {:graphic
                       {:fx/type :button
                        :on-action {:event/type ::http-download
                                    :url url
                                    :dest dest}
                        :graphic
                        {:fx/type font-icon/lifecycle
                         :icon-literal "mdi-download:16:white"}}})))
                {:text "-"}))}}]}
        {:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Games index URL: "}
          {:fx/type :choice-box
           :value (str mods-index-url)
           :items [http/springfightclub-root]
           :on-value-changed {:event/type ::mods-index-change}}
          {:fx/type :button
           :on-action {:event/type ::mods-index-change
                       :mods-index-url mods-index-url}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-refresh:16:white"}}]}
        {:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :items (or mod-files-cache
                    [])
         :columns
         [{:fx/type :table-column
           :text "Filename"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:filename i))})}}
          {:fx/type :table-column
           :text "URL"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:url i))})}}
          {:fx/type :table-column
           :text "Date"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:date i))})}}
          {:fx/type :table-column
           :text "Size"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:size i))})}}
          {:fx/type :table-column
           :text "Download"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              (let [url (str mods-index-url "/" (:url i))
                    download (get http-download url)
                    dest (io/file (fs/spring-root) "games" (:filename i))]
                (merge
                  {:text (str (:message download))
                   :style {:-fx-font-family "monospace"}}
                  (cond
                    (or (not (:size i)) (= "-" (string/trim (:size i))))
                    nil
                    (.exists dest)
                    {:graphic
                     {:fx/type font-icon/lifecycle
                      :icon-literal "mdi-check:16:white"}}
                    (:running download)
                    nil
                    :else
                    {:graphic
                     {:fx/type :button
                      :on-action {:event/type ::http-download
                                  :url url
                                  :dest dest}
                      :graphic
                      {:fx/type font-icon/lifecycle
                       :icon-literal "mdi-download:16:white"}}}))))}}]}
        {:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Maps index URL: "}
          {:fx/type :choice-box
           :value (str maps-index-url)
           :items [http/springfiles-maps-url
                   (str http/springfightclub-root "/maps")]
           :on-value-changed {:event/type ::maps-index-change}}
          {:fx/type :button
           :on-action {:event/type ::maps-index-change
                       :maps-index-url maps-index-url}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-refresh:16:white"}}]}
        {:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :items (or map-files-cache [])
         :columns
         [{:fx/type :table-column
           :text "Filename"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:filename i))})}}
          {:fx/type :table-column
           :text "URL"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:url i))})}}
          {:fx/type :table-column
           :text "Date"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:date i))})}}
          {:fx/type :table-column
           :text "Size"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (:size i))})}}
          {:fx/type :table-column
           :text "Download"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [{:keys [filename url]}]
              (if filename
                (let [url (str maps-index-url "/" url)
                      download (get http-download url)
                      dest (io/file (fs/download-dir) "maps" filename)]
                  (merge
                    {:text (str (:message download))
                     :style {:-fx-font-family "monospace"}}
                    (cond
                      (.exists dest)
                      {:graphic
                       {:fx/type font-icon/lifecycle
                        :icon-literal "mdi-check:16:white"}}
                      (:running download)
                      nil
                      :else
                      {:graphic
                       {:fx/type :button
                        :on-action {:event/type ::http-download
                                    :url url
                                    :dest dest}
                        :graphic
                        {:fx/type font-icon/lifecycle
                         :icon-literal "mdi-download:16:white"}}})))
                {:text "-"}))}}]}]}}}))


; TODO remove and delay most of this stuff
(defn root-on-created [& args]
  (log/trace "on-created" args)
  #_
  (doseq [import-source import-sources]
    (add-task! *state (merge
                        {::task-type ::scan-imports}
                        import-source)))
  (add-task! *state {::task-type ::reconcile-engines})
  (add-task! *state {::task-type ::reconcile-mods})
  (add-task! *state {::task-type ::reconcile-maps})
  #_
  (swap! *state assoc :sdp-files-cached
    (delay
      (try
        (doall (rapid/sdp-files))
        (catch Exception e
          (log/error e "Error loading SDP files")))))
  #_
  (swap! *state assoc :rapid-cache
    (delay
      (try
        (let [rapid-repos (sort (rapid/repos))]
          {:rapid-repos-cached rapid-repos
           :rapid-versions-by-hash
           (->> rapid-repos
                (mapcat rapid/versions)
                (map (juxt :hash identity))
                (into {}))
           :rapid-data-by-version
           (->> rapid-repos
                (mapcat rapid/versions)
                (map (juxt :version identity))
                (into {}))})
        (catch Exception e
          (log/error e "Error loading rapid versions by hash")))))
  #_
  (future
    (try
      (when-let [rapid-repo (:rapid-repo @*state)]
        (let [versions (->> (rapid/versions rapid-repo)
                            (sort-by :version version/version-compare)
                            reverse
                            doall)]
          (swap! *state assoc :rapid-versions-cached versions)))
      (catch Exception e
        (log/error e "Error loading rapid versions")))))

(defn main-window-on-close-request
  [standalone e]
  (log/debug "Main window close request" e)
  (when standalone
    (loop []
      (let [^SplicedStream client (:client @*state)]
        (if (and client (not (.isClosed client)))
          (do
            (client/disconnect client)
            (recur))
          (System/exit 0))))))

(defn root-view
  [{{:keys [battle battles file-events last-failed-message pop-out-battle show-http-downloader
            show-importer show-rapid-downloader standalone tasks users]
     :as state}
    :state}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created root-on-created
   :on-advanced (fn [& args]
                  (log/trace "on-advanced" args))
   :on-deleted (fn [& args]
                 (log/trace "on-deleted" args))
   :desc
   {:fx/type fx/ext-many
    :desc
    (concat
      [{:fx/type :stage
        :showing true
        :title "Alt Spring Lobby"
        :x 100
        :y 100
        :width main-window-width
        :height main-window-height
        :on-close-request (partial main-window-on-close-request standalone)
        :scene
        {:fx/type :scene
         :stylesheets stylesheets
         :root
         {:fx/type :v-box
          :alignment :top-left
          :children
          (concat
            [(merge
               {:fx/type client-buttons}
               (select-keys state
                 [:client :client-deferred :username :password :login-error
                  :server-url]))
             {:fx/type :split-pane
              :v-box/vgrow :always
              :divider-positions [0.75]
              :items
              [{:fx/type :v-box
                :children
                [{:fx/type :label
                  :text "Battles"
                  :style {:-fx-font-size 16}}
                 {:fx/type battles-table
                  :v-box/vgrow :always
                  :battles battles
                  :users users}]}
               {:fx/type :v-box
                :children
                [{:fx/type :label
                  :text "Users"
                  :style {:-fx-font-size 16}}
                 {:fx/type users-table
                  :v-box/vgrow :always
                  :users users}]}]}
             (merge
               {:fx/type battles-buttons}
               (select-keys state
                 [:battle :battle-password :battle-title :battles :client :engines :engine-filter
                  :engine-version :map-input-prefix :map-name :maps :mod-filter :mod-name :mods
                  :pop-out-battle :scripttags :selected-battle]))]
            (when battle
              (if (:battle-id battle)
                (when (not pop-out-battle)
                  [(merge
                     {:fx/type battle-view}
                     (select-keys state
                       [:archiving :battles :battle :battle-map-details :battle-mod-details :bot-name
                        :bot-username :bot-version :cleaning :copying :drag-team :engine-version
                        :engines :extracting :git-clone :gitting :http-download :importables-by-path
                        :isolation-type
                        :map-input-prefix :maps :minimap-type :mods :rapid-data-by-version
                        :rapid-download :username :users]))])
                [{:fx/type :h-box
                  :alignment :top-left
                  :children
                  [{:fx/type :v-box
                    :h-box/hgrow :always
                    :children
                    [{:fx/type :label
                      :style {:-fx-font-size 20}
                      :text "Waiting for server to start battle..."}]}]}]))
            [{:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :label
                :text (str last-failed-message)
                :style {:-fx-text-fill "#FF0000"}}
               {:fx/type :pane
                :h-box/hgrow :always}
               {:fx/type :label
                :text (str (count file-events) " file events  ")}
               {:fx/type :label
                :text (str (count tasks) " tasks")}]}])}}}]
      (when pop-out-battle
        [{:fx/type :stage
          :showing pop-out-battle
          :title "alt-spring-lobby Battle"
          :on-close-request (fn [& args]
                              (log/debug args)
                              (swap! *state assoc :pop-out-battle false))
          :width battle-window-width
          :height battle-window-height
          :scene
          {:fx/type :scene
           :stylesheets stylesheets
           :root
           (merge
             {:fx/type battle-view}
             (select-keys state
               [:battles :battle :users :username :engine-version
                :bot-username :bot-name :bot-version :maps :engines
                :map-input-prefix :mods :drag-team
                :copying :archiving :cleaning :battle-map-details
                :minimap-type :http-download :extracting
                :rapid-data-by-version :rapid-download :git-clone]))}}])
      (when show-importer
        [(merge
           {:fx/type import-window}
           (select-keys state
             [:import-source-name :importables-by-path :show-importer]))])
      (when show-rapid-downloader
        [(merge
           {:fx/type rapid-download-window}
           (select-keys state
             [:engine-version :engines :rapid-download :rapid-repo :rapid-repos-cached :rapid-versions-cached
              :rapid-versions-by-hash :sdp-files-cached :show-rapid-downloader]))])
      (when show-http-downloader
        [(merge
           {:fx/type http-download-window}
           (select-keys state
             [:engine-branch :engine-versions-cached :engines :extracting :http-download :map-files-cache
              :maps-index-url :mod-files-cache :mods-index-url :show-http-downloader]))]))}})


(defn -main [& _args]
  (Platform/setImplicitExit true)
  (fs/init-7z!)
  (swap! *state assoc :standalone true)
  (add-watchers *state)
  ;(add-hawk *state)
  (tasks-chimer-fn *state)
  (file-events-chimer-fn *state)
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc
                          (fn [state]
                            {:fx/type root-view
                             :state state}))
            :opts {:fx.opt/map-event-handler event-handler})]
    (fx/mount-renderer *state r)))
