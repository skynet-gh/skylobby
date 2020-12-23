(ns spring-lobby
  (:require
    [chime.core :as chime]
    [clj-http.client :as clj-http]
    [cljfx.api :as fx]
    [cljfx.component :as fx.component]
    [clojure.contrib.humanize :as humanize]
    [cljfx.ext.node :as fx.ext.node]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [cljfx.lifecycle :as fx.lifecycle]
    [clojure.core.async :as async]
    clojure.data
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.set]
    [clojure.string :as string]
    [com.evocomputing.colors :as colors]
    hashp.core
    [hawk.core :as hawk]
    java-time
    [me.raynes.fs :as raynes-fs]
    [shams.priority-queue :as pq]
    [spring-lobby.battle :as battle]
    [spring-lobby.client :as client]
    [spring-lobby.client.message :as message]
    [spring-lobby.fs :as fs]
    [spring-lobby.fs.sdfz :as replay]
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

(def battle-window-width 1740)
(def battle-window-height 800)

(def start-pos-r 10.0)

(def minimap-size 512)


(def map-browse-image-size 98)
(def map-browse-box-height 160)


; https://github.com/clojure/clojure/blob/28efe345d5e995dc152a0286fb0be81443a0d9ac/src/clj/clojure/instant.clj#L274-L279
(defn read-file-tag [cs]
  (io/file cs))

; https://github.com/clojure/clojure/blob/0754746f476c4ddf6a6b699d9547830b2fdad17c/src/clj/clojure/core.clj#L7755-L7761
(def custom-readers
  {'spring-lobby/java.io.File #'spring-lobby/read-file-tag})

; https://stackoverflow.com/a/23592006/984393
(defmethod print-method java.io.File [f ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.io.File \"" (fs/canonical-path f) "\"")))


(defn slurp-app-edn
  "Returns data loaded from a .edn file in this application's root directory."
  [edn-filename]
  (try
    (let [config-file (io/file (fs/app-root) edn-filename)]
      (when (fs/exists config-file)
        (->> config-file slurp (edn/read-string {:readers custom-readers}))))
    (catch Exception e
      (log/warn e "Exception loading app edn file" edn-filename))))


(def priority-overrides
  {::update-engine 4
   ::update-map 4
   ::update-mod 4
   ::import 5
   ::http-downloadable 5
   ::rapid-downloadable 5})

(defn task-priority [{::keys [task-priority task-type]}]
  (or task-priority
      (get priority-overrides task-type)
      3))

(defn initial-tasks []
  (pq/priority-queue task-priority :variant :set))

(defn initial-file-events []
  (clojure.lang.PersistentQueue/EMPTY))


(def config-keys
  [:username :password :server-url :engine-version :mod-name :map-name
   :battle-title :battle-password
   :bot-username :bot-name :bot-version :minimap-type :pop-out-battle
   :scripttags :preferred-color :rapid-repo])


(defn select-config [state]
  (select-keys state config-keys))

(defn select-maps [state]
  (select-keys state [:maps]))

(defn select-engines [state]
  (select-keys state [:engines]))

(defn select-mods [state]
  (select-keys state [:mods]))

(defn select-importables [state]
  (select-keys state
    [:importables-by-path]))

(defn select-downloadables [state]
  (select-keys state
    [:downloadables-by-url :downloadables-last-updated]))


(def state-to-edn
  [{:select-fn select-config
    :filename "config.edn"}
   {:select-fn select-maps
    :filename "maps.edn"}
   {:select-fn select-engines
    :filename "engines.edn"}
   {:select-fn select-mods
    :filename "mods.edn"}
   {:select-fn select-importables
    :filename "importables.edn"}
   {:select-fn select-downloadables
    :filename "downloadables.edn"}])


(defn initial-state []
  (merge
    (apply
      merge
      (doall
        (map (comp slurp-app-edn :filename) state-to-edn)))
    {:file-events (initial-file-events)
     :tasks (initial-tasks)}))


(def ^:dynamic *state (atom {}))


(defn spit-app-edn
  "Writes the given data as edn to the given file in the application directory."
  [data filename]
  (let [app-root (io/file (fs/app-root))
        file (io/file app-root filename)]
    (when-not (fs/exists app-root)
      (.mkdirs app-root))
    (spit file (with-out-str (pprint data)))))


(defn add-watch-state-to-edn
  [state-atom]
  (add-watch state-atom :state-to-edn
    (fn [_k _ref old-state new-state]
      (doseq [{:keys [select-fn filename]} state-to-edn]
        (try
          (let [old-data (select-fn old-state)
                new-data (select-fn new-state)]
            (when (not= old-data new-data)
              (log/debug "Updating" filename)
              (spit-app-edn new-data filename)))
          (catch Exception e
            (log/error e "Error in :state-to-edn for" filename "state watcher")))))))


(defn read-map-data [maps map-name]
  (let [log-map-name (str "'" map-name "'")]
    (log/info "Reading map data for" log-map-name)
    (try
      (if-let [map-file (some->> maps
                                 (filter (comp #{map-name} :map-name))
                                 first
                                 :file)]
        (fs/read-map-data map-file)
        (log/warn "No file found for map" log-map-name))
      (catch Exception e
        (log/warn e "Error loading map data for" log-map-name)))))

(def ^java.io.File mods-cache-root
  (io/file (fs/app-root) "mods-cache"))

(defn mod-cache-file [mod-name]
  (io/file mods-cache-root (str mod-name ".edn")))

(defn read-mod-data
  ([f]
   (read-mod-data f nil))
  ([f opts]
   (let [mod-data
         (if (string/ends-with? (fs/filename f) ".sdp")
           (rapid/read-sdp-mod f opts)
           (fs/read-mod-file f opts))
         mod-name (spring/mod-name mod-data)]
     (assoc mod-data :mod-name mod-name))))


(defn update-mod [state-atom file]
  (let [mod-data (read-mod-data file {:modinfo-only false})
        ;mod-name (:mod-name mod-data)
        ;cache-file (mod-cache-file mod-name)
        mod-details (select-keys mod-data [:file :mod-name ::fs/source :git-commit-id])]
    ;(log/info "Caching" mod-name "to" cache-file)
    ;(spit cache-file (with-out-str (pprint mod-data)))
    (swap! state-atom update :mods
           (fn [mods]
             (-> (remove (comp #{(fs/canonical-path (:file mod-details))} fs/canonical-path :file) mods)
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


(defn select-debug [state]
  (select-keys state [:pop-out-battle]))


(defmulti event-handler :event/type)


(defn add-watchers
  "Adds all *state watchers."
  [state-atom]
  (remove-watch state-atom :debug)
  (remove-watch state-atom :state-to-edn)
  (remove-watch state-atom :battle-map-details)
  (remove-watch state-atom :battle-mod-details)
  (remove-watch state-atom :fix-missing-resource)
  (add-watch state-atom :debug
    (fn [_k _ref old-state new-state]
      (try
        (let [dold (select-debug old-state)
              dnew (select-debug new-state)]
          (when (not= dold dnew)
            (println dold)
            (println dnew)
            (let [[old-only new-only] (clojure.data/diff old-state new-state)]
              (println old-only)
              (println new-only))))
        (catch Exception e
          (log/error e "Error in debug")))))
  (add-watch-state-to-edn state-atom)
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
            (let [map-details (read-map-data (:maps new-state) new-battle-map)]
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
            (let [mod-details (some->> new-state
                                       :mods
                                       (filter filter-fn)
                                       first
                                       :file
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
              ;(fs/isolation-dir)
              ;(fs/spring-root)] ; TODO slow or dangerous
              (fs/maps-dir)]
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


(defn update-file-cache!
  "Updates the file cache in state for this file. This is so that we don't need to do IO in render,
  and any updates to file statuses here can now cause UI redraws, which is good."
  [& fs]
  (let [statuses (for [f fs]
                   (let [f (if (string? f)
                             (io/file f)
                             f)]
                     (if f
                       {:canonical-path (fs/canonical-path f)
                        :exists (fs/exists f)
                        :is-directory (fs/is-directory f)}
                       (log/warn "Attempt to update file cache for nil file"))))
        status-by-path (->> statuses
                            (filter some?)
                            (map (juxt :canonical-path identity))
                            (into {}))]
    (swap! *state update :file-cache merge status-by-path)
    status-by-path))

(defn file-status [file-cache f]
  (when f
    (let [path (if (string? f)
                 f
                 (fs/canonical-path f))]
      (get file-cache path))))

(defn file-exists? [file-cache f]
  (boolean (:exists (file-status file-cache f))))


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
                 (string/ends-with? (fs/filename file) ".sdp"))))
      {::task-type ::update-mod
       :file file}
      ; or mod in directory / git format
      (partial fs/descendant? games)
      (loop [parent file] ; find directory in games
        (when parent
          (if (fs/child? games parent)
            {::task-type ::update-mod
             :file parent}
            (recur (fs/parent-file parent)))))
      (partial fs/descendant? maps)
      (loop [parent file] ; find file in maps
        (when parent
          (if (fs/child? maps parent)
            {::task-type ::update-map
             :file parent}
            (recur (fs/parent-file parent)))))
      (partial fs/descendant? engines)
      (loop [parent file] ; find directory in engines
        (when parent
          (if (fs/child? maps parent)
            {::task-type ::update-engine
             :file parent}
            (recur (fs/parent-file parent)))))
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
              (when-not (.isAlive ^java.lang.Thread (:thread hawk))
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
   (log/info "Reconciling engines")
   (apply update-file-cache! (file-seq (fs/download-dir))) ; TODO move this somewhere
   (let [before (u/curr-millis)
         engine-dirs (fs/engine-dirs)
         known-canonical-paths (->> state-atom deref :engines
                                    (map (comp fs/canonical-path :file))
                                    (filter some?)
                                    set)
         to-add (remove (comp known-canonical-paths fs/canonical-path) engine-dirs)
         canonical-path-set (set (map fs/canonical-path engine-dirs))
         missing-files (set
                         (concat
                           (->> known-canonical-paths
                                (remove (comp fs/exists io/file)))
                           (->> known-canonical-paths
                                (remove (comp (partial fs/descendant? (fs/isolation-dir)) io/file)))))
         to-remove (set
                     (concat missing-files
                             (remove canonical-path-set known-canonical-paths)))]
     (apply update-file-cache! known-canonical-paths)
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
              (->> engines
                   (filter :file)
                   (remove (comp to-remove fs/canonical-path :file))
                   set)))
     {:to-add-count (count to-add)
      :to-remove-count (count to-remove)})))

(defn force-update-battle-engine
  ([]
   (force-update-battle-engine *state))
  ([state-atom]
   (log/info "Force updating battle engine")
   (reconcile-engines state-atom)
   (let [{:keys [battle battles engines]} @state-atom
         battle-id (:battle-id battle)
         battle-engine-version (-> battles (get battle-id) :battle-version)
         _ (log/debug "Force updating battle engine details for" battle-engine-version)
         filter-fn (comp #{battle-engine-version} :engine-version)
         engine-details (some->> engines
                                 (filter filter-fn)
                                 first
                                 :file
                                 fs/engine-data)]
     (swap! *state update :engines
            (fn [engines]
              (->> engines
                   (remove filter-fn)
                   (concat [engine-details])
                   set)))
     engine-details)))


(defn remove-bad-mods [state-atom]
  (swap! state-atom update :mods
         (fn [mods]
           (->> mods
                (remove (comp string/blank? :mod-name))
                (filter (comp fs/exists :file))
                set))))

(defn remove-all-duplicate-mods
  "Removes all copies of any mod that shares canonical-path with another mod."
  [state-atom]
  (log/info "Removing duplicate mods")
  (swap! state-atom update :mods
         (fn [mods]
           (let [path-fn (comp fs/canonical-path :file)
                 freqs (frequencies (map path-fn mods))]
             (->> mods
                  (remove (comp pos? dec freqs path-fn))
                  set)))))

(defn reconcile-mods
  "Reads mod details and updates missing mods in :mods in state."
  [state-atom]
  (log/info "Reconciling mods")
  (remove-all-duplicate-mods state-atom)
  (let [before (u/curr-millis)
        mods (->> state-atom deref :mods)
        {:keys [rapid archive directory]} (group-by ::fs/source mods)
        known-file-paths (set (map (comp fs/canonical-path :file) (concat archive directory)))
        known-rapid-paths (set (map (comp fs/canonical-path :file) rapid))
        mod-files (fs/mod-files)
        sdp-files (rapid/sdp-files)
        _ (log/info "Found" (count mod-files) "files and"
                    (count sdp-files) "rapid archives to scan for mods")
        to-add-file (set
                      (concat
                        (remove (comp known-file-paths fs/canonical-path) mod-files)
                        (map :file directory))) ; always scan dirs in case git changed
        to-add-rapid (remove (comp known-rapid-paths fs/canonical-path) sdp-files)
        all-paths (filter some? (concat known-file-paths known-rapid-paths))
        missing-files (set
                        (concat
                          (->> all-paths
                               (remove (comp fs/exists io/file)))
                          (->> all-paths
                               (remove (comp (partial fs/descendant? (fs/isolation-dir)) io/file)))))]
    (apply update-file-cache! all-paths)
    (when-not (fs/exists mods-cache-root)
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
                  (remove (comp missing-files fs/canonical-path :file))
                  set)))
    {:to-add-file-count (count to-add-file)
     :to-add-rapid-count (count to-add-rapid)}))

(defn force-update-battle-mod
  ([]
   (force-update-battle-mod *state))
  ([state-atom]
   (log/info "Force updating battle mod")
   (reconcile-mods state-atom)
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
                              :file
                              read-mod-data)]
     (swap! *state assoc :battle-mod-details mod-details)
     mod-details)))

(def ^java.io.File maps-cache-root
  (io/file (fs/app-root) "maps-cache"))

(defn map-cache-file [map-name]
  (io/file maps-cache-root (str map-name ".edn")))


(defn scale-minimap-image [minimap-width minimap-height minimap-image]
  (when minimap-image
    (let [^sun.awt.image.ToolkitImage scaled
          (.getScaledInstance ^java.awt.Image minimap-image
            minimap-width minimap-height java.awt.Image/SCALE_SMOOTH)
          _ (.getWidth scaled)
          _ (.getHeight scaled)]
      (.getBufferedImage scaled))))

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


(defn scaled-minimap-image [{:keys [header minimap-image]}]
  (when minimap-image
    (let [{:keys [minimap-width minimap-height] :or {minimap-width minimap-size
                                                     minimap-height minimap-size}} (minimap-dimensions header)]
      (scale-minimap-image minimap-width minimap-height minimap-image))))

(defn update-cached-minimaps
  ([maps]
   (update-cached-minimaps maps nil))
  ([maps opts]
   (let [to-update
         (->> maps
              (map (comp fs/minimap-image-cache-file :map-name))
              (filter (fn [f] (or (:force opts) (not (fs/exists f))))))]
     (log/info (count to-update) "maps do not have cached minimap image files")
     (doseq [map-details to-update]
       (when-let [map-file (:file map-details)]
         (let [{:keys [map-name smf]} (fs/read-map-data map-file)]
           (when-let [minimap-image (scaled-minimap-image smf)]
             (fs/write-image-png minimap-image (fs/minimap-image-cache-file map-name)))))))))

(defn reconcile-maps
  "Reads map details and caches for maps missing from :maps in state."
  [state-atom]
  (log/info "Reconciling maps")
  (let [before (u/curr-millis)
        map-files (fs/map-files)
        known-files (->> state-atom deref :maps (map :file) set)
        known-paths (->> known-files (map fs/canonical-path) set)
        todo (remove (comp known-paths fs/canonical-path) map-files)
        missing-paths (set
                        (concat
                          (->> known-files
                               (remove fs/exists)
                               (map fs/canonical-path))
                          (->> known-files
                               (remove (partial fs/descendant? (fs/isolation-dir)))
                               (map fs/canonical-path))))]
    (apply update-file-cache! map-files)
    (log/info "Found" (count todo) "maps to load in" (- (u/curr-millis) before) "ms")
    (when-not (fs/exists maps-cache-root)
      (.mkdirs maps-cache-root))
    (doseq [map-file todo]
      (log/info "Reading" map-file)
      (let [{:keys [map-name smf] :as map-data} (fs/read-map-data map-file)]
            ;map-cache-file (map-cache-file (:map-name map-data))]
        (if map-name
          (do
            ;(log/info "Caching" map-file "to" map-cache-file)
            ;(spit map-cache-file (with-out-str (pprint map-data)))
            (when-let [minimap-image (scaled-minimap-image smf)]
              (fs/write-image-png minimap-image (fs/minimap-image-cache-file map-name)))
            (swap! state-atom update :maps
                   (fn [maps]
                     (set (conj maps (select-keys map-data [:file :map-name]))))))
          (log/warn "No map name found for" map-file))))
    (log/debug "Removing maps with no name, and" (count missing-paths) "maps with missing files")
    (swap! state-atom update :maps
           (fn [maps]
             (->> maps
                  (filter :file)
                  (remove (comp string/blank? :map-name))
                  (remove (comp missing-paths fs/canonical-path :file))
                  set)))
    (update-cached-minimaps (:maps @*state))
    {:todo-count (count todo)}))

(defn force-update-battle-map
  ([]
   (force-update-battle-map *state))
  ([state-atom]
   (log/info "Force updating battle map")
   (reconcile-maps state-atom)
   (let [{:keys [battle battles maps]} @state-atom
         battle-id (:battle-id battle)
         battle-map (-> battles (get battle-id) :battle-map)
         _ (log/debug "Force updating battle map details for" battle-map)
         filter-fn (comp #{battle-map} :map-name)
         map-details (some->> maps
                              (filter filter-fn)
                              first
                              :file
                              fs/read-map-data)]
     (swap! *state assoc :battle-map-details map-details)
     map-details)))


(defn force-update-chimer-fn [state-atom]
  (log/info "Starting force update chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/instant)
            (java-time/duration 3 :minutes))
          (fn [_chimestamp]
            (log/info "Force updating battle resources")
            (force-update-battle-engine state-atom)
            (force-update-battle-mod state-atom)
            (force-update-battle-map state-atom))
          {:error-handler
           (fn [e]
             (log/error e "Error force updating resources")
             true)})]
    (fn [] (.close chimer))))


(defmethod task-handler ::reconcile-engines [_]
  (reconcile-engines *state))

(defmethod task-handler ::reconcile-mods [_]
  (reconcile-mods *state))

(defmethod task-handler ::reconcile-maps [_]
  (reconcile-maps *state))


(defmethod task-handler ::update-map
  [{:keys [_file]}]
  (reconcile-maps *state)) ; TODO specific map


(defmethod event-handler ::reconcile-engines [_e]
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
               :-fx-max-width "360px"}}]}
    {:fx/type :pane
     :h-box/hgrow :always}
    {:fx/type :button
     :text "Replays"
     :tooltip
     {:fx/type :tooltip
      :show-delay [10 :ms]
      :style {:-fx-font-size 14}
      :text "Show replays window"}
     :on-action {:event/type ::assoc
                 :key :show-replays}
     :graphic
     {:fx/type font-icon/lifecycle
      :icon-literal "mdi-open-in-new:16:white"}}]})


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
  [{:keys [client scripttags host-battle-state use-springlobby-modname]}]
  (let [{:keys [engine-version map-name mod-name]} host-battle-state]
    (when-not (or (string/blank? engine-version)
                  (string/blank? mod-name)
                  (string/blank? map-name))
      (let [mod-name-parsed (parse-mod-name-git mod-name)
            [_ mod-prefix _git] mod-name-parsed
            adjusted-modname (if (and use-springlobby-modname mod-name-parsed)
                                 (str mod-prefix " $VERSION")
                                 mod-name)]
        (open-battle client (assoc host-battle-state :mod-name adjusted-modname)))
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
           :on-value-changed on-value-changed
           :cell-factory
           {:fx/cell-type :list-cell
            :describe
            (fn [map-name]
              {:text (if (string/blank? map-name)
                       "< choose a map >"
                       map-name)})}
           :on-key-pressed {:event/type ::maps-key-pressed}
           :on-hidden {:event/type ::maps-hidden}
           :tooltip {:fx/type :tooltip
                     :show-delay [10 :ms]
                     :text (or map-input-prefix "Choose map")}})])
     [{:fx/type :button
       :text ""
       :tooltip
       {:fx/type :tooltip
        :show-delay [10 :ms]
        :style {:-fx-font-size 14}
        :text "Show maps window"}
       :on-action {:event/type ::assoc
                   :key :show-maps}
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-magnify:16:white"}}
      {:fx/type fx.ext.node/with-tooltip-props
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
         :icon-literal "mdi-folder:16:white"}}}]
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

(defmethod event-handler ::show-downloader [_e]
  (swap! *state assoc :show-downloader true))

(defn battles-buttons
  [{:keys [battle battles battle-password battle-title client engine-version mod-name map-name maps
           engines mods map-input-prefix engine-filter mod-filter pop-out-battle selected-battle
           use-springlobby-modname]
    :as state}]
  {:fx/type :v-box
   :alignment :top-left
   :children
   [{:fx/type :h-box
     :alignment :center-left
     :style {:-fx-font-size 16}
     :children
     [{:fx/type :label
       :text " Resources: "}
      {:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Import local resources from SpringLobby and Beyond All Reason"}}
       :desc
       {:fx/type :button
        :text "import"
        :on-action {:event/type ::show-importer}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal (str "mdi-file-import:16:white")}}}
      {:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Download resources from various websites using http"}}
       :desc
       {:fx/type :button
        :text "http"
        :on-action {:event/type ::show-downloader}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal (str "mdi-download:16:white")}}}
      {:fx/type fx.ext.node/with-tooltip-props
       :props
       {:tooltip
        {:fx/type :tooltip
         :show-delay [10 :ms]
         :text "Download resources with the Rapid tool"}}
       :desc
       {:fx/type :button
        :text "rapid"
        :on-action {:event/type ::show-rapid-downloader}
        :graphic
        {:fx/type font-icon/lifecycle
         :icon-literal (str "mdi-download:16:white")}}}
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
                                       (filter some?)
                                       (filter #(string/includes? (string/lower-case %) filter-lc))
                                       sort)]
             [{:fx/type :combo-box
               :value (str engine-version)
               :items filtered-engines
               ;:disable (boolean battle)
               :on-value-changed {:event/type ::version-change}
               :cell-factory
               {:fx/cell-type :list-cell
                :describe
                (fn [engine]
                  {:text (if (string/blank? engine)
                           "< choose an engine >"
                           engine)})}
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
             :text "Reload engines"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::reconcile-engines}
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
               ;:disable (boolean battle)
               :on-value-changed {:event/type ::mod-change}
               :cell-factory
               {:fx/cell-type :list-cell
                :describe
                (fn [mod-name]
                  {:text (if (string/blank? mod-name)
                           "< choose a game >"
                           mod-name)})}
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
       ;:disable (boolean battle)
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
                                    (select-keys state [:client :scripttags :use-springlobby-modname]))]
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
             :on-text-changed {:event/type ::battle-password-change}}
            {:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :show-delay [10 :ms]
               :style {:-fx-font-size 14}
               :text "If using git, set version to $VERSION so SpringLobby is happier"}}
             :desc
             {:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :check-box
                :selected (boolean use-springlobby-modname)
                :h-box/margin 8
                :on-selected-changed {:event/type ::use-springlobby-modname-change}}
               {:fx/type :label
                :text "Use SpringLobby Game Name"}]}}])))}
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

(defmethod event-handler ::use-springlobby-modname-change
  [{:fx/keys [event]}]
  (swap! *state assoc :use-springlobby-modname event))


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
    (swap! *state assoc :map-name map-name)))

(defmethod event-handler ::battle-map-change
  [{:fx/keys [event] :keys [map-name maps]}]
  (let [spectator-count 0 ; TODO
        locked 0
        map-hash -1 ; TODO
        map-name (or map-name event)
        m (str "UPDATEBATTLEINFO " spectator-count " " locked " " map-hash " " map-name)]
    (swap! *state assoc :battle-map-details (read-map-data maps map-name))
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
                         (map (juxt (comp spring/team-name :id :battle-status) identity))
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


(defn update-copying [f copying]
  (if f
    (swap! *state update-in [:copying (fs/canonical-path f)] merge copying)
    (log/warn "Attempt to update copying for nil file")))

(defmethod event-handler ::add-task [{:keys [task]}]
  (add-task! *state task))

(defn resource-dest [{:keys [resource-filename resource-name resource-file resource-type]}]
  (let [filename (or resource-filename
                     (fs/filename resource-file))]
    (case resource-type
      ::engine (cond
                 (and resource-file (fs/exists resource-file) (fs/is-directory resource-file))
                 (io/file (fs/engines-dir) filename)
                 filename (io/file (fs/download-dir) "engine" filename)
                 resource-name (http/engine-download-file resource-name)
                 :else nil)
      ::mod (when filename (io/file (fs/mod-file filename))) ; TODO mod format types
      ::map (when filename (io/file (fs/map-file filename))) ; TODO mod format types
      nil)))

(defmethod task-handler ::import [{:keys [importable]}]
  (let [{:keys [resource-file]} importable
        source resource-file
        dest (resource-dest importable)]
    (update-copying source {:status true})
    (update-copying dest {:status true})
    (try
      (fs/copy source dest)
      (log/info "Finished importing" importable "from" source "to" dest)
      (case (:resource-type importable)
        ::map (force-update-battle-map *state)
        ::mod (force-update-battle-mod *state)
        ::engine (reconcile-engines *state)
        nil)
      (catch Exception e
        (log/error e "Error importing" importable))
      (finally
        (update-copying source {:status false})
        (update-copying dest {:status false})
        (update-file-cache! source)
        (update-file-cache! dest))))) ; TODO atomic?

(defmethod event-handler ::git-mod
  [{:keys [battle-mod-git-ref file]}]
  (when (and file battle-mod-git-ref)
    (log/info "Resetting mod at" file "to ref" battle-mod-git-ref)
    (let [canonical-path (fs/canonical-path file)]
      (swap! *state assoc-in [:gitting canonical-path] {:status true})
      (future
        (try
          (git/fetch file)
          (git/reset-hard file battle-mod-git-ref)
          (reconcile-mods *state)
          (force-update-battle-mod *state)
          (catch Exception e
            (log/error e "Error during git reset" canonical-path "to ref" battle-mod-git-ref))
          (finally
            (swap! *state assoc-in [:gitting canonical-path] {:status false})))))))


(defn engine-dest [engine-version]
  (when engine-version
    (io/file (fs/spring-root) "engine" (http/engine-archive engine-version))))


(def minimap-types
  ["minimap" "metalmap"])

(defmethod event-handler ::minimap-scroll
  [_e]
  (swap! *state
         (fn [{:keys [minimap-type] :as state}]
           (let [next-index (mod
                              (inc (.indexOf ^java.util.List minimap-types minimap-type))
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

(defmethod event-handler ::hostip-changed
  [{:fx/keys [event]}]
  (let [scripttags {:game {:hostip (str event)}}]
    (swap! *state update :scripttags u/deep-merge scripttags)
    (swap! *state update-in [:battle :scripttags] u/deep-merge scripttags)
    (message/send-message
      (:client @*state)
      (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags)))))

(defmethod event-handler ::isolation-type-change [{:fx/keys [event]}]
  (log/info event))

(defmethod event-handler ::force-update-battle-map [_e]
  (future
    (try
      (force-update-battle-map *state)
      (catch Exception e
        (log/error e "Error force updating battle map")))))

(defmethod event-handler ::delete-map [{:fx/keys [event]}]
  (log/info event))

(defmethod event-handler ::force-update-battle-mod [_e]
  (future
    (try
      (force-update-battle-mod *state)
      (catch Exception e
        (log/error e "Error force updating battle mod")))))

(defmethod event-handler ::force-update-battle-engine [_e]
  (future
    (try
      (force-update-battle-engine *state)
      (catch Exception e
        (log/error e "Error force updating battle engine")))))

(defmethod event-handler ::delete-mod [{:fx/keys [event]}]
  (log/info event))

(defmethod event-handler ::delete-engine
  [{:keys [engines engine-version]}]
  (if-let [engine-dir (some->> engines
                               (filter (comp #{engine-version} :engine-version))
                               first
                               :file)]
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

(defn git-repo-url [battle-modname]
  (cond
    (string/starts-with? battle-modname "Beyond All Reason")
    git/bar-repo-url
    (string/starts-with? battle-modname "Balanced Annihilation")
    git/ba-repo-url))

(defn could-be-this-engine?
  "Returns true if this resource might be the engine with the given name, by magic, false otherwise."
  [engine-version {:keys [resource-filename resource-name]}]
  (or (= engine-version resource-name)
      (when (and engine-version resource-filename)
        (or (= engine-version resource-filename)
            (= (http/engine-archive engine-version)
               resource-filename)))))

(defn normalize-mod [mod-name-or-filename]
  (-> mod-name-or-filename
      string/lower-case
      (string/replace #"\s+" "_")
      (string/replace #"-" "_")
      (string/replace #"\.sd[7z]$" "")))

(defn could-be-this-mod?
  "Returns true if this resource might be the mod with the given name, by magic, false otherwise."
  [mod-name {:keys [resource-filename resource-name]}]
  (or (= mod-name resource-name)
      (when (and mod-name resource-filename)
        (= (normalize-mod mod-name)
           (normalize-mod resource-filename)))))


(defn normalize-map [map-name-or-filename]
  (some-> map-name-or-filename
          string/lower-case
          (string/replace #"\s+" "_")
          (string/replace #"-" "_")
          (string/replace #"\.sd[7z]$" "")))

(defn could-be-this-map?
  "Returns true if this resource might be the map with the given name, by magic, false otherwise."
  [map-name {:keys [resource-filename resource-name]}]
  (or (= map-name resource-name)
      (when (and map-name resource-filename)
        (= (normalize-map map-name)
           (normalize-map resource-filename)))))


(defn download-progress
  [{:keys [current total]}]
  (if (and current total)
    (str (u/format-bytes current)
         " / "
         (u/format-bytes total))
    "-"))

(def battle-view-keys
  [:archiving :battles :battle :battle-map-details :battle-mod-details :bot-name
   :bot-username :bot-version :cleaning :copying :downloadables-by-url :downloads :drag-team :engine-version
   :engines :extracting :file-cache :git-clone :gitting :http-download :importables-by-path
   :isolation-type
   :map-input-prefix :maps :minimap-type :mods :rapid-data-by-version
   :rapid-download :username :users])

(defn battle-view
  [{:keys [battle battles battle-map-details battle-mod-details bot-name bot-username bot-version
           copying downloadables-by-url drag-team engines extracting file-cache gitting
           http-download importables-by-path map-input-prefix maps minimap-type
           rapid-data-by-version rapid-download users username]
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
        engine-file (:file engine-details)
        engine-download-file (http/engine-download-file engine-version) ; TODO duplicate of downloadable?
        bots (fs/bots engine-file)
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
               :text " Host IP: "}
              {:fx/type :text-field
               :text (-> battle :scripttags :game :hostip str)
               :on-text-changed {:event/type ::hostip-changed}}]}
            #_
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
             [(let [map-file (:file battle-map-details)]
                {:fx/type resource-sync-pane
                 :h-box/margin 8
                 :resource "Map" ;battle-map ; (str "map (" battle-map ")")
                 ;:delete-action {:event/type ::delete-map}
                 :browse-action {:event/type ::desktop-browse-dir
                                 :file (or map-file (fs/maps-dir))}
                 :refresh-action {:event/type ::force-update-battle-map}
                 :issues
                 (concat
                   (let [severity (if battle-map-details 0 2)]
                     [{:severity severity
                       :text "info"
                       :human-text battle-map
                       :tooltip (if (zero? severity)
                                  (fs/canonical-path (:file battle-map-details))
                                  (str "Map '" battle-map "' not found locally"))}])
                   (when-not battle-map-details
                     (let [downloadable (->> downloadables-by-url
                                             vals
                                             (filter (comp #{::map} :resource-type))
                                             (filter (partial could-be-this-map? battle-map))
                                             first)
                           url (:download-url downloadable)
                           download (get http-download url)
                           in-progress (:running download)
                           dest (resource-dest downloadable)
                           dest-exists (file-exists? file-cache dest)
                           severity (if battle-map-details 0 2)]
                       [{:severity severity
                         :text "download"
                         :human-text (if in-progress
                                       (download-progress download)
                                       (if downloadable
                                         (if dest-exists
                                           (str "Downloaded " (fs/filename dest))
                                           (str "Download from " (:download-source-name downloadable)))
                                         (str "No download for " battle-map)))
                         :tooltip (if in-progress
                                    (str "Downloading " (download-progress download))
                                    (if dest-exists
                                      (str "Downloaded to " (fs/canonical-path dest))
                                      (str "Download " url)))
                         :in-progress in-progress
                         :action (when (and downloadable (not dest-exists))
                                   {:event/type ::http-downloadable
                                    :downloadable downloadable})}]))
                   (when-not battle-map-details
                     (let [importable (some->> importables-by-path
                                               vals
                                               (filter (comp #{::map} :resource-type))
                                               (filter (partial could-be-this-map? battle-map))
                                               first)
                           resource-file (:resource-file importable)
                           canonical-path (fs/canonical-path resource-file)]
                       [{:severity 2
                         :text "import"
                         :human-text (if importable
                                       (str "Import from " (:import-source-name importable))
                                       "No import found")
                         :tooltip (if importable
                                    (str "Copy map archive from " canonical-path)
                                    (str "No local import found for map " battle-map))
                         :in-progress (-> copying (get canonical-path) :status)
                         :action
                         (when (and importable
                                    (not (file-exists? file-cache (resource-dest importable))))
                           {:event/type ::add-task
                            :task
                            {::task-type ::import
                             :importable importable}})}])))})
              (let [mod-file (:file battle-mod-details)
                    canonical-path (fs/canonical-path mod-file)]
                {:fx/type resource-sync-pane
                 :h-box/margin 8
                 :resource "Game" ;battle-modname ; (str "game (" battle-modname ")")
                 ;:delete-action {:event/type ::delete-mod}
                 :browse-action {:event/type ::desktop-browse-dir
                                 :file (or mod-file (fs/mods-dir))}
                 :refresh-action {:event/type ::force-update-battle-mod}
                 :issues
                 (concat
                   (let [severity (if battle-mod-details 0 2)]
                     [{:severity severity
                       :text "info"
                       :human-text battle-modname
                       :tooltip (if (zero? severity)
                                  canonical-path
                                  (str "Game '" battle-modname "' not found locally"))}])
                   (when-not battle-mod-details
                     (let [downloadable (->> downloadables-by-url
                                             vals
                                             (filter (comp #{::mod} :resource-type))
                                             (filter (partial could-be-this-mod? battle-modname))
                                             first)
                           download-url (:download-url downloadable)
                           in-progress (-> http-download (get download-url) :running)
                           {:keys [download-source-name download-url]} downloadable]
                       [{:severity (if battle-mod-details 0 2)
                         :text "download"
                         :human-text (if battle-mod-details
                                       (:mod-name battle-mod-details)
                                       (if downloadable
                                         (str "Download from " download-source-name)
                                         (str "No download for " battle-modname)))
                         :in-progress in-progress
                         :tooltip (if downloadable
                                    (str "Download from " download-source-name " at " download-url)
                                    (str "No http download found for " battle-modname))
                         :action
                         (when downloadable
                           {:event/type ::http-downloadable
                            :downloadable downloadable})}]))
                   (when-not battle-mod-details
                     (let [rapid-id (:id (get rapid-data-by-version battle-modname))
                           in-progress (-> rapid-download (get rapid-id) :running)]
                       [{:severity 2
                         :text "rapid"
                         :human-text (if rapid-id
                                       (if engine-file
                                         (str "Download rapid " rapid-id)
                                         "Needs engine first to download with rapid")
                                       "No rapid download")
                         :tooltip (if rapid-id
                                    (if engine-file
                                      (str "Use rapid downloader to get resource id " rapid-id
                                           " using engine " (:engine-version engine-details))
                                      "Rapid requires an engine to work, get engine first")
                                    (str "No rapid download found for" battle-modname))
                         :in-progress in-progress
                         :action
                         (when (and rapid-id engine-file)
                           {:event/type ::rapid-download
                            :rapid-id rapid-id
                            :engine-file engine-file})}]))
                   (when-not battle-mod-details
                     (let [importable (some->> importables-by-path
                                               vals
                                               (filter (comp #{::mod} :resource-type))
                                               (filter (partial could-be-this-mod? battle-modname))
                                               first)
                           resource-file (:resource-file importable)]
                       [{:severity 2
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
                                   {:event/type ::add-task
                                    :task
                                    {::task-type ::import
                                     :importable importable}})}]))
                   (when (and (= :directory
                                 (::fs/source battle-mod-details)))
                     (let [battle-mod-git-ref (mod-git-ref battle-modname)
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
                               {:event/type ::git-mod
                                :file mod-file
                                :battle-mod-git-ref battle-mod-git-ref}}))]
                         (when (and (not (zero? severity))
                                    (not= battle-mod-git-ref "$VERSION"))
                           [(merge
                              {:severity 1
                               :text "rehost"
                               :human-text "Or rehost to change game version"
                               :tooltip (str "Leave battle and host again to use game "
                                             (:mod-name battle-mod-details))})])))))})
              {:fx/type resource-sync-pane
               :h-box/margin 8
               :resource "Engine" ; engine-version ; (str "engine (" engine-version ")")
               ;:delete-action {:event/type ::delete-engine
               ;                :engines engines
               ;                :engine-version engine-version
               :refresh-action {:event/type ::force-update-battle-engine}
               :browse-action {:event/type ::desktop-browse-dir
                               :file (or engine-file
                                         (fs/engines-dir))}
               :issues
               (concat
                 (let [severity (if engine-details 0 2)]
                   [{:severity severity
                     :text "info"
                     :human-text (str "Spring " engine-version)
                     :tooltip (if (zero? severity)
                                (fs/canonical-path (:file engine-details))
                                (str "Engine '" engine-version "' not found locally"))}])
                 (when-not engine-details
                   (let [downloadable (->> downloadables-by-url
                                           vals
                                           (filter (comp #{::engine} :resource-type))
                                           (filter (partial could-be-this-engine? engine-version))
                                           first)
                         url (:download-url downloadable)
                         download (get http-download url)
                         in-progress (:running download)
                         dest (resource-dest downloadable)
                         dest-exists (file-exists? file-cache dest)
                         severity (if dest-exists 0 2)]
                     [{:severity severity
                       :text "download"
                       :human-text (if in-progress
                                     (download-progress download)
                                     (if downloadable
                                       (if dest-exists
                                         (str "Downloaded " (fs/filename dest))
                                         (str "Download from " (:download-source-name downloadable)))
                                       (str "No download for " engine-version)))
                       :tooltip (if in-progress
                                  (str "Downloading " (download-progress download))
                                  (if dest-exists
                                    (str "Downloaded to " (fs/canonical-path dest))
                                    (str "Download " url)))
                       :in-progress in-progress
                       :action (when (and downloadable (not dest-exists))
                                 {:event/type ::http-downloadable
                                  :downloadable downloadable})}]))
                 (when (and (not engine-details)
                            (file-exists? file-cache engine-download-file))
                   [{:severity 2
                     :text "extract"
                     :in-progress (get extracting engine-download-file)
                     :human-text "Extract engine archive"
                     :tooltip (str "Click to extract " engine-download-file)
                     :action {:event/type ::extract-7z
                              :file engine-download-file
                              :dest (io/file (fs/isolation-dir) "engine" engine-version)}}])
                 (when-not engine-details
                   (let [importable (some->> importables-by-path
                                             vals
                                             (filter (comp #{::engine} :resource-type))
                                             (filter (partial could-be-this-engine? engine-version))
                                             first)
                         {:keys [import-source-name resource-file]} importable
                         resource-path (fs/canonical-path resource-file)]
                     [{:severity 2
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
                                  (not (file-exists? file-cache (resource-dest importable))))
                         {:event/type ::add-task
                          :task
                          {::task-type ::import
                           :importable importable}})}])))}]}
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
                                      Color/BLACK Color/WHITE)
                       random (= "Random" startpostype)
                       random-teams (when random
                                      (let [teams (spring/teams battle-details)]
                                        (set (map str (take (count teams) (iterate inc 0))))))
                       starting-points (if random
                                         (filter (comp random-teams :team) starting-points)
                                         starting-points)]
                   (.clearRect gc 0 0 minimap-width minimap-height)
                   (.setFill gc Color/RED)
                   (doseq [{:keys [x y team color]} starting-points]
                     (let [drag (when (and drag-team
                                           (= team (:team drag-team)))
                                  drag-team)
                           x (or (:x drag) x)
                           y (or (:y drag) y)
                           xc (- x (if (= 1 (count team)) ; single digit
                                     (* start-pos-r -0.6)
                                     (* start-pos-r -0.2)))
                           yc (+ y (/ start-pos-r 0.75))
                           text (if random "?" team)
                           fill-color (if random Color/RED color)]
                       (cond
                         (#{"Fixed" "Random" "Choose before game"} startpostype)
                         (do
                           (.beginPath gc)
                           (.rect gc x y
                                  (* 2 start-pos-r)
                                  (* 2 start-pos-r))
                           (.setFill gc fill-color)
                           (.fill gc)
                           (.setStroke gc border-color)
                           (.stroke gc)
                           (.closePath gc)
                           (.setStroke gc Color/BLACK)
                           (.strokeText gc text xc yc)
                           (.setFill gc Color/WHITE)
                           (.fillText gc text xc yc))
                         :else ; TODO choose starting rects
                         nil)))))})])}
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
           :on-value-changed {:event/type ::battle-map-change
                              :maps maps}}]}
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

(defmethod task-handler ::update-rapid
  [_e]
  (let [{:keys [engine-version engines]} @*state
        preferred-engine-details (spring/engine-details engines engine-version)
        engine-details (if (and preferred-engine-details (:file preferred-engine-details))
                         preferred-engine-details
                         (->> engines
                              (filter :file)
                              first))
        root (fs/isolation-dir)]
    (if (and engine-details (:file engine-details))
      (if-not (and (fs/exists (io/file root "rapid"))
                   (fs/exists (io/file root "rapid" "packages.springrts.com" "versions.gz")))
        (do
          (log/info "Initializing rapid by calling download")
          (deref
            (event-handler
              {:event/type ::rapid-download
               :rapid-id "engine:stable" ; TODO how else to init rapid without download...
               :engine-file (:file engine-details)})))
        (log/info "Rapid already initialized"))
      (log/warn "No engine details to do rapid init"))
    (log/info "Updating rapid versions in" root)
    (let [before (u/curr-millis)
          rapid-repos (rapid/repos root)
          _ (log/info "Found" (count rapid-repos) "rapid repos")
          rapid-versions (mapcat rapid/versions rapid-repos)
          _ (log/info "Found" (count rapid-versions) "rapid versions")
          rapid-data-by-hash (->> rapid-versions
                              (map (juxt :hash identity))
                              (into {}))
          rapid-data-by-version (->> rapid-versions
                                     (map (juxt :version identity))
                                     (into {}))]
      (swap! *state assoc
             :rapid-repos rapid-repos
             :rapid-data-by-hash rapid-data-by-hash
             :rapid-data-by-version rapid-data-by-version
             :rapid-versions rapid-versions)
      (log/info "Updated rapid repo data in" (- (u/curr-millis) before) "ms"))))

(defmethod event-handler ::rapid-repo-change
  [{:fx/keys [event]}]
  (swap! *state assoc :rapid-repo event))

(defmethod event-handler ::rapid-download
  [{:keys [engine-file rapid-id]}]
  (swap! *state assoc-in [:rapid-download rapid-id] {:running true
                                                     :message "Preparing to run pr-downloader"})
  (future
    (try
      (let [pr-downloader-file (io/file engine-file (fs/executable "pr-downloader"))
            root (fs/isolation-dir) ; TODO always?
            command [(fs/canonical-path pr-downloader-file)
                     "--filesystem-writepath" (fs/wslpath root)
                     "--rapid-download" rapid-id]
            runtime (Runtime/getRuntime)]
        (log/info "Running '" command "'")
        (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
              ^"[Ljava.lang.String;" envp nil
              ^java.lang.Process process (.exec runtime cmdarray envp root)]
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
          (swap! *state assoc :sdp-files (doall (rapid/sdp-files root)))))
      (catch Exception e
        (log/error e "Error downloading" rapid-id)
        (swap! *state assoc-in [:rapid-download rapid-id :message] (.getMessage e)))
      (finally
        (swap! *state assoc-in [:rapid-download rapid-id :running] false)
        (reconcile-mods *state)))))


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
      (let [parent (fs/parent-file dest)]
        (.mkdirs parent))
      (clj-http/with-middleware
        (-> clj-http/default-middleware
            (insert-after clj-http/wrap-url wrap-downloaded-bytes-counter)
            (conj clj-http/wrap-lower-case-headers))
        (let [request (clj-http/get url {:as :stream})
              ^String content-length (get-in request [:headers "content-length"] "0")
              length (Integer/valueOf content-length)
              buffer-size (* 1024 10)]
          (swap! *state update-in [:http-download url]
                 merge
                 {:current 0
                  :total length})
          (with-open [^java.io.InputStream input (:body request)
                      output (io/output-stream dest)]
            (let [buffer (make-array Byte/TYPE buffer-size)
                  ^CountingInputStream counter (:downloaded-bytes-counter request)]
              (loop []
                (let [size (.read input buffer)]
                  (when (pos? size)
                    (.write output buffer 0 size)
                    (when counter
                      (let [current (.getByteCount counter)
                            msg (with-out-str
                                  (print-progress-bar
                                    current
                                    length))]
                        (swap! *state update-in [:http-download url]
                               merge
                               {:current current
                                :total length
                                :message msg}))) ; TODO is message really required?
                    (recur))))))))
      (catch Exception e
        (log/error e "Error downloading" url "to" dest)
        (raynes-fs/delete dest))
      (finally
        (swap! *state assoc-in [:http-download url :running] false)
        (update-file-cache! dest)
        (log/info "Finished downloading" url "to" dest)))))


(defmethod event-handler ::http-downloadable
  [{:keys [downloadable]}]
  (log/info "Request to download" downloadable)
  (future
    (deref
      (event-handler
        {:event/type ::http-download
         :dest (resource-dest downloadable)
         :url (:download-url downloadable)}))
    (case (:resource-type downloadable)
      ::map (force-update-battle-map *state)
      ::mod (force-update-battle-mod *state)
      ::engine (reconcile-engines *state)
      nil)))


(defmethod event-handler ::extract-7z
  [{:keys [file dest]}]
  (future
    (try
      (swap! *state assoc-in [:extracting file] true)
      (if dest
        (fs/extract-7z-fast file dest)
        (fs/extract-7z-fast file))
      (reconcile-engines *state)
      (catch Exception e
        (log/error e "Error extracting 7z" file))
      (finally
        (swap! *state assoc-in [:extracting file] false)))))


(def resource-types
  [::engine ::map ::mod ::sdp]) ; TODO split out packaging type from resource type...

(defn update-importable
  [{:keys [resource-file resource-name resource-type] :as importable}]
  (log/info "Finding name for importable" importable)
  (if resource-name
    (log/info "Skipping known import" importable)
    (let [resource-name (case resource-type
                          ::map (:map-name (fs/read-map-data resource-file))
                          ::mod (:mod-name (read-mod-data resource-file))
                          ::engine (:engine-version (fs/engine-data resource-file))
                          ::sdp (:mod-name (read-mod-data resource-file)))
          now (u/curr-millis)]
      (swap! *state update-in [:importables-by-path (fs/canonical-path resource-file)]
             assoc :resource-name resource-name
             :resource-updated now)
      resource-name)))

(defmethod task-handler ::update-importable [{:keys [importable]}]
  (update-importable importable))

(defn importable-data [resource-type import-source-name now resource-file]
  {:resource-type resource-type
   :import-source-name import-source-name
   :resource-file resource-file
   :resource-filename (fs/filename resource-file)
   :resource-updated now})

(defmethod task-handler ::scan-imports
  [{root :file import-source-name :import-source-name}]
  (log/info "Scanning for possible imports from" root)
  (let [map-files (fs/map-files root)
        mod-files (fs/mod-files root)
        engine-dirs (fs/engine-dirs root)
        sdp-files (rapid/sdp-files root)
        now (u/curr-millis)
        importables (concat
                      (map (partial importable-data ::map import-source-name now) map-files)
                      (map (partial importable-data ::mod import-source-name now) (concat mod-files sdp-files))
                      (map (partial importable-data ::engine import-source-name now) engine-dirs))
        importables-by-path (->> importables
                                 (map (juxt (comp fs/canonical-path :resource-file) identity))
                                 (into {}))]
    (log/info "Found imports" (frequencies (map :resource-type importables)))
    (swap! *state update :importables-by-path merge importables-by-path)
    importables-by-path
    #_
    (doseq [importable (filter (comp #{::engine} :resource-type) importables)]
      (add-task! *state {::task-type ::update-importable
                         :importable importable}))))



(def download-sources
  [{:download-source-name "SpringFiles Maps"
    :url http/springfiles-maps-url
    :resources-fn http/html-downloadables}
   {:download-source-name "SpringFightClub Maps"
    :url (str http/springfightclub-root "/maps")
    :resources-fn http/html-downloadables}
   {:download-source-name "SpringFightClub Games"
    :url http/springfightclub-root
    :resources-fn (partial http/html-downloadables
                           (fn [url]
                             (when (and url (string/ends-with? url ".sdz"))
                               ::mod)))}
   {:download-source-name "SpringLauncher"
    :url http/springlauncher-root
    :resources-fn http/get-springlauncher-downloadables}
   {:download-source-name "SpringRTS buildbot"
    :url http/springrts-buildbot-root
    :resources-fn http/crawl-springrts-engine-downloadables}
   {:download-source-name "BAR GitHub spring"
    :url http/bar-spring-releases-url
    :resources-fn http/get-github-release-engine-downloadables}])


(def downloadable-update-cooldown
  (* 1000 60 60 24)) ; 1 day ?

(defmethod task-handler ::update-downloadables
  [{:keys [resources-fn url download-source-name] :as source}]
  (log/info "Getting resources for possible download from" download-source-name "at" url)
  (let [now (u/curr-millis)
        last-updated (or (-> *state deref :downloadables-last-updated (get url)) 0)]
    (if (< downloadable-update-cooldown (- now last-updated))
      (do
        (log/info "Updating downloadables from" url)
        (swap! *state assoc-in [:downloadables-last-updated url] now)
        (let [downloadables (resources-fn source)
              downloadables-by-url (->> downloadables
                                        (map (juxt :download-url identity))
                                        (into {}))]
          (log/info "Found downloadables from" download-source-name "at" url
                    (frequencies (map :resource-type downloadables)))
          (swap! *state update :downloadables-by-url merge downloadables-by-url)
          downloadables-by-url))
      (log/info "Too soon to check downloads from" url))))

#_
(task-handler
  (merge {::task-type ::update-downloadables}
         (last download-sources)))


(defmethod event-handler ::import-source-change
  [{:fx/keys [event]}]
  (swap! *state assoc :import-source-name (:import-source-name event)))

(defn import-source-cell
  [{:keys [file import-source-name]}]
  {:text (str import-source-name
              (when file
                (str " ( at " (fs/canonical-path file) " )")))})

(defn import-type-cell
  [import-type]
  {:text (if import-type
           (name import-type)
           " < nil > ")})

(defmethod event-handler ::assoc
  [{:fx/keys [event] :as e}]
  (swap! *state assoc (:key e) event))

(defmethod event-handler ::assoc-in
  [{:fx/keys [event] :keys [path]}]
  (swap! *state assoc-in path event))

(defmethod event-handler ::dissoc
  [e]
  (swap! *state dissoc (:key e)))

(defmethod event-handler ::scan-imports
  [_e]
  (doseq [import-source import-sources]
    (add-task! *state (merge
                        {::task-type ::scan-imports}
                        import-source))))


(defn import-window
  [{:keys [copying file-cache import-filter import-type import-source-name importables-by-path
           show-importer show-stale tasks]}]
  (let [import-source (->> import-sources
                           (filter (comp #{import-source-name} :import-source-name))
                           first)
        now (u/curr-millis)
        importables (->> (or (vals importables-by-path) [])
                         (filter (fn [{:keys [resource-updated]}]
                                   (if show-stale
                                     true
                                     (and resource-updated
                                          (< (- now downloadable-update-cooldown) resource-updated)))))
                         (filter (fn [importable]
                                   (if import-source-name
                                     (= import-source-name (:import-source-name importable))
                                     true)))
                         (filter (fn [{:keys [resource-file resource-name]}]
                                   (if-not (string/blank? import-filter)
                                     (let [path (fs/canonical-path resource-file)]
                                       (or (and path
                                                (string/includes?
                                                  (string/lower-case path)
                                                  (string/lower-case import-filter)))
                                           (and resource-name
                                                (string/includes?
                                                  (string/lower-case resource-name)
                                                  (string/lower-case import-filter)))))
                                     true)))
                         (filter (fn [{:keys [resource-type]}]
                                   (if import-type
                                     (= import-type resource-type)
                                     true))))
        import-tasks (->> tasks
                          (filter (comp #{::import} ::task-type))
                          (map (comp fs/canonical-path :resource-file :importable))
                          set)]
    {:fx/type :stage
     :x 300
     :y 300
     :showing show-importer
     :title "alt-spring-lobby Importer"
     :on-close-request (fn [^javafx.stage.WindowEvent e]
                         (swap! *state assoc :show-importer false)
                         (.consume e))
     :width download-window-width
     :height download-window-height
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      {:fx/type :v-box
       :children
       [{:fx/type :button
         :style {:-fx-font-size 16}
         :text "Scan Imports"
         :on-action {:event/type ::scan-imports}}
        {:fx/type :h-box
         :alignment :center-left
         :style {:-fx-font-size 16}
         :children
         (concat
           [{:fx/type :label
             :text " Filter source: "}
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
                 :text "Clear source filter"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::dissoc
                            :key :import-source-name}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-close:16:white"}}}
              {:fx/type fx.ext.node/with-tooltip-props
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
                 :icon-literal "mdi-folder:16:white"}}}])
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :show-delay [10 :ms]
               :style {:-fx-font-size 14}
               :text "Hide downloadables not discovered in the last polling cycle, default 24h"}}
             :desc
             {:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :check-box
                :selected (boolean show-stale)
                :h-box/margin 8
                :on-selected-changed {:event/type ::assoc
                                      :key :show-stale}}
               {:fx/type :label
                :text "Show stale artifacts"}]}}])}
        {:fx/type :h-box
         :alignment :center-left
         :style {:-fx-font-size 16}
         :children
         (concat
           [{:fx/type :label
             :text " Filter: "}
            {:fx/type :text-field
             :text import-filter
             :prompt-text "Filter by name or path"
             :on-text-changed {:event/type ::assoc
                               :key :import-filter}}]
           (when-not (string/blank? import-filter)
             [{:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :text "Clear filter"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::dissoc
                            :key :import-filter}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-close:16:white"}}}])
           [{:fx/type :label
             :text " Filter type: "}
            {:fx/type :combo-box
             :value import-type
             :items resource-types
             :button-cell import-type-cell
             ;:placeholder {:import-source-name " < pick a source > "} TODO figure out placeholders
             :cell-factory
             {:fx/cell-type :list-cell
              :describe import-type-cell}
             :on-value-changed {:event/type ::assoc
                                :key :import-type}
             :tooltip {:fx/type :tooltip
                       :show-delay [10 :ms]
                       :text "Choose import type"}}]
           (when import-type
             [{:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :text "Clear type filter"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::dissoc
                            :key :import-type}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-close:16:white"}}}]))}
        {:fx/type :label
         :text (str (count importables) " artifacts")}
        {:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :v-box/vgrow :always
         :items importables
         :columns
         [{:fx/type :table-column
           :text "Last Seen Ago"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [{:keys [resource-updated]}]
                          (when resource-updated
                            {:text (str (humanize/duration (- now resource-updated)))}))}}
          {:fx/type :table-column
           :text "Source"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:import-source-name i))})}}
          {:fx/type :table-column
           :text "Type"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (comp import-type-cell :resource-type)}}
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
            :describe
            (fn [importable]
              (let [source-path (some-> importable :resource-file fs/canonical-path)
                    dest-path (some-> importable resource-dest fs/canonical-path)
                    copying (or (-> copying (get source-path) :status)
                                (-> copying (get dest-path) :status))
                    in-progress (boolean
                                  (or (contains? import-tasks source-path)
                                      copying))]
                {:text ""
                 :graphic
                 (if (file-exists? file-cache dest-path)
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-check:16:white"}
                   {:fx/type :button
                    :text (cond
                            (contains? import-tasks source-path) "queued"
                            copying "copying"
                            :else "")
                    :disable in-progress
                    :tooltip
                    {:fx/type :tooltip
                     :show-delay [10 :ms]
                     :text (str "Copy to " dest-path)}
                    :on-action {:event/type ::add-task
                                :task
                                {::task-type ::import
                                 :importable importable}}
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal "mdi-content-copy:16:white"}})}))}}]}]}}}))


(defmethod event-handler ::download-source-change
  [{:fx/keys [event]}]
  (swap! *state assoc :download-source-name (:download-source-name event)))

(defn download-source-cell
  [{:keys [url download-source-name]}]
  {:text (str download-source-name " ( at " url " )")})

(defmethod event-handler ::update-downloadables
  [_e]
  (doseq [download-source download-sources]
    (add-task! *state (merge
                        {::task-type ::update-downloadables}
                        download-source))))

(defn download-window
  [{:keys [download-filter download-type download-source-name downloadables-by-url file-cache
           http-download show-downloader show-stale]}]
  (let [download-source (->> download-sources
                             (filter (comp #{download-source-name} :download-source-name))
                             first)
        now (u/curr-millis)
        downloadables (->> (or (vals downloadables-by-url) [])
                           (filter :resource-type)
                           (filter (fn [{:keys [resource-updated]}]
                                     (if show-stale
                                       true
                                       (and resource-updated
                                            (< (- now downloadable-update-cooldown) resource-updated)))))
                           (filter (fn [downloadable]
                                     (if download-source-name
                                       (= download-source-name (:download-source-name downloadable))
                                       true)))
                           (filter (fn [{:keys [resource-filename resource-name]}]
                                     (if download-filter
                                       (or (and resource-filename
                                                (string/includes?
                                                  (string/lower-case resource-filename)
                                                  (string/lower-case download-filter)))
                                           (and resource-name
                                                (string/includes?
                                                  (string/lower-case resource-name)
                                                  (string/lower-case download-filter))))
                                       true)))
                           (filter (fn [{:keys [resource-type]}]
                                     (if download-type
                                       (= download-type resource-type)
                                       true))))]
    {:fx/type :stage
     :x 200
     :y 200
     :showing show-downloader
     :title "alt-spring-lobby Downloader"
     :on-close-request (fn [^javafx.stage.WindowEvent e]
                         (swap! *state assoc :show-downloader false)
                         (.consume e))
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
             :text " Filter source: "}
            {:fx/type :combo-box
             :value download-source
             :items download-sources
             :button-cell download-source-cell
             ;:placeholder {:import-source-name " < pick a source > "} TODO figure out placeholders
             :cell-factory
             {:fx/cell-type :list-cell
              :describe download-source-cell}
             :on-value-changed {:event/type ::download-source-change}
             :tooltip {:fx/type :tooltip
                       :show-delay [10 :ms]
                       :text "Choose download source"}}]
           (when download-source
             [{:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :text "Clear source filter"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::dissoc
                            :key :download-source-name}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-close:16:white"}}}
              {:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :text "Open download source url"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::desktop-open-url
                            :url (:url download-source)}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-web:16:white"}}}])
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :show-delay [10 :ms]
               :style {:-fx-font-size 14}
               :text "Hide downloadables not discovered in the last polling cycle, default 24h"}}
             :desc
             {:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :check-box
                :selected (boolean show-stale)
                :h-box/margin 8
                :on-selected-changed {:event/type ::assoc
                                      :key :show-stale}}
               {:fx/type :label
                :text "Show stale artifacts"}]}}])}
        {:fx/type :h-box
         :alignment :center-left
         :style {:-fx-font-size 16}
         :children
         (concat
           [{:fx/type :label
             :text " Filter: "}
            {:fx/type :text-field
             :text download-filter
             :prompt-text "Filter by name or path"
             :on-text-changed {:event/type ::assoc
                               :key :download-filter}}]
           (when-not (string/blank? download-filter)
             [{:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :text "Clear filter"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::dissoc
                            :key :download-filter}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-close:16:white"}}}])
           [{:fx/type :label
             :text " Filter type: "}
            {:fx/type :combo-box
             :value download-type
             :items resource-types
             :button-cell import-type-cell
             ;:placeholder {:import-source-name " < pick a source > "} TODO figure out placeholders
             :cell-factory
             {:fx/cell-type :list-cell
              :describe import-type-cell}
             :on-value-changed {:event/type ::assoc
                                :key :download-type}
             :tooltip {:fx/type :tooltip
                       :show-delay [10 :ms]
                       :text "Choose download type"}}]
           (when download-type
             [{:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :text "Clear type filter"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::dissoc
                            :key :download-type}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-close:16:white"}}}]))}
        {:fx/type :label
         :text (str (count downloadables) " artifacts")}
        {:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :v-box/vgrow :always
         :items downloadables
         :columns
         [{:fx/type :table-column
           :text "Last Seen Ago"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [{:keys [resource-updated]}]
                          (when resource-updated
                            {:text (str (humanize/duration (- now resource-updated)))}))}}
          {:fx/type :table-column
           :text "Source"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:download-source-name i))})}}
          {:fx/type :table-column
           :text "Type"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (comp import-type-cell :resource-type)}}
          {:fx/type :table-column
           :text "File"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:resource-filename i))})}}
          {:fx/type :table-column
           :text "URL"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [i] {:text (str (:download-url i))})}}
          {:fx/type :table-column
           :text "Download"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [{:keys [download-url resource-filename] :as downloadable}]
              (let [dest-file (some-> downloadable resource-dest)
                    dest-path (some-> dest-file fs/canonical-path)
                    download (get http-download download-url)
                    in-progress (:running download)
                    extract-file (when dest-file
                                   (io/file (fs/isolation-dir) "engine" (fs/filename dest-file)))]
                {:text ""
                 :graphic
                 (cond
                   in-progress
                   {:fx/type :label
                    :text (str (download-progress download))}
                   (and (not in-progress)
                        (not (file-exists? file-cache dest-path)))
                   {:fx/type :button
                    :tooltip
                    {:fx/type :tooltip
                     :show-delay [10 :ms]
                     :text (str "Download to " dest-path)}
                    :on-action {:event/type ::http-downloadable
                                :downloadable downloadable}
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal "mdi-download:16:white"}}
                   (and
                        (file-exists? file-cache dest-path)
                        dest-file
                        (or
                          (http/engine-archive? resource-filename)
                          (http/bar-engine-filename? resource-filename))
                        extract-file
                        (not (file-exists? file-cache (fs/canonical-path extract-file))))
                   {:fx/type :button
                    :tooltip
                    {:fx/type :tooltip
                     :show-delay [10 :ms]
                     :text (str "Extract to " extract-file)}
                    :on-action
                    {:event/type ::extract-7z
                     :file dest-file
                     :dest extract-file}
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal "mdi-archive:16:white"}}
                   :else
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-check:16:white"})}))}}]}]}}}))


(defn rapid-download-window
  [{:keys [engine-version engines rapid-download rapid-filter rapid-repo rapid-repos rapid-versions
           rapid-data-by-hash sdp-files show-rapid-downloader]}]
  (let [sdp-files (or sdp-files [])
        sdp-hashes (set (map rapid/sdp-hash sdp-files))]
    {:fx/type :stage
     :showing show-rapid-downloader
     :title "alt-spring-lobby Rapid Downloader"
     :on-close-request (fn [^javafx.stage.WindowEvent e]
                         (swap! *state assoc :show-rapid-downloader false)
                         (.consume e))
     :width download-window-width
     :height download-window-height
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      {:fx/type :v-box
       :children
       [{:fx/type :h-box
         :style {:-fx-font-size 16}
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Engine for pr-downloader: "}
          {:fx/type :choice-box
           :value (str engine-version)
           :items (or (->> engines
                           (map :engine-version)
                           sort)
                      [])
           :on-value-changed {:event/type ::version-change}}]}
        {:fx/type :h-box
         :style {:-fx-font-size 16}
         :alignment :center-left
         :children
         (concat
           [{:fx/type :label
             :text " Filter Repo: "}
            {:fx/type :choice-box
             :value (str rapid-repo)
             :items (or rapid-repos [])
             :on-value-changed {:event/type ::rapid-repo-change}}]
           (when rapid-repo
             [{:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :text "Clear rapid repo filter"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::dissoc
                            :key :rapid-repo}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-close:16:white"}}}])
           [{:fx/type :label
             :text " Rapid Filter: "}
            {:fx/type :text-field
             :text rapid-filter
             :prompt-text "Filter by name or path"
             :on-text-changed {:event/type ::assoc
                               :key :rapid-filter}}]
           (when-not (string/blank? rapid-filter)
             [{:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type :tooltip
                 :show-delay [10 :ms]
                 :text "Clear filter"}}
               :desc
               {:fx/type :button
                :on-action {:event/type ::dissoc
                            :key :rapid-filter}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-close:16:white"}}}]))}
        {:fx/type :table-view
         :column-resize-policy :constrained ; TODO auto resize
         :items (or (->> rapid-versions
                         (filter
                           (fn [{:keys [id]}]
                             (if rapid-repo
                               (string/starts-with? id (str rapid-repo ":"))
                               true)))
                         (filter
                           (fn [{:keys [version]}]
                             (if-not (string/blank? rapid-filter)
                               (string/includes?
                                 (string/lower-case version)
                                 (string/lower-case rapid-filter))
                               true))))
                    [])
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
                                  :engine-file
                                  (:file
                                    (spring/engine-details engines engine-version))}
                      :graphic
                      {:fx/type font-icon/lifecycle
                       :icon-literal "mdi-download:16:white"}}}))))}}]}
        {:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :style {:-fx-font-size 16}
           :text " Packages"}
          {:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type :tooltip
             :show-delay [10 :ms]
             :text "Open rapid packages directory"}}
           :desc
           {:fx/type :button
            :on-action {:event/type ::desktop-browse-dir
                        :file (io/file (fs/isolation-dir) "packages")}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-folder:16:white"}}}]}
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
              {:text (str (fs/filename i))})}}
          {:fx/type :table-column
           :text "ID"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (->> i
                          rapid/sdp-hash
                          (get rapid-data-by-hash)
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
                          (get rapid-data-by-hash)
                          :version
                          str)})}}]}]}}}))

(defmethod event-handler ::watch-replay
  [{:keys [engine-version engines replay-file]}]
  (future
    (try
      (let [state @*state
            demofile (fs/wslpath replay-file)]
        (spring/start-game
          (merge
            (select-keys state [:client :username])
            {:engines engines
             :battle {:battle-id "replay"} ; fake battle and battles
             :battles {"replay"
                       {:battle-version engine-version
                        :host-username (:username state)
                        :scripttags
                        {:game {:demofile demofile}}}}})))
      (catch Exception e
        (log/error e "Error watching replay" replay-file)))))


(defn replays-window
  [{:keys [engines show-replays]}]
  (let [replay-files (fs/replay-files) ; TODO FIXME IO IN RENDER
        replays (->> replay-files
                     (map
                       (fn [f]
                         {:file f
                          :filename (fs/filename f)
                          :file-size (fs/size f)
                          :parsed-filename (replay/parse-replay-filename f)
                          :header (replay/decode-replay-header f)}))
                     (sort-by :filename)
                     reverse
                     doall)]
    {:fx/type :stage
     :x 400
     :y 400
     :showing show-replays
     :title "alt-spring-lobby Replays"
     :on-close-request (fn [^javafx.stage.WindowEvent e]
                         (swap! *state assoc :show-replays false)
                         (.consume e))
     :width download-window-width
     :height download-window-height
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      {:fx/type :v-box
       :children
       [
        {:fx/type :table-view
         :v-box/vgrow :always
         :column-resize-policy :constrained ; TODO auto resize
         :items replays
         :columns
         [{:fx/type :table-column
           :text "Filename"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i] {:text (-> i :file fs/filename str)})}}
          {:fx/type :table-column
           :text "Timestamp"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i] {:text (-> i :parsed-filename :timestamp str)})}}
          {:fx/type :table-column
           :text "Map"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i] {:text (-> i :parsed-filename :map-name str)})}}
          {:fx/type :table-column
           :text "Engine"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i] {:text (-> i :parsed-filename :engine-version str)})}}
          {:fx/type :table-column
           :text "Size"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i] {:text (-> i :file-size u/format-bytes)})}}
          {:fx/type :table-column
           :text "Watch"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text ""
               :graphic
               {:fx/type :button
                :text " Watch"
                :on-action
                {:event/type ::watch-replay
                 :replay-file (:file i)
                 :engines engines
                 :engine-version (-> i :parsed-filename :engine-version)}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-movie:16:white"}}})}}
          #_
          {:fx/type :table-column
           :text "Parsed Filename"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text
               (with-out-str
                 (pprint
                   (->> i :parsed-filename)))})}}
          #_
          {:fx/type :table-column
           :text "Header"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text
               (with-out-str
                 (pprint
                   (->> i
                        :header)))})}}]}]}}}))

(defn maps-window
  [{:keys [filter-maps-name maps show-maps]}]
  {:fx/type :stage
   :x 400
   :y 400
   :showing show-maps
   :title "alt-spring-lobby Maps"
   :on-close-request (fn [^javafx.stage.WindowEvent e]
                       (swap! *state assoc :show-maps false)
                       (.consume e))
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
           :text " Filter: "}
          {:fx/type :text-field
           :text (str filter-maps-name)
           :prompt-text "Filter by name or path"
           :on-text-changed {:event/type ::assoc
                             :key :filter-maps-name}}]
         (when-not (string/blank? filter-maps-name)
           [{:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :show-delay [10 :ms]
               :text "Clear filter"}}
             :desc
             {:fx/type :button
              :on-action {:event/type ::dissoc
                          :key :filter-maps-name}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-close:16:white"}}}]))}
      {:fx/type :flow-pane
       :vgap 5
       :hgap 5
       :padding 5
       :children
       (map
         (fn [{:keys [map-name]}]
           {:fx/type :v-box
            :style
            {:-fx-min-width map-browse-image-size
             :-fx-max-width map-browse-image-size
             :-fx-min-height map-browse-box-height
             :-fx-max-height map-browse-box-height}
            :children
            [{:fx/type :stack-pane
              :children
              [{:fx/type :image-view
                :image {:url (-> map-name fs/minimap-image-cache-file io/as-url str)
                        :background-loading true}
                :fit-width map-browse-image-size
                :fit-height map-browse-image-size
                :preserve-ratio true}
               {:fx/type :pane
                :style
                {:-fx-min-width map-browse-image-size
                 :-fx-max-width map-browse-image-size
                 :-fx-min-height map-browse-image-size
                 :-fx-max-height map-browse-image-size}}]}
             {:fx/type :label
              :wrap-text true
              :text (str map-name)}]})
         (let [filter-lc ((fnil string/lower-case "") filter-maps-name)]
           (->> maps
                (filter (fn [{:keys [map-name]}]
                          (and map-name
                               (string/includes? (string/lower-case map-name) filter-lc))))
                (sort-by :map-name))))}]}}})

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
  [{{:keys [battle battles file-events last-failed-message pop-out-battle
            show-downloader show-importer show-maps show-rapid-downloader show-replays
            standalone tasks users]
     :as state}
    :state}]
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
                 :pop-out-battle :scripttags :selected-battle :use-springlobby-modname]))]
           (when battle
             (if (:battle-id battle)
               (when (not pop-out-battle)
                 [(merge
                    {:fx/type battle-view}
                    (select-keys state battle-view-keys))])
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
         :on-close-request {:event/type ::pop-in-battle}
         :x 300
         :y 300
         :width battle-window-width
         :height battle-window-height
         :scene
         {:fx/type :scene
          :stylesheets stylesheets
          :root
          {:fx/type :h-box
           :children
           (concat []
             (when pop-out-battle
               [(merge
                  {:fx/type battle-view}
                  (select-keys state battle-view-keys))]))}}}])
     (when show-downloader
       [(merge
          {:fx/type download-window}
          (select-keys state
            [:download-filter :download-source-name :download-type :downloadables-by-url :file-cache
             :http-download :show-downloader :show-stale]))])
     (when show-importer
       [(merge
          {:fx/type import-window}
          (select-keys state
            [:copying :file-cache :import-filter :import-source-name :import-type
             :importables-by-path :show-importer :show-stale :tasks]))])
     (when show-maps
       [(merge
          {:fx/type maps-window}
          (select-keys state
            [:filter-maps-name :maps :show-maps]))])
     (when show-rapid-downloader
      (merge
        {:fx/type rapid-download-window}
        (select-keys state
          [:engine-version :engines :rapid-download :rapid-filter :rapid-repo :rapid-repos :rapid-versions
           :rapid-data-by-hash :sdp-files :show-rapid-downloader])))
     (when show-replays
       [(merge
          {:fx/type replays-window}
          (select-keys state
            [:engines :show-replays]))]))})


(defn init
  "Things to do on program init, or in dev after a recompile."
  [state-atom]
  (let [tasks-chimer (tasks-chimer-fn state-atom)
        force-update-chimer (force-update-chimer-fn state-atom)]
    (add-watchers state-atom)
    (add-task! state-atom {::task-type ::reconcile-engines})
    (add-task! state-atom {::task-type ::reconcile-mods})
    (add-task! state-atom {::task-type ::reconcile-maps})
    (add-task! state-atom {::task-type ::update-rapid})
    (event-handler {:event/type ::update-downloadables})
    (event-handler {:event/type ::scan-imports})
    {:chimers [tasks-chimer force-update-chimer]}))


(defn -main [& _args]
  (Platform/setImplicitExit true)
  (fs/init-7z!)
  (reset! *state (assoc (initial-state) :standalone true))
  (init *state)
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc
                          (fn [state]
                            {:fx/type root-view
                             :state state}))
            :opts {:fx.opt/map-event-handler event-handler})]
    (fx/mount-renderer *state r)))
