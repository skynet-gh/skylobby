(ns spring-lobby
  (:require
    [chime.core :as chime]
    [clj-http.client :as clj-http]
    [cljfx.api :as fx]
    [cljfx.component :as fx.component]
    [cljfx.ext.node :as fx.ext.node]
    [cljfx.ext.tab-pane :as fx.ext.tab-pane]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [cljfx.lifecycle :as fx.lifecycle]
    [cljfx.mutator :as fx.mutator]
    [cljfx.prop :as fx.prop]
    clojure.data
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.set]
    [clojure.string :as string]
    [com.evocomputing.colors :as colors]
    [crypto.random]
    [diehard.core :as dh]
    hashp.core
    java-time
    [manifold.deferred :as deferred]
    [manifold.stream :as s]
    [me.raynes.fs :as raynes-fs]
    [shams.priority-queue :as pq]
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.battles-buttons :as fx.battles-buttons]
    [skylobby.fx.channel :as fx.channel]
    [skylobby.fx.minimap :as fx.minimap]
    [skylobby.fx.players-table :as fx.players-table]
    [skylobby.resource :as resource]
    [spring-lobby.battle :as battle]
    [spring-lobby.client :as client]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.client.message :as message]
    [spring-lobby.fs :as fs]
    [spring-lobby.fs.sdfz :as replay]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.git :as git]
    [spring-lobby.http :as http]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.spring.uikeys :as uikeys]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [version-clj.core :as version])
  (:import
    (java.awt Desktop Desktop$Action)
    (java.io File)
    (java.time LocalDateTime)
    (java.util List TimeZone)
    (javafx.application Platform)
    (javafx.event Event)
    (javafx.scene Node)
    (javafx.scene.control ScrollPane Tab TextArea)
    (javafx.scene.input KeyCode KeyEvent ScrollEvent)
    (javafx.stage Screen WindowEvent)
    (manifold.stream SplicedStream)
    (org.apache.commons.io.input CountingInputStream))
  (:gen-class))


(set! *warn-on-reflection* true)


(declare limit-download-status reconcile-engines reconcile-maps reconcile-mods)


(def app-version (u/app-version))

(def wait-before-init-tasks-ms 10000)

(dh/defratelimiter limit-download-status {:rate 1}) ; one update per second

(def stylesheets
  [(str (io/resource "dark.css"))])

(def monospace-font-family
  (if (fs/windows?)
    "Consolas"
    "monospace"))

(def icons
  [(str (io/resource "icon16.png"))
   (str (io/resource "icon32.png"))
   (str (io/resource "icon64.png"))
   (str (io/resource "icon128.png"))
   (str (io/resource "icon256.png"))
   (str (io/resource "icon512.png"))
   (str (io/resource "icon1024.png"))])


(def main-window-width 1920)
(def main-window-height 1200)

(def download-window-width 1600)
(def download-window-height 800)

(def replays-window-width 2200)
(def replays-window-height 1200)

(def battle-window-width 1740)
(def battle-window-height 800)

(defn- screen-bounds []
  (let [screen (Screen/getPrimary)
        bounds (.getVisualBounds screen)]
    {:min-x (.getMinX bounds)
     :min-y (.getMinY bounds)
     :width (.getWidth bounds)
     :height (.getHeight bounds)}))


(def start-pos-r 10.0)

(def minimap-size 512)


;(def map-browse-image-size 98)
;(def map-browse-box-height 160)
(def map-browse-image-size 162)
(def map-browse-box-height 200)


; https://github.com/clojure/clojure/blob/28efe345d5e995dc152a0286fb0be81443a0d9ac/src/clj/clojure/instant.clj#L274-L279
(defn- read-file-tag [cs]
  (io/file cs))

; https://github.com/clojure/clojure/blob/0754746f476c4ddf6a6b699d9547830b2fdad17c/src/clj/clojure/core.clj#L7755-L7761
(def custom-readers
  {'spring-lobby/java.io.File #'spring-lobby/read-file-tag})

; https://stackoverflow.com/a/23592006/984393
(defmethod print-method File [f ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.io.File " (pr-str (fs/canonical-path f)))))


(defn- slurp-config-edn
  "Returns data loaded from a .edn file in this application's root directory."
  [edn-filename]
  (try
    (let [config-file (fs/config-file edn-filename)]
      (log/info "Slurping config edn from" config-file)
      (when (fs/exists? config-file)
        (let [data (->> config-file slurp (edn/read-string {:readers custom-readers}))]
          (if (map? data)
            data
            (do
              (log/warn "Config file data from" edn-filename "is not a map")
              {})))))
    (catch Exception e
      (log/warn e "Exception loading app edn file" edn-filename)
      (try
        (log/info "Copying bad config file for debug")
        (fs/copy (fs/config-file edn-filename) (fs/config-file (str edn-filename ".debug")))
        (catch Exception e
          (log/warn e "Exception copying bad edn file" edn-filename)))
      {})))


(def priority-overrides
  {::reconcile-engines 5
   ::reconcile-mods 4
   ::reconcile-maps 3
   ::import 2
   ::http-downloadable 2
   ::rapid-downloadable 2
   ::update-rapid-packages 1
   ::update-downloadables 1
   ::scan-imports 1})

(def default-task-priority 2)

(defn- task-priority [{::keys [task-priority task-type]}]
  (or task-priority
      (get priority-overrides task-type)
      default-task-priority))

(defn- initial-tasks []
  (pq/priority-queue task-priority :variant :set))

(defn- initial-file-events []
  (clojure.lang.PersistentQueue/EMPTY))


(def config-keys
  [:auto-get-resources :battle-title :battle-password :bot-name :bot-username :bot-version :chat-auto-scroll
   :console-auto-scroll :engine-version :extra-import-sources :extra-replay-sources :filter-replay
   :filter-replay-type :filter-replay-max-players :filter-replay-min-players :logins :map-name
   :mod-name :my-channels :password :pop-out-battle :preferred-color :rapid-repo
   :replays-watched :replays-window-details :server :servers :spring-isolation-dir :uikeys
   :username])


(defn- select-config [state]
  (select-keys state config-keys))

(defn- select-maps [state]
  (select-keys state [:maps]))

(defn- select-engines [state]
  (select-keys state [:engines]))

(defn- select-mods [state]
  (select-keys state [:mods]))

(defn- select-importables [state]
  (select-keys state
    [:importables-by-path]))

(defn- select-downloadables [state]
  (select-keys state
    [:downloadables-by-url :downloadables-last-updated]))

(defn- select-replays [state]
  (select-keys state
    [:bar-replays-page :online-bar-replays]))

(def state-to-edn
  [{:select-fn select-config
    :filename "config.edn"
    :pretty true}
   {:select-fn select-maps
    :filename "maps.edn"
    :pretty true}
   {:select-fn select-engines
    :filename "engines.edn"
    :pretty true}
   {:select-fn select-mods
    :filename "mods.edn"
    :pretty true}
   {:select-fn select-importables
    :filename "importables.edn"}
   {:select-fn select-downloadables
    :filename "downloadables.edn"}
   {:select-fn select-replays
    :filename "replays.edn"}])

(def default-server-port 8200)

(def default-servers
  {"lobby.springrts.com:8200"
   {:host "lobby.springrts.com"
    :port 8200
    :alias "SpringLobby"}
   "springfightclub.com:8200"
   {:host "springfightclub.com"
    :port 8200
    :alias "Spring Fight Club"}
   "road-flag.bnr.la:8200"
   {:host "road-flag.bnr.la"
    :port 8200
    :alias "Beyond All Reason"}})

(def minimap-types
  ["minimap" "metalmap" "heightmap"])


(defn- dummy-matchmaking-queues []
  (->> (iterate inc 1) ; TODO queues from server
       (take 8)
       (map (fn [i] (str i "v" i)))
       (concat ["ffa"])
       (map-indexed (fn [i n] [i {:queue-name n}]))
       (into {})))

(defn initial-state []
  (merge
    {:auto-get-resources true
     :battle-players-color-allyteam true
     :matchmaking-queues (dummy-matchmaking-queues)
     :spring-isolation-dir (fs/default-isolation-dir)
     :servers default-servers}
    (apply
      merge
      (doall
        (map
          (comp slurp-config-edn :filename) state-to-edn)))
    (slurp-config-edn "parsed-replays.edn")
    {:file-events (initial-file-events)
     :tasks (initial-tasks)
     :minimap-type (first minimap-types)
     :replay-minimap-type (first minimap-types)}))


(def ^:dynamic *state (atom {}))


(defn- send-message [client message]
  (u/update-console-log *state :client message)
  (message/send-message client message))

(defn- spit-app-edn
  "Writes the given data as edn to the given file in the application directory."
  ([data filename]
   (spit-app-edn data filename nil))
  ([data filename {:keys [pretty]}]
   (let [file (fs/config-file filename)]
     (fs/make-parent-dirs file)
     (log/info "Spitting edn to" file)
     (spit file
       (if pretty
         (with-out-str (pprint (if (map? data)
                                 (into (sorted-map) data)
                                 data)))
         (pr-str data))))))


(defn- add-watch-state-to-edn
  [state-atom]
  (add-watch state-atom :state-to-edn
    (fn [_k _ref old-state new-state]
      (doseq [{:keys [select-fn filename]} state-to-edn]
        (try
          (let [old-data (select-fn old-state)
                new-data (select-fn new-state)]
            (when (not= old-data new-data)
              (future
                (u/try-log (str "update " filename)
                  (spit-app-edn new-data filename)))))
          (catch Exception e
            (log/error e "Error in :state-to-edn for" filename "state watcher")))))))


(defn- read-map-data [maps map-name]
  (let [log-map-name (str "'" map-name "'")]
    (u/try-log (str "reading map data for " log-map-name)
      (if-let [map-file (some->> maps
                                 (filter (comp #{map-name} :map-name))
                                 first
                                 :file)]
        (let [map-data (fs/read-map-data map-file)]
          map-data)
        (log/warn "No file found for map" log-map-name)))))


(defn- read-mod-data
  ([f]
   (read-mod-data f nil))
  ([f opts]
   (let [mod-data
         (if (string/ends-with? (fs/filename f) ".sdp")
           (rapid/read-sdp-mod f opts)
           (fs/read-mod-file f opts))
         mod-name (spring/mod-name mod-data)]
     (assoc mod-data :mod-name mod-name))))


(defn- update-mod [state-atom file]
  (let [path (fs/canonical-path file)
        mod-data (try
                   (read-mod-data file {:modinfo-only false})
                   (catch Exception e
                     (log/error e "Error reading mod data for" file)))
        mod-details (select-keys mod-data [:file :mod-name ::fs/source :git-commit-id])
        mod-details (assoc mod-details
                           :is-game
                           (boolean
                             (or (:engineoptions mod-data)
                                 (:modoptions mod-data))))]
    (swap! state-atom update :mods
           (fn [mods]
             (set
               (cond->
                 (remove (comp #{path} fs/canonical-path :file) mods)
                 mod-details (conj mod-details)))))
    mod-data))


(defn- parse-mod-name-git [mod-name]
  (or (re-find #"(.+)\s([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\sgit:([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\s(\$VERSION)$" mod-name)))

(defn- mod-name-sans-git [mod-name]
  (when mod-name
    (if-let [[_all mod-prefix _git] (parse-mod-name-git mod-name)]
      mod-prefix
      mod-name)))

(defn- import-sources [extra-import-sources]
  (concat
    [{:import-source-name "Spring"
      :file (fs/spring-root)
      :builtin true}
     {:import-source-name "Beyond All Reason"
      :file (fs/bar-root)
      :builtin true}
     {:import-source-name "Zero-K"
      :file (fs/zerok-root)
      :builtin true}]
    extra-import-sources))


(defmulti event-handler :event/type)


(defn- add-task! [state-atom task]
  (if task
    (do
      (log/info "Adding task" (pr-str task))
      (swap! state-atom update :tasks conj task))
    (log/warn "Attempt to add nil task" task)))

(defn- add-tasks! [state-atom tasks-to-add]
  (log/info "Adding tasks" (pr-str tasks-to-add))
  (swap! state-atom update :tasks
    (fn [tasks]
      (apply conj tasks tasks-to-add))))

(defn- same-resource-file? [resource1 resource2]
  (= (:resource-file resource1)
     (:resource-file resource2)))

(defn- battle-map-details-relevant-keys [state]
  (select-keys
    state
    [:battle :battle-map-details :maps]))

(defn- battle-mod-details-relevant-keys [state]
  (select-keys
    state
    [:battle :battle-mod-details :mods]))

(defn- replay-map-and-mod-details-relevant-keys [state]
  (select-keys
    state
    [:maps :mods :online-bar-replays :parsed-replays-by-path :replay-map-details :replay-mod-details
     :selected-replay-file :selected-replay-id]))

(defn- fix-resource-relevant-keys [state]
  (select-keys
    state
    [:engine-version :engines :map-name :maps :mod-name :mods]))

(defn- auto-get-resources-relevant-keys [state]
  (select-keys
    state
    [:battle :downloadables-by-url :engines :importables-by-path :maps :mods :rapid-data-by-version]))


(defn- add-watchers
  "Adds all *state watchers."
  [state-atom]
  (remove-watch state-atom :state-to-edn)
  (remove-watch state-atom :debug)
  (remove-watch state-atom :battle-map-details)
  (remove-watch state-atom :battle-mod-details)
  (remove-watch state-atom :replay-map-and-mod-details)
  (remove-watch state-atom :fix-missing-resource)
  (remove-watch state-atom :fix-spring-isolation-dir)
  (remove-watch state-atom :spring-isolation-dir-changed)
  (remove-watch state-atom :auto-get-resources)
  (add-watch-state-to-edn state-atom)
  (add-watch state-atom :battle-map-details
    (fn [_k _ref old-state new-state]
      (when (not= (battle-map-details-relevant-keys old-state)
                  (battle-map-details-relevant-keys new-state))
        (try
          (let [old-battle-id (-> old-state :battle :battle-id)
                new-battle-id (-> new-state :battle :battle-id)
                old-battle-map (-> old-state :battles (get old-battle-id) :battle-map)
                new-battle-map (-> new-state :battles (get new-battle-id) :battle-map)
                battle-map-details (:battle-map-details new-state)
                map-changed (not= new-battle-map (:map-name battle-map-details))
                old-maps (:maps old-state)
                new-maps (:maps new-state)]
            (when (or (and (or (not= old-battle-id new-battle-id)
                               (not= old-battle-map new-battle-map))
                           (and (not (string/blank? new-battle-map))
                                (or (not (seq battle-map-details))
                                    map-changed)))
                      (and
                        (or (not (some (comp #{new-battle-map} :map-name) old-maps)))
                        (some (comp #{new-battle-map} :map-name) new-maps)))
              (if (->> new-maps (filter (comp #{new-battle-map} :map-name)) first)
                (do
                  (log/info "Updating battle map details for" new-battle-map "was" old-battle-map)
                  (future
                    (let [map-details (or (read-map-data new-maps new-battle-map) {})]
                      (swap! *state assoc :battle-map-details map-details))))
                (do
                  (log/info "Battle map not found, setting empty details for" new-battle-map "was" old-battle-map)
                  (swap! *state assoc :battle-map-details {})))))
          (catch Exception e
            (log/error e "Error in :battle-map-details state watcher"))))))
  (add-watch state-atom :battle-mod-details
    (fn [_k _ref old-state new-state]
      (when (not= (battle-mod-details-relevant-keys old-state)
                  (battle-mod-details-relevant-keys new-state))
        (try
          (let [old-battle-id (-> old-state :battle :battle-id)
                new-battle-id (-> new-state :battle :battle-id)
                old-battle-mod (-> old-state :battles (get old-battle-id) :battle-modname)
                new-battle-mod (-> new-state :battles (get new-battle-id) :battle-modname)
                new-battle-mod-sans-git (mod-name-sans-git new-battle-mod)
                mod-name-set (set [new-battle-mod new-battle-mod-sans-git])
                filter-fn (comp mod-name-set mod-name-sans-git :mod-name)
                battle-mod-details (:battle-mod-details new-state)
                mod-changed (not= new-battle-mod (:mod-name battle-mod-details))
                old-mods (:mods old-state)
                new-mods (:mods new-state)]
            (when (or (and (or (not= old-battle-id new-battle-id)
                               (not= old-battle-mod new-battle-mod))
                           (and (not (string/blank? new-battle-mod))
                                (or (not (seq battle-mod-details))
                                    mod-changed)))
                      (and
                        (or (not (some (comp #{new-battle-mod} :mod-name) old-mods)))
                        (some (comp #{new-battle-mod} :mod-name) new-mods)))
              (if (->> new-mods (filter filter-fn) first)
                (do
                  (log/info "Updating battle mod details for" new-battle-mod "was" old-battle-mod)
                  (future
                    (let [mod-details (or (some->> new-mods
                                                   (filter filter-fn)
                                                   first
                                                   :file
                                                   read-mod-data)
                                          {})]
                      (swap! *state assoc :battle-mod-details mod-details))))
                (do
                  (log/info "Battle mod not found, setting empty details for" new-battle-mod "was" old-battle-mod)
                  (swap! *state assoc :battle-mod-details {})))))
          (catch Exception e
            (log/error e "Error in :battle-mod-details state watcher"))))))
  (add-watch state-atom :replay-map-and-mod-details
    (fn [_k _ref old-state new-state]
      (when (not= (replay-map-and-mod-details-relevant-keys old-state)
                  (replay-map-and-mod-details-relevant-keys new-state))
        (try
          (let [old-selected-replay-file (:selected-replay-file old-state)
                old-replay-id (:selected-replay-id old-state)
                {:keys [online-bar-replays parsed-replays-by-path selected-replay-file selected-replay-id]} new-state

                old-replay-path (fs/canonical-path old-selected-replay-file)
                new-replay-path (fs/canonical-path selected-replay-file)

                old-replay (or (get parsed-replays-by-path old-replay-path)
                               (get online-bar-replays old-replay-id))
                new-replay (or (get parsed-replays-by-path new-replay-path)
                               (get online-bar-replays selected-replay-id))

                old-game (-> old-replay :body :script-data :game)
                old-mod (:gametype old-game)
                old-map (:mapname old-game)

                new-game (-> new-replay :body :script-data :game)
                new-mod (:gametype new-game)
                new-map (:mapname new-game)

                map-details (:replay-map-details new-state)
                mod-details (:replay-mod-details new-state)

                map-changed (not= new-map (:map-name map-details))
                mod-changed (not= new-mod (:mod-name mod-details))

                old-maps (:maps old-state)
                new-maps (:maps new-state)

                old-mods (:mods old-state)
                new-mods (:mods new-state)

                new-mod-sans-git (mod-name-sans-git new-mod)
                mod-name-set (set [new-mod new-mod-sans-git])
                filter-fn (comp mod-name-set mod-name-sans-git :mod-name)

                map-exists (some (comp #{new-map} :map-name) new-maps)
                mod-exists (some filter-fn new-mods)]
            (when (or (and (or (not= old-replay-path new-replay-path)
                               (not= old-mod new-mod))
                           (and (not (string/blank? new-mod))
                                (or (not (seq mod-details))
                                    mod-changed)))
                      (and
                        (or (not (some filter-fn old-mods)))
                        mod-exists))
              (if mod-exists
                (do
                  (log/info "Updating replay mod details for" new-mod "was" old-mod)
                  (future
                    (let [mod-details (or (some->> new-mods
                                                   (filter filter-fn)
                                                   first
                                                   :file
                                                   read-mod-data)
                                          {})]
                      (swap! *state assoc :replay-mod-details mod-details))))
                (future
                  (log/info "Replay mod not found, setting empty details for" new-mod "was" old-mod)
                  (swap! *state assoc :replay-mod-details {}))))
            (when (or (and (or (not= old-replay-path new-replay-path)
                               (not= old-map new-map))
                           (and (not (string/blank? new-map))
                                (or (not (seq map-details))
                                    map-changed)))
                      (and
                        (or (not (some (comp #{new-map} :map-name) old-maps)))
                        map-exists))
              (if map-exists
                (do
                  (log/info "Updating replay map details for" new-map "was" old-map)
                  (future
                    (let [map-details (or (read-map-data new-maps new-map) {})]
                      (swap! *state assoc :replay-map-details map-details))))
                (future
                  (log/info "Replay map not found, setting empty details for" new-map "was" old-map)
                  (swap! *state assoc :replay-map-details {})))))
          (catch Exception e
            (log/error e "Error in :battle-mod-details state watcher"))))))
  (add-watch state-atom :fix-missing-resource
    (fn [_k _ref old-state new-state]
      (when (not= (fix-resource-relevant-keys old-state)
                  (fix-resource-relevant-keys new-state))
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
  (add-watch state-atom :fix-spring-isolation-dir
    (fn [_k _ref old-state new-state]
      (when (not= old-state new-state)
        (try
          (let [{:keys [spring-isolation-dir]} new-state]
            (when-not (and spring-isolation-dir
                           (instance? File spring-isolation-dir))
              (log/info "Fixed spring isolation dir, was" spring-isolation-dir)
              (swap! state-atom assoc :spring-isolation-dir (fs/default-isolation-dir))))
          (catch Exception e
            (log/error e "Error in :fix-spring-isolation-dir state watcher"))))))
  (add-watch state-atom :spring-isolation-dir-changed
    (fn [_k _ref old-state new-state]
      (when (not= old-state new-state)
        (try
          (let [{:keys [spring-isolation-dir]} new-state]
            (when (and spring-isolation-dir
                       (instance? File spring-isolation-dir)
                       (not= (fs/canonical-path spring-isolation-dir)
                             (fs/canonical-path (:spring-isolation-dir old-state))))
              (log/info "Spring isolation dir changed from" (:spring-isolation-dir old-state)
                        "to" spring-isolation-dir "updating resources")
              (swap! *state
                (fn [{:keys [extra-import-sources] :as state}]
                  (-> state
                      (dissoc :engines :maps :mods :battle-map-details :battle-mod-details)
                      (update :tasks
                        (fn [tasks]
                          (conj tasks
                            {::task-type ::reconcile-engines}
                            {::task-type ::reconcile-mods}
                            {::task-type ::reconcile-maps}
                            {::task-type ::scan-imports
                             :sources (import-sources extra-import-sources)}
                            {::task-type ::update-rapid}
                            {::task-type ::refresh-replays}))))))))
          (catch Exception e
            (log/error e "Error in :spring-isolation-dir-changed state watcher"))))))
  (add-watch state-atom :auto-get-resources
    (fn [_k _ref old-state new-state]
      (when (not= (auto-get-resources-relevant-keys old-state)
                  (auto-get-resources-relevant-keys new-state))
        (try
          (when (and (:auto-get-resources new-state) (:spring-isolation-dir new-state))
            (let [{:keys [battle battles current-tasks downloadables-by-url engines file-cache importables-by-path maps mods rapid-data-by-version spring-isolation-dir tasks]} new-state
                  old-battle-details (-> old-state :battles (get (-> old-state :battle :battle-id)))
                  {:keys [battle-map battle-modname battle-version]} (get battles (:battle-id battle))
                  rapid-data (get rapid-data-by-version battle-modname)
                  rapid-id (:id rapid-data)
                  all-tasks (concat tasks (vals current-tasks))
                  rapid-task (->> all-tasks
                                  (filter (comp #{::rapid-download} ::task-type))
                                  (filter (comp #{rapid-id} :rapid-id))
                                  first)
                  engine-file (-> engines first :file)
                  importables (vals importables-by-path)
                  map-importable (some->> importables
                                          (filter (comp #{::map} :resource-type))
                                          (filter (partial resource/could-be-this-map? battle-map))
                                          first)
                  map-import-task (->> all-tasks
                                       (filter (comp #{::import} ::task-type))
                                       (filter (comp (partial same-resource-file? map-importable) :importable))
                                       first)
                  no-map (->> maps
                              (filter (comp #{battle-map} :map-name))
                              first
                              not)
                  downloadables (vals downloadables-by-url)
                  map-downloadable (->> downloadables
                                        (filter (comp #{::map} :resource-type))
                                        (filter (partial resource/could-be-this-map? battle-map))
                                        first)
                  map-download-task (->> all-tasks
                                         (filter (comp #{::http-downloadable} ::task-type))
                                         (filter (comp (partial same-resource-file? map-downloadable) :downloadable))
                                         first)
                  engine-details (spring/engine-details engines battle-version)
                  engine-importable (some->> importables
                                             (filter (comp #{::engine} :resource-type))
                                             (filter (partial resource/could-be-this-engine? battle-version))
                                             first)
                  engine-import-task (->> all-tasks
                                          (filter (comp #{::import} ::task-type))
                                          (filter (comp (partial same-resource-file? engine-importable) :importable))
                                          first)
                  engine-downloadable (->> downloadables
                                           (filter (comp #{::engine} :resource-type))
                                           (filter (partial resource/could-be-this-engine? battle-version))
                                           first)
                  engine-download-task (->> all-tasks
                                            (filter (comp #{::http-downloadable} ::task-type))
                                            (filter (comp (partial same-resource-file? engine-downloadable) :downloadable))
                                            first)
                  mod-downloadable (->> downloadables
                                        (filter (comp #{::mod} :resource-type))
                                        (filter (partial resource/could-be-this-mod? battle-modname))
                                        first)
                  mod-download-task (->> all-tasks
                                         (filter (comp #{::http-downloadable} ::task-type))
                                         (filter (comp (partial same-resource-file? mod-downloadable) :downloadable))
                                         first)
                  no-mod (->> mods
                              (filter (comp #{battle-modname} :mod-name))
                              first
                              not)
                  tasks [(when
                           (and (= battle-version (:battle-version old-battle-details))
                                (not engine-details))
                           (cond
                             (and engine-importable
                                  (not engine-import-task)
                                  (not (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir engine-importable))))
                             (do
                               (log/info "Adding task to auto import engine" engine-importable)
                               {::task-type ::import
                                :importable engine-importable
                                :spring-isolation-dir spring-isolation-dir})
                             (and (not engine-importable)
                                  engine-downloadable
                                  (not engine-download-task)
                                  (not (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir engine-downloadable))))
                             (do
                               (log/info "Adding task to auto download engine" engine-downloadable)
                               {::task-type ::http-downloadable
                                :downloadable engine-downloadable
                                :spring-isolation-dir spring-isolation-dir})
                             :else
                             nil))
                         (when
                           (and (= battle-map (:battle-map old-battle-details))
                                no-map)
                           (cond
                             (and map-importable
                                  (not map-import-task)
                                  (not (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir map-importable))))
                             (do
                               (log/info "Adding task to auto import map" map-importable)
                               {::task-type ::import
                                :importable map-importable
                                :spring-isolation-dir spring-isolation-dir})
                             (and (not map-importable)
                                  map-downloadable
                                  (not map-download-task)
                                  (not (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir map-downloadable))))
                             (do
                               (log/info "Adding task to auto download map" map-downloadable)
                               {::task-type ::http-downloadable
                                :downloadable map-downloadable
                                :spring-isolation-dir spring-isolation-dir})
                             :else
                             nil))
                         (when
                           (and (= battle-modname (:battle-modname old-battle-details))
                                no-mod)
                           (cond
                             (and rapid-id
                                  (not rapid-task)
                                  engine-file
                                  (not (fs/file-exists? file-cache (rapid/sdp-file spring-isolation-dir (str (:hash rapid-data) ".sdp")))))
                             (do
                               (log/info "Adding task to auto download rapid" rapid-id)
                               {::task-type ::rapid-download
                                :engine-file engine-file
                                :rapid-id rapid-id
                                :spring-isolation-dir spring-isolation-dir})
                             (and (not rapid-id)
                                  mod-downloadable
                                  (not mod-download-task)
                                  (not (fs/file-exists? file-cache (resource/resource-dest spring-isolation-dir mod-downloadable))))
                             (do
                               (log/info "Adding task to auto download mod" mod-downloadable)
                               {::task-type ::http-downloadable
                                :downloadable mod-downloadable
                                :spring-isolation-dir spring-isolation-dir})
                             :else
                             nil))]]
             (when-let [tasks (seq (filter some? tasks))]
               (add-tasks! *state tasks))))
          (catch Exception e
            (log/error e "Error in :auto-get-resources state watcher")))))))


(defmulti task-handler ::task-type)

(defmethod task-handler ::fn
  [{:keys [description function]}]
  (log/info "Running function task" description)
  (function))

(defmethod task-handler ::update-mod
  [{:keys [file]}]
  (update-mod *state file))

(defmethod task-handler :default [task]
  (when task
    (log/warn "Unknown task type" task)))


(defn- peek-task [min-priority tasks]
  (when-not (empty? tasks)
    (when-let [{::keys [task-priority] :as task} (peek tasks)]
      (when (<= min-priority (or task-priority default-task-priority))
        task))))

; https://www.eidel.io/2019/01/22/thread-safe-queues-clojure/
(defn- handle-task!
  ([state-atom worker-id]
   (handle-task! state-atom worker-id 1))
  ([state-atom worker-id min-priority]
   (let [[before _after] (swap-vals! state-atom
                           (fn [{:keys [tasks] :as state}]
                             (if (empty? tasks)
                               state ; don't update unnecessarily
                               (let [next-tasks (if-not (empty? tasks)
                                                  (let [{::keys [task-priority]} (peek tasks)]
                                                    (if (<= min-priority (or task-priority default-task-priority))
                                                      (pop tasks)
                                                      tasks)
                                                    (pop tasks))
                                                  tasks)
                                     task (peek-task min-priority tasks)]
                                 (-> state
                                     (assoc :tasks next-tasks)
                                     (assoc-in [:current-tasks worker-id] task))))))
         tasks (:tasks before)
         task (peek-task min-priority tasks)]
     (task-handler task)
     (when task
       (swap! state-atom update :current-tasks assoc worker-id nil))
     task)))

(defn- tasks-chimer-fn
  ([state-atom worker-id]
   (tasks-chimer-fn state-atom worker-id 1))
  ([state-atom worker-id min-priority]
   (log/info "Starting tasks chimer")
   (let [chimer
         (chime/chime-at
           (chime/periodic-seq
             (java-time/instant)
             (java-time/duration 1 :seconds))
           (fn [_chimestamp]
             (handle-task! state-atom worker-id min-priority))
           {:error-handler
            (fn [e]
              (log/error e "Error handling task")
              true)})]
     (fn [] (.close chimer)))))


(defn- file-cache-data [f]
  (if f
    {:canonical-path (fs/canonical-path f)
     :exists (fs/exists f)
     :is-directory (fs/is-directory? f)
     :last-modified (fs/last-modified f)}
    (log/warn (ex-info "stacktrace" {}) "Attempt to update file cache for nil file")))

(defn- file-cache-by-path [statuses]
  (->> statuses
       (filter some?)
       (map (juxt :canonical-path identity))
       (into {})))

(defn- update-file-cache!
  "Updates the file cache in state for this file. This is so that we don't need to do IO in render,
  and any updates to file statuses here can now cause UI redraws, which is good."
  [& fs]
  (let [statuses (for [f fs]
                   (let [f (if (string? f)
                             (io/file f)
                             f)]
                     (file-cache-data f)))
        status-by-path (file-cache-by-path statuses)]
    (swap! *state update :file-cache merge status-by-path)
    status-by-path))


(defn- replay-game-type [allyteam-counts]
  (let [one-per-allyteam? (= #{1} (set allyteam-counts))
        num-allyteams (count allyteam-counts)]
    (cond
      (= 2 num-allyteams)
      (if one-per-allyteam?
        :duel
        :team)
      (< 2 num-allyteams)
      (if one-per-allyteam?
        :ffa
        :teamffa)
      :else
      :invalid)))

(defn- replay-type-and-players [parsed-replay]
  (let [teams (->> parsed-replay
                   :body
                   :script-data
                   :game
                   (filter (comp #(string/starts-with? % "team") name first)))
        teams-by-allyteam (->> teams
                               (group-by (comp keyword (partial str "allyteam") :allyteam second)))
        allyteam-counts (sort (map (comp count second) teams-by-allyteam))]
    {:game-type (replay-game-type allyteam-counts)
     :player-counts allyteam-counts}))


(defn- replay-sources [{:keys [extra-replay-sources]}]
  (concat
    [{:replay-source-name "skylobby"
      :file (fs/replays-dir (fs/default-isolation-dir))
      :builtin true}
     {:replay-source-name "Beyond All Reason"
      :file (fs/replays-dir (fs/bar-root))
      :builtin true}
     {:replay-source-name "Spring"
      :file (fs/replays-dir (fs/spring-root))
      :builtin true}]
    extra-replay-sources))


(defn- refresh-replays
  [state-atom]
  (log/info "Refreshing replays")
  (let [before (u/curr-millis)
        {:keys [parsed-replays-by-path] :as state} @state-atom
        existing-paths (set (keys parsed-replays-by-path))
        all-files (mapcat
                    (fn [{:keys [file recursive replay-source-name]}]
                      (let [files (fs/replay-files file {:recursive recursive})]
                        (log/info "Found" (count files) "replay files from" replay-source-name "at" file)
                        (map
                          (juxt (constantly replay-source-name) identity)
                          files)))
                    (replay-sources state))
        todo (->> all-files
                  (remove (comp existing-paths fs/canonical-path second))
                  (sort-by (comp fs/filename second))
                  reverse)
        all-paths (set (map (comp fs/canonical-path second) all-files))
        this-round (take 100 todo)
        next-round (drop 100 todo)
        parsed-replays (->> this-round
                            (map
                              (fn [[source f]]
                                (let [replay (try
                                               (replay/decode-replay f)
                                               (catch Exception e
                                                 (log/error e "Error reading replay" f)))]
                                  [(fs/canonical-path f)
                                   (merge
                                     {:file f
                                      :filename (fs/filename f)
                                      :file-size (fs/size f)}
                                     replay
                                     {:source-name source}
                                     (replay-type-and-players replay))])))
                            doall)]
    (log/info "Parsed" (count this-round) "of" (count todo) "new replays in" (- (u/curr-millis) before) "ms")
    (let [new-state (swap! state-atom update :parsed-replays-by-path
                           (fn [old-replays]
                             (let [replays-by-path (if (map? old-replays) old-replays {})]
                               (->> replays-by-path
                                    (filter (comp all-paths first)) ; remove missing files
                                    (remove (comp zero? :file-size second)) ; remove empty files
                                    (remove (comp #{:invalid} :game-type second)) ; remove invalid
                                    (concat parsed-replays)
                                    (into {})))))]
      (if (seq next-round)
        (add-task! state-atom {::task-type ::refresh-replays})
        (do
          (when (seq this-round)
            (spit-app-edn
              (select-keys new-state [:parsed-replays-by-path])
              "parsed-replays.edn"))
          (add-task! state-atom {::task-type ::refresh-replay-resources}))))))

(defn- refresh-replay-resources
  [state-atom]
  (log/info "Refresh replay resources")
  (let [before (u/curr-millis)
        {:keys [downloadables-by-url importables-by-path parsed-replays-by-path]} @state-atom
        parsed-replays (vals parsed-replays-by-path)
        engine-versions (->> parsed-replays
                             (map (comp :engine-version :header))
                             (filter some?)
                             set)
        mod-names (->> parsed-replays
                       (map (comp :gametype :game :script-data :body))
                       set)
        map-names (->> parsed-replays
                       (map (comp :mapname :game :script-data :body))
                       set)
        downloads (vals downloadables-by-url)
        imports (vals importables-by-path)
        engine-downloads (filter (comp #{::engine} :resource-type) downloads)
        replay-engine-downloads (->> engine-versions
                                     (map
                                       (fn [engine-version]
                                         (when-let [imp (->> engine-downloads
                                                             (filter (partial resource/could-be-this-engine? engine-version))
                                                             first)]
                                           [engine-version imp])))
                                     (into {}))
        mod-imports (filter (comp #{::mod} :resource-type) imports)
        replay-mod-imports (->> mod-names
                                (map
                                  (fn [mod-name]
                                    (when-let [imp (->> mod-imports
                                                        (filter (partial resource/could-be-this-mod? mod-name))
                                                        first)]
                                      [mod-name imp])))
                                (into {}))
        mod-downloads (filter (comp #{::mod} :resource-type) downloads)
        replay-mod-downloads (->> mod-names
                                  (map
                                    (fn [mod-name]
                                      (when-let [dl (->> mod-downloads
                                                         (filter (partial resource/could-be-this-mod? mod-name))
                                                         first)]
                                        [mod-name dl])))
                                  (into {}))
        map-imports (filter (comp #{::map} :resource-type) imports)
        replay-map-imports (->> map-names
                                (map
                                  (fn [map-name]
                                    (when-let [imp (->> map-imports
                                                        (filter (partial resource/could-be-this-map? map-name))
                                                        first)]
                                      [map-name imp])))
                                (into {}))
        map-downloads (filter (comp #{::map} :resource-type) downloads)
        replay-map-downloads (->> map-names
                                  (map
                                    (fn [map-name]
                                      (when-let [dl (->> map-downloads
                                                         (filter (partial resource/could-be-this-map? map-name))
                                                         first)]
                                        [map-name dl])))
                                  (into {}))]
    (log/info "Refreshed replay resources in" (- (u/curr-millis) before) "ms")
    (swap! state-atom assoc
           :replay-downloads-by-engine replay-engine-downloads
           :replay-downloads-by-mod replay-mod-downloads
           :replay-imports-by-mod replay-mod-imports
           :replay-downloads-by-map replay-map-downloads
           :replay-imports-by-map replay-map-imports)))

(defn- reconcile-engines
  "Reads engine details and updates missing engines in :engines in state."
  ([]
   (reconcile-engines *state))
  ([state-atom]
   (swap! state-atom assoc :update-engines true)
   (log/info "Reconciling engines")
   (apply update-file-cache! (file-seq (fs/download-dir))) ; TODO move this somewhere
   (let [before (u/curr-millis)
         {:keys [spring-isolation-dir] :as state} @state-atom
         _ (log/info "Updating engines in" spring-isolation-dir)
         engine-dirs (fs/engine-dirs spring-isolation-dir)
         known-canonical-paths (->> state :engines
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
                                (remove (comp (partial fs/descendant? spring-isolation-dir) io/file)))))
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
                   (filter (comp fs/canonical-path :file))
                   (remove (comp to-remove fs/canonical-path :file))
                   set)))
     (swap! state-atom assoc :update-engines false)
     {:to-add-count (count to-add)
      :to-remove-count (count to-remove)})))

(defn- force-update-battle-engine
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


(defn- remove-all-duplicate-mods
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

(defn- reconcile-mods
  "Reads mod details and updates missing mods in :mods in state."
  [state-atom]
  (swap! state-atom assoc :update-mods true)
  (log/info "Reconciling mods")
  (remove-all-duplicate-mods state-atom)
  (let [before (u/curr-millis)
        {:keys [mods spring-isolation-dir]} @state-atom
         _ (log/info "Updating mods in" spring-isolation-dir)
        {:keys [rapid archive directory]} (group-by ::fs/source mods)
        known-file-paths (set (map (comp fs/canonical-path :file) (concat archive directory)))
        known-rapid-paths (set (map (comp fs/canonical-path :file) rapid))
        mod-files (fs/mod-files spring-isolation-dir)
        sdp-files (rapid/sdp-files spring-isolation-dir)
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
                               (remove (comp (partial fs/descendant? spring-isolation-dir) io/file)))))]
    (apply update-file-cache! all-paths)
    (log/info "Found" (count to-add-file) "mod files and" (count to-add-rapid)
              "rapid files to scan for mods in" (- (u/curr-millis) before) "ms")
    (doseq [file (concat to-add-file to-add-rapid)]
      (log/info "Reading mod from" file)
      (update-mod *state file))
    (log/info "Removing mods with no name, and" (count missing-files) "mods with missing files")
    (swap! state-atom
           (fn [state]
             (-> state
                 (update :mods (fn [mods]
                                 (->> mods
                                      (filter #(contains? % :is-game))
                                      (remove (comp string/blank? :mod-name))
                                      (remove (comp missing-files fs/canonical-path :file))
                                      set)))
                 (dissoc :update-mods))))
    {:to-add-file-count (count to-add-file)
     :to-add-rapid-count (count to-add-rapid)}))

(defn- force-update-battle-mod
  ([]
   (force-update-battle-mod *state))
  ([state-atom]
   (log/info "Force updating battle mod")
   (swap! *state
     (fn [state]
       (if (seq (:battle-mod-details state))
         state
         (assoc state :battle-mod-details nil))))
   (reconcile-mods state-atom)
   (let [{:keys [battle battles mods]} @state-atom
         battle-id (:battle-id battle)
         battle-modname (-> battles (get battle-id) :battle-modname)
         _ (log/debug "Force updating battle mod details for" battle-modname)
         battle-modname-sans-git (mod-name-sans-git battle-modname)
         mod-name-set (set [battle-modname battle-modname-sans-git])
         filter-fn (comp mod-name-set mod-name-sans-git :mod-name)
         mod-details (or (some->> mods
                                  (filter filter-fn)
                                  first
                                  :file
                                  read-mod-data)
                         {})]
     (swap! *state assoc :battle-mod-details mod-details)
     mod-details)))


(defn- update-cached-minimaps
  ([maps]
   (update-cached-minimaps maps nil))
  ([maps opts]
   (let [to-update
         (->> maps
              (filter
                (fn [map-details]
                  (let [minimap-file (-> map-details :map-name fs/minimap-image-cache-file)]
                    (or (:force opts) (not (fs/exists minimap-file))))))
              (sort-by :map-name))]
     (log/info (count to-update) "maps do not have cached minimap image files")
     (doseq [map-details to-update]
       (if-let [map-file (:file map-details)]
         (do
           (log/info "Caching minimap for" map-file)
           (let [{:keys [map-name smf]} (fs/read-map-data map-file)]
             (when-let [minimap-image-scaled (:minimap-image-scaled smf)]
               (fs/write-image-png minimap-image-scaled (fs/minimap-image-cache-file map-name)))))
         (log/error "Map is missing file" (:map-name map-details)))))))

(defn- reconcile-maps
  "Reads map details and caches for maps missing from :maps in state."
  [state-atom]
  (swap! state-atom assoc :update-maps true)
  (log/info "Reconciling maps")
  (let [before (u/curr-millis)
        {:keys [battle battles spring-isolation-dir] :as state} @state-atom
        _ (log/info "Updating maps in" spring-isolation-dir)
        battle-map (-> battles (get (:battle-id battle)) :battle-map)
        map-files (fs/map-files spring-isolation-dir)
        known-files (->> state :maps (map :file) set)
        known-paths (->> known-files (map fs/canonical-path) set)
        todo (remove (comp known-paths fs/canonical-path) map-files)
        priorities (filterv (comp (partial resource/could-be-this-map? battle-map) (fn [f] {:resource-filename (fs/filename f)})) todo)
        _ (log/info "Prioritizing map files for battle" (pr-str priorities))
        this-round (concat priorities (take 5 todo))
        next-round (drop 5 todo)
        missing-paths (set
                        (concat
                          (->> known-files
                               (remove fs/exists)
                               (map fs/canonical-path))
                          (->> known-files
                               (remove (partial fs/descendant? spring-isolation-dir))
                               (map fs/canonical-path))))]
    (apply update-file-cache! map-files)
    (log/info "Found" (count todo) "maps to load in" (- (u/curr-millis) before) "ms")
    (fs/make-dirs fs/maps-cache-root)
    (when (seq this-round)
      (log/info "Adding" (count this-round) "maps this iteration"))
    (doseq [map-file this-round]
      (log/info "Reading" map-file)
      (let [{:keys [map-name] :as map-data} (fs/read-map-data map-file)]
        (if map-name
          (swap! state-atom update :maps
                 (fn [maps]
                   (set (conj maps (select-keys map-data [:file :map-name])))))
          (log/warn "No map name found for" map-file))))
    (log/debug "Removing maps with no name, and" (count missing-paths) "maps with missing files")
    (swap! state-atom update :maps
           (fn [maps]
             (->> maps
                  (filter (comp fs/canonical-path :file))
                  (remove (comp string/blank? :map-name))
                  (remove (comp missing-paths fs/canonical-path :file))
                  set)))
    (swap! state-atom assoc :update-maps false)
    (if (seq next-round)
      (do
        (log/info "Scheduling map load since there are" (count next-round) "maps left to load")
        (add-task! state-atom {::task-type ::reconcile-maps}))
      (add-task! state-atom {::task-type ::update-cached-minimaps}))
    {:todo-count (count todo)}))

(defn- force-update-battle-map
  ([]
   (force-update-battle-map *state))
  ([state-atom]
   (log/info "Force updating battle map")
   (swap! *state
     (fn [state]
       (if (seq (:battle-map-details state))
         state
         (assoc state :battle-map-details nil))))
   (reconcile-maps state-atom)
   (let [{:keys [battle battles maps]} @state-atom
         battle-id (:battle-id battle)
         battle-map (-> battles (get battle-id) :battle-map)
         _ (log/debug "Force updating battle map details for" battle-map)
         filter-fn (comp #{battle-map} :map-name)
         map-details (or (some->> maps
                                  (filter filter-fn)
                                  first
                                  :file
                                  fs/read-map-data)
                         {})]
     (swap! *state assoc :battle-map-details map-details)
     map-details)))


(defn- update-channels-chimer-fn [state-atom]
  (log/info "Starting channels update chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/instant)
            (java-time/duration 5 :minutes))
          (fn [_chimestamp]
            (when-let [client (:client @state-atom)]
              (log/info "Updating channel list")
              (send-message client "CHANNELS")))
          {:error-handler
           (fn [e]
             (log/error e "Error force updating resources")
             true)})]
    (fn [] (.close chimer))))

(def app-update-url "https://api.github.com/repos/skynet-gh/skylobby/releases")
(def app-update-browseurl "https://github.com/skynet-gh/skylobby/releases")

(defn- check-app-update [state-atom]
  (let [versions
        (->> (clj-http/get app-update-url {:as :auto})
             :body
             (map :tag_name)
             (sort version/version-compare)
             reverse)
        latest-version (first versions)
        current-version (u/manifest-version)]
    (if (and latest-version current-version (not= latest-version current-version))
      (do
        (log/info "New version available:" latest-version "currently" current-version)
        (swap! state-atom assoc :app-update-available {:current current-version
                                                       :latest latest-version}))
      (log/info "No update available, or not running a jar. Latest:" latest-version "current" current-version))))

(defn- check-app-update-chimer-fn [state-atom]
  (log/info "Starting channels update chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 5 :minutes))
            (java-time/duration 1 :hours))
          (fn [_chimestamp]
            (check-app-update state-atom))
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

(defmethod task-handler ::update-cached-minimaps [_]
  (update-cached-minimaps (:maps @*state)))

(defmethod task-handler ::refresh-replays [_]
  (refresh-replays *state))

(defmethod task-handler ::refresh-replay-resources [_]
  (refresh-replay-resources *state))


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
  [{:fx/keys [^javafx.scene.input.MouseEvent event] :as e}]
  (future
    (when (= 2 (.getClickCount event))
      @(event-handler (merge e {:event/type ::join-battle})))))


(def battles-table-keys
  [:battle :battle-password :battles :client :selected-battle :users])

(defn- battles-table [{:keys [battle battle-password battles client selected-battle users]}]
  {:fx/type fx.ext.table-view/with-selection-props
   :props {:selection-mode :single
           :on-selected-item-changed {:event/type ::select-battle}}
   :desc
   {:fx/type :table-view
    :style {:-fx-font-size 15}
    :column-resize-policy :constrained ; TODO auto resize
    :items (->> battles
                vals
                (filter :battle-title)
                (sort-by (juxt (comp count :users) :battle-spectators))
                reverse)
    :row-factory
    {:fx/cell-type :table-row
     :describe (fn [i]
                 {:on-mouse-clicked
                  {:event/type ::on-mouse-clicked-battles-row
                   :battle battle
                   :battle-password battle-password
                   :client client
                   :selected-battle selected-battle
                   :battle-passworded (= "1" (-> battles (get selected-battle) :battle-passworded))}
                  :tooltip
                  {:fx/type :tooltip
                   :style {:-fx-font-size 16}
                   :show-delay [10 :ms]
                   :text (->> i
                              :users
                              keys
                              (sort String/CASE_INSENSITIVE_ORDER)
                              (string/join "\n")
                              (str "Players:\n\n"))}
                  :context-menu
                  {:fx/type :context-menu
                   :items
                   [{:fx/type :menu-item
                     :text "Join Battle"
                     :on-action {:event/type ::join-battle
                                 :client client
                                 :selected-battle (:battle-id i)}}]}})}
    :columns
    [
     {:fx/type :table-column
      :text "Game"
      :cell-value-factory :battle-modname
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [battle-modname] {:text (str battle-modname)})}}
     {:fx/type :table-column
      :text "Status"
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe
       (fn [status]
         (cond
           (or (= "1" (:battle-passworded status))
               (= "1" (:battle-locked status)))
           {:text ""
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-lock:16:yellow"}}
           (->> status :host-username (get users) :client-status :ingame)
           {:text ""
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-sword:16:red"}}
           :else
           {:text ""
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-checkbox-blank-circle-outline:16:green"}}))}}
     {:fx/type :table-column
      :text "Map"
      :cell-value-factory :battle-map
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [battle-map] {:text (str battle-map)})}}
     {:fx/type :table-column
      :text "Players (Specs)"
      :cell-value-factory (juxt (comp count :users) #(or (u/to-number (:battle-spectators %)) 0))
      :cell-factory
      {:fx/cell-type :table-cell
       :describe
       (fn [[total-user-count spec-count]]
         {:text (str (if (and (number? total-user-count) (number? spec-count))
                       (- total-user-count spec-count)
                       total-user-count)
                     " (" spec-count ")")})}}
     {:fx/type :table-column
      :text "Battle Name"
      :cell-value-factory :battle-title
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [battle-title] {:text (str battle-title)})}}
     {:fx/type :table-column
      :text "Host"
      :cell-value-factory :host-username
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [host-username] {:text (str host-username)})}}
     {:fx/type :table-column
      :text "Country"
      :cell-value-factory #(:country (get users (:host-username %)))
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [country] {:text (str country)})}}
     #_
     {:fx/type :table-column
      :text "Engine"
      :cell-value-factory #(str (:battle-engine %) " " (:battle-version %))
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [engine] {:text (str engine)})}}]}})

(defmethod event-handler ::join-direct-message
  [{:keys [username]}]
  (swap! *state
    (fn [state]
      (let [channel-name (str "@" username)]
        (-> state
            (assoc-in [:my-channels channel-name] {})
            (assoc :selected-tab-main "chat")
            (assoc :selected-tab-channel channel-name))))))

(defmethod event-handler ::direct-message
  [{:keys [client message username]}]
  (send-message client (str "SAYPRIVATE " username " " message)))

(defmethod event-handler ::on-mouse-clicked-users-row
  [{:fx/keys [^javafx.scene.input.MouseEvent event] :as e}]
  (future
    (when (< 1 (.getClickCount event))
      (when (:username e)
        (event-handler (merge e {:event/type ::join-direct-message}))))))

(def users-table-keys [:battles :client :users])

(defn- users-table [{:keys [battles client users]}]
  (let [battles-by-users (->> battles
                              vals
                              (mapcat
                                (fn [battle]
                                  (map
                                    (fn [[username _status]]
                                      [username battle])
                                    (:users battle))))
                              (into {}))]
    {:fx/type :table-view
     :style {:-fx-font-size 15}
     :column-resize-policy :constrained ; TODO auto resize
     :items (->> users
                 vals
                 (filter :username)
                 (sort-by :username String/CASE_INSENSITIVE_ORDER)
                 vec)
     :row-factory
     {:fx/cell-type :table-row
      :describe (fn [{:keys [user-id username]}]
                  (let [{:keys [battle-id battle-title] :as battle} (get battles-by-users username)]
                    (merge
                      {:on-mouse-clicked
                       {:event/type ::on-mouse-clicked-users-row
                        :username username}
                       :context-menu
                       {:fx/type :context-menu
                        :items
                        (concat
                          [{:fx/type :menu-item
                            :text "Message"
                            :on-action {:event/type ::join-direct-message
                                        :username username}}]
                          (when battle
                            [{:fx/type :menu-item
                              :text "Join Battle"
                              :on-action {:event/type ::join-battle
                                          :client client
                                          :selected-battle battle-id}}])
                          (when (= "SLDB" username)
                            [{:fx/type :menu-item
                              :text "!help"
                              :on-action {:event/type ::send-message
                                          :client client
                                          :channel-name (u/user-channel username)
                                          :message "!help"}}
                             {:fx/type :menu-item
                              :text "!ranking"
                              :on-action {:event/type ::send-message
                                          :client client
                                          :channel-name (u/user-channel username)
                                          :message "!ranking"}}])
                          [{:fx/type :menu-item
                            :text (str "User ID: " user-id)}])}}
                      (when battle
                        {:tooltip
                         {:fx/type :tooltip
                          :style {:-fx-font-size 16}
                          :show-delay [10 :ms]
                          :text (str "Battle: " battle-title)}}))))}
     :columns
     [{:fx/type :table-column
       :text "Username"
       :cell-value-factory :username
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [username]
          {:text (str username)})}}
      {:fx/type :table-column
       :sortable false
       :text "Status"
       :cell-value-factory #(select-keys (:client-status %) [:bot :access :away :ingame])
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [status]
          {:text ""
           :graphic
           {:fx/type :h-box
            :children
            (concat
              [{:fx/type font-icon/lifecycle
                :icon-literal
                (str
                  "mdi-"
                  (cond
                    (:bot status) "robot"
                    (:access status) "account-key"
                    :else "account")
                  ":16:"
                  (cond
                    (:bot status) "grey"
                    (:access status) "orange"
                    :else "white"))}]
              (when (:ingame status)
                [{:fx/type font-icon/lifecycle
                  :icon-literal "mdi-sword:16:red"}])
              (when (:away status)
                [{:fx/type font-icon/lifecycle
                  :icon-literal "mdi-sleep:16:grey"}]))}})}}
      {:fx/type :table-column
       :text "Country"
       :cell-value-factory :country
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [country] {:text (str country)})}}
      {:fx/type :table-column
       :text "Rank"
       :cell-value-factory (comp :rank :client-status)
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [rank] {:text (str rank)})}}
      {:fx/type :table-column
       :text "Lobby Client"
       :cell-value-factory :user-agent
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [user-agent] {:text (str user-agent)})}}]}))


(defmethod event-handler ::join-channel [{:keys [channel-name client]}]
  (future
    (try
      (swap! *state dissoc :join-channel-name)
      (send-message client (str "JOIN " channel-name))
      (catch Exception e
        (log/error e "Error joining channel" channel-name)))))

(defmethod event-handler ::leave-channel
  [{:keys [channel-name client] :fx/keys [^Event event]}]
  (future
    (try
      (swap! *state update :my-channels dissoc channel-name)
      (when-not (string/starts-with? channel-name "@")
        (send-message client (str "LEAVE " channel-name)))
      (catch Exception e
        (log/error e "Error leaving channel" channel-name))))
  (.consume event))

(defn- non-battle-channels
  [channels]
  (->> channels
       (remove (comp string/blank? :channel-name))
       (remove (comp u/battle-channel-name? :channel-name))))

(defn- channels-table [{:keys [channels client my-channels]}]
  {:fx/type :table-view
   :column-resize-policy :constrained ; TODO auto resize
   :items (->> (vals channels)
               non-battle-channels
               (sort-by :channel-name String/CASE_INSENSITIVE_ORDER))
   :columns
   [{:fx/type :table-column
     :text "Channel"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:channel-name i))})}}
    {:fx/type :table-column
     :text "User Count"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe (fn [i] {:text (str (:user-count i))})}}
    {:fx/type :table-column
     :text "Actions"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [{:keys [channel-name]}]
        {:text ""
         :graphic
         (merge
           {:fx/type :button}
           (if (contains? my-channels channel-name)
             {:text "Leave"
              :on-action {:event/type ::leave-channel
                          :channel-name channel-name
                          :client client}}
             {:text "Join"
              :on-action {:event/type ::join-channel
                          :channel-name channel-name
                          :client client}}))})}}]})

(defn- update-disconnected! [state-atom]
  (log/info "Updating state after disconnect")
  (let [[{:keys [client ping-loop print-loop]} _new-state]
        (swap-vals! state-atom
          (fn [state]
            (-> state
                (dissoc :accepted
                        :battle :battles
                        :client :client-deferred :compflags :last-failed-message
                        :ping-loop :print-loop :users)
                (update :my-channels
                  (fn [my-channels]
                    (->> my-channels
                         (remove (comp u/battle-channel-name? first))
                         (into {})))))))]
    (when client
      (client/disconnect client))
    (when ping-loop
      (future-cancel ping-loop))
    (when print-loop
      (future-cancel print-loop)))
  nil)

(defmethod event-handler ::print-state [_e]
  (pprint *state))


(defmethod event-handler ::disconnect [_e]
  (update-disconnected! *state))

(defn- connect [state-atom client-deferred]
  (future
    (try
      (let [^SplicedStream client @client-deferred]
        (s/on-closed client
          (fn []
            (log/info "client closed")
            (update-disconnected! *state)))
        (s/on-drained client
          (fn []
            (log/info "client drained")
            (update-disconnected! *state)))
        (if (s/closed? client)
          (log/warn "client was closed on create")
          (do
            (swap! state-atom assoc :client client :login-error nil)
            (client/connect state-atom client))))
      (catch Exception e
        (log/error e "Connect error")
        (swap! state-atom assoc :login-error (str (.getMessage e)))
        (update-disconnected! *state)))
    nil))

(defmethod event-handler ::connect [{:keys [server-url]}]
  (future
    (try
      (let [client-deferred (client/client server-url)] ; TODO catch connect errors somehow
        (swap! *state assoc :client-deferred client-deferred)
        (connect *state client-deferred))
      (catch Exception e
        (log/error e "Error connecting")))))

(defmethod event-handler ::cancel-connect [{:keys [client client-deferred]}]
  (future
    (try
      (if client-deferred
        (deferred/error! client-deferred (ex-info "User cancel connect" {}))
        (log/warn "No client-deferred to cancel"))
      (when client
        (log/warn "client found during cancel")
        (s/close! client))
      (catch Exception e
        (log/error e "Error cancelling connect")))))


(defn- server-cell
  [[server-url server-data]]
  (let [server-alias (:alias server-data)]
    {:text (if server-alias
             (str server-alias " (" server-url ")")
             (str server-url))}))

(defn- server-combo-box [{:keys [disable on-value-changed server servers]}]
  {:fx/type :combo-box
   :disable (boolean disable)
   :value server
   :items (or (->> servers
                   (sort-by (juxt (comp :alias second) first)))
              [])
   :prompt-text "< choose a server >"
   :button-cell server-cell
   :on-value-changed on-value-changed
   :cell-factory
   {:fx/cell-type :list-cell
    :describe server-cell}})


(defmethod event-handler ::toggle
  [{:fx/keys [event] :as e}]
  (let [v (boolean (or (:value e) event))
        inv (not v)]
    (swap! *state assoc (:key e) inv)
    (future
      (async/<!! (async/timeout 10))
      (swap! *state assoc (:key e) v))))

(defmethod event-handler ::on-change-server
  [{:fx/keys [event]}]
  (swap! *state
    (fn [state]
      (let [server-url (first event)
            {:keys [username password]} (-> state :logins (get server-url))]
        (-> state
            (assoc :server event)
            (assoc :username username)
            (assoc :password password))))))

(def client-buttons-keys
  [:accepted :app-update-available :client :client-deferred :username :password :login-error :server-url :servers :server
   :show-register-popup])

(defn- client-buttons
  [{:keys [accepted app-update-available client client-deferred username password login-error server servers]}]
  {:fx/type :h-box
   :alignment :center-left
   :style {:-fx-font-size 16}
   :children
   (concat
     [{:fx/type :button
       :text (if client
               (if accepted
                 "Disconnect"
                 "Logging in...")
               (if client-deferred
                 "Connecting..."
                 "Connect"))
       :disable (boolean
                  (or
                    (and client (not accepted))
                    (and
                      server
                      (not client)
                      client-deferred)))
       :on-action (assoc {:event/type (if client ::disconnect ::connect)}
                         :server-url (first server))}]
     (when (and client-deferred (not client))
       [{:fx/type :button
         :text ""
         :tooltip
         {:fx/type :tooltip
          :show-delay [10 :ms]
          :style {:-fx-font-size 14}
          :text "Cancel connect"}
         :on-action {:event/type ::cancel-connect
                     :client-deferred client-deferred
                     :client client}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal "mdi-close-octagon:16:white"}}])
     [
      {:fx/type :label
       :alignment :center
       :text " Server: "}
      (assoc
        {:fx/type server-combo-box}
        :disable (or client client-deferred)
        :server server
        :servers servers
        :on-value-changed {:event/type ::on-change-server})
      {:fx/type :button
       :text ""
       :tooltip
       {:fx/type :tooltip
        :show-delay [10 :ms]
        :style {:-fx-font-size 14}
        :text "Show servers window"}
       :on-action {:event/type ::toggle
                   :key :show-servers-window}
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-plus:16:white"}}
      {:fx/type :label
       :alignment :center
       :text " Login: "}
      {:fx/type :text-field
       :text username
       :prompt-text "Username"
       :disable (boolean (or client client-deferred (not server)))
       :on-text-changed {:event/type ::username-change
                         :server-url (first server)}}]
     (when-not (or client client-deferred)
       [{:fx/type :password-field
         :text password
         :disable (boolean (not server))
         :prompt-text "Password"
         :style {:-fx-pref-width 300}
         :on-text-changed {:event/type ::password-change
                           :server-url (first server)}}])
     [{:fx/type :button
       :text "Register"
       :disable (boolean (or client client-deferred))
       :tooltip
       {:fx/type :tooltip
        :show-delay [10 :ms]
        :style {:-fx-font-size 14}
        :text "Show server registration window"}
       :on-action {:event/type ::toggle
                   :key :show-register-window}
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-account-plus:16:white"}}
      {:fx/type :v-box
       :h-box/hgrow :always
       :alignment :center
       :children
       [{:fx/type :label
         :text (str login-error)
         :style {:-fx-text-fill "#FF0000"
                 :-fx-max-width "360px"}}]}
      {:fx/type :pane
       :h-box/hgrow :always}]
     (when-let [{:keys [latest]} app-update-available]
       (let [color "gold"]
         [{:fx/type :button
           :text (str "Update to " latest)
           :on-action {:event/type ::desktop-browse-url
                       :url app-update-browseurl}
           :style {:-fx-base color
                   :-fx-background color}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-open-in-new:16:black"}}
          {:fx/type :button
           :text ""
           :on-action {:event/type ::dissoc
                       :key :app-update-available}
           :style {:-fx-base color
                   :-fx-background color}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-close:16:black"}}]))
     [{:fx/type :button
       :text "Settings"
       :on-action {:event/type ::toggle
                   :key :show-settings-window}
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-settings:16:white"}}
      {:fx/type :button
       :text "Replays"
       :on-action {:event/type ::toggle
                   :key :show-replays}
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-open-in-new:16:white"}}])})


(defmethod event-handler ::register [{:keys [email password server username]}]
  (future
    (try
      (let [server-url (first server)
            client-deferred (client/client server-url) ; TODO catch connect errors somehow
            client @client-deferred]
        (swap! *state dissoc :password-confirm)
        (send-message client
          (str "REGISTER " username " " (client/base64-md5 password) " " email))
        (loop []
          (when-let [d (s/take! client)]
            (when-let [m @d]
              (log/info "(register) <" (str "'" m "'"))
              (swap! *state assoc :register-response m)
              (when-not (Thread/interrupted)
                (recur)))))
        (s/close! client))
      (catch Exception e
        (log/error e "Error registering with" server "as" username)))))

(defmethod event-handler ::confirm-agreement [{:keys [client password username verification-code]}]
  (future
    (try
      (send-message client
        (str "CONFIRMAGREEMENT " verification-code))
      (swap! *state dissoc :agreement :verification-code)
      (client/login client username password)
      (catch Exception e
        (log/error e "Error confirming agreement")))))

(defmethod event-handler ::edit-server
  [{:keys [server-data]}]
  (swap! *state assoc
         :server-host (:host server-data)
         :server-port (:port server-data)
         :server-alias (:alias server-data)
         :server-ssl (:ssl server-data)))

(defmethod event-handler ::update-server
  [{:keys [server-url server-data]}]
  (swap! *state update-in [:servers server-url] merge server-data))


(defmethod event-handler ::delete-extra-import-source [{:keys [file]}]
  (swap! *state update :extra-import-sources
    (fn [extra-import-sources]
      (remove (comp #{(fs/canonical-path file)} fs/canonical-path :file) extra-import-sources))))

(defmethod event-handler ::delete-extra-replay-source [{:keys [file]}]
  (swap! *state update :extra-replay-sources
    (fn [extra-replay-sources]
      (remove (comp #{(fs/canonical-path file)} fs/canonical-path :file) extra-replay-sources))))


(def servers-window-keys
  [:server-alias :server-edit :server-host :server-port :servers :show-servers-window])

(defn- servers-window
  [{:keys [server-alias server-edit server-host server-port server-ssl servers show-servers-window]}]
  (let [url (first server-edit)
        port (if (string/blank? (str server-port))
               default-server-port (str server-port))
        server-url (str server-host ":" port)]
    {:fx/type :stage
     :showing (boolean show-servers-window)
     :title (str u/app-name " Servers")
     :icons icons
     :on-close-request (fn [^javafx.stage.WindowEvent e]
                         (swap! *state assoc :show-servers-window false)
                         (.consume e))
     :width 560
     :height 320
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      (if show-servers-window
        {:fx/type :v-box
         :style {:-fx-font-size 16}
         :children
         [{:fx/type :h-box
           :alignment :center-left
           :children
           (concat
             [{:fx/type :label
               :alignment :center
               :text " Servers: "}
              (assoc
                {:fx/type server-combo-box}
                :server server-edit
                :servers servers
                :on-value-changed {:event/type ::assoc
                                   :key :server-edit})]
             (when server-edit
               [{:fx/type :button
                 :alignment :center
                 :on-action {:event/type ::edit-server
                             :server-data (second server-edit)}
                 :text ""
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-pencil:16:white"}}
                {:fx/type :button
                 :alignment :center
                 :on-action {:event/type ::dissoc-in
                             :path [:servers url]}
                 :text ""
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-delete:16:white"}}]))}
          {:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :label
           :text "New server:"}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Host: "}
            {:fx/type :text-field
             :h-box/hgrow :always
             :text server-host
             :on-text-changed {:event/type ::assoc
                               :key :server-host}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Port: "}
            {:fx/type :text-field
             :text (str server-port)
             :prompt-text "8200"
             :on-text-changed {:event/type ::assoc
                               :key :server-port}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " Alias: "}
            {:fx/type :text-field
             :h-box/hgrow :always
             :text server-alias
             :on-text-changed {:event/type ::assoc
                               :key :server-alias}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :alignment :center
             :text " SSL: "}
            {:fx/type :check-box
             :h-box/hgrow :always
             :selected (boolean server-ssl)
             :on-selected-changed {:event/type ::assoc
                                   :key :server-ssl}}]}
          {:fx/type :button
           :text (str
                   (if (contains? servers server-url) "Update" "Add")
                   " server")
           :style {:-fx-font-size 20}
           :disable (string/blank? server-host)
           :on-action {:event/type ::update-server
                       :server-url server-url
                       :server-data
                       {:port port
                        :host server-host
                        :alias server-alias
                        :ssl (boolean server-ssl)}}}]}
        {:fx/type :pane})}}))


(def register-window-keys
  [:email :password :password-confirm :register-response :server :servers :show-register-window
   :username])

(defn- register-window
  [{:keys [email password password-confirm register-response server servers show-register-window username]}]
  {:fx/type :stage
   :showing (boolean show-register-window)
   :title (str u/app-name " Register")
   :icons icons
   :on-close-request (fn [^javafx.stage.WindowEvent e]
                       (swap! *state assoc :show-register-window false)
                       (.consume e))
   :width 500
   :height 400
   :scene
   {:fx/type :scene
    :stylesheets stylesheets
    :root
    (if show-register-window
      {:fx/type :v-box
       :style {:-fx-font-size 16}
       :children
       [
        (assoc
          {:fx/type server-combo-box}
          :server server
          :servers servers
          :on-value-changed {:event/type ::on-change-server})
        {:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Username: "}
          {:fx/type :text-field
           :text username
           :on-text-changed {:event/type ::username-change
                             :server-url (first server)}}]}
        {:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Password: "}
          {:fx/type :password-field
           :text password
           :on-text-changed {:event/type ::password-change
                             :server-url (first server)}}]}
        {:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Confirm: "}
          {:fx/type :password-field
           :text password-confirm
           :on-text-changed {:event/type ::assoc
                             :key :password-confirm}}]}
        {:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text " Email: "}
          {:fx/type :text-field
           :text email
           :on-text-changed {:event/type ::assoc
                             :key :email}}]}
        {:fx/type :pane
         :v-box/vgrow :always}
        {:fx/type :label
         :text (str register-response)}
        {:fx/type :label
         :style {:-fx-text-fill "red"}
         :text (str (when (and (not (string/blank? password))
                               (not (string/blank? password-confirm))
                               (not= password password-confirm))
                      "Passwords do not match"))}
        {:fx/type :button
         :text "Register"
         :style {:-fx-font-size 20}
         :tooltip
         {:fx/type :tooltip
          :show-delay [10 :ms]
          :text "Register with server"}
         :disable (not (and server
                            username
                            password
                            password-confirm
                            (= password password-confirm)))
         :on-action {:event/type ::register
                     :server server
                     :username username
                     :password password
                     :email email}}]}
      {:fx/type :pane})}})

(defmethod event-handler ::conj
  [event]
  (swap! *state update (:key event) conj (:value event)))

(defmethod event-handler ::add-extra-import-source
  [{:keys [extra-import-name extra-import-path]}]
  (swap! *state
    (fn [state]
      (-> state
          (update :extra-import-sources conj {:import-source-name extra-import-name
                                              :file (io/file extra-import-path)})
          (dissoc :extra-import-name :extra-import-path)))))

(defmethod event-handler ::add-extra-replay-source
  [{:keys [extra-replay-name extra-replay-path extra-replay-recursive]}]
  (swap! *state
    (fn [state]
      (-> state
          (update :extra-replay-sources conj {:replay-source-name extra-replay-name
                                              :file (io/file extra-replay-path)
                                              :recursive extra-replay-recursive})
          (dissoc :extra-replay-name :extra-replay-path :extra-replay-recursive)))))

(defmethod event-handler ::save-spring-isolation-dir [_e]
  (future
    (try
      (swap! *state
        (fn [{:keys [spring-isolation-dir-draft] :as state}]
          (let [f (io/file spring-isolation-dir-draft)
                isolation-dir (if (fs/exists? f)
                                f
                                (fs/default-isolation-dir))]
            (-> state
                (assoc :spring-isolation-dir isolation-dir)
                (dissoc :spring-isolation-dir-draft)))))
      (catch Exception e
        (log/error e "Error setting spring isolation dir")
        (swap! *state dissoc :spring-isolation-dir-draft)))))

(def settings-window-keys
  [:extra-import-name :extra-import-path :extra-import-sources :extra-replay-name :extra-replay-path
   :extra-replay-recursive
   :extra-replay-sources :show-settings-window :spring-isolation-dir :spring-isolation-dir-draft])

(defn settings-window
  [{:keys [extra-import-name extra-import-path extra-import-sources extra-replay-name
           extra-replay-path extra-replay-recursive show-settings-window spring-isolation-dir
           spring-isolation-dir-draft]
    :as state}]
  {:fx/type :stage
   :showing (boolean show-settings-window)
   :title (str u/app-name " Settings")
   :icons icons
   :on-close-request (fn [^javafx.stage.WindowEvent e]
                       (swap! *state assoc :show-settings-window false)
                       (.consume e))
   :width 800
   :height 800
   :scene
   {:fx/type :scene
    :stylesheets stylesheets
    :root
    (if show-settings-window
      {:fx/type :scroll-pane
       :fit-to-width true
       :content
       {:fx/type :v-box
        :style {:-fx-font-size 16}
        :children
        [
         {:fx/type :label
          :text " Spring Isolation Dir"
          :style {:-fx-font-size 24}}
         {:fx/type :label
          :text (str (fs/canonical-path spring-isolation-dir))}
         {:fx/type :h-box
          :alignment :center-left
          :children
          [{:fx/type :button
            :on-action {:event/type ::save-spring-isolation-dir}
            :disable (string/blank? spring-isolation-dir-draft)
            :text ""
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-content-save:16:white"}}
           {:fx/type :text-field
            :text (str spring-isolation-dir-draft)
            :style {:-fx-min-width 600}
            :on-text-changed {:event/type ::assoc
                              :key :spring-isolation-dir-draft}}]}
         {:fx/type :h-box
          :alignment :center-left
          :children
          [{:fx/type :button
            :on-action {:event/type ::assoc
                        :key :spring-isolation-dir
                        :value (fs/default-isolation-dir)}
            :text "Default"}
           {:fx/type :button
            :on-action {:event/type ::assoc
                        :key :spring-isolation-dir
                        :value (fs/bar-root)}
            :text "Beyond All Reason"}
           {:fx/type :button
            :on-action {:event/type ::assoc
                        :key :spring-isolation-dir
                        :value (fs/spring-root)}
            :text "Spring"}]}
         {:fx/type :label
          :text " Import Sources"
          :style {:-fx-font-size 24}}
         {:fx/type :v-box
          :children
          (map
            (fn [{:keys [builtin file import-source-name]}]
              {:fx/type :h-box
               :alignment :center-left
               :children
               [{:fx/type :button
                 :on-action {:event/type ::delete-extra-import-source
                             :file file}
                 :disable (boolean builtin)
                 :text ""
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-delete:16:white"}}
                {:fx/type :v-box
                 :children
                 [{:fx/type :label
                   :text (str " " import-source-name)}
                  {:fx/type :label
                   :text (str " " (fs/canonical-path file))
                   :style {:-fx-font-size 14}}]}]})
            (import-sources extra-import-sources))}
         {:fx/type :h-box
          :alignment :center-left
          :children
          [{:fx/type :button
            :text ""
            :disable (or (string/blank? extra-import-name)
                         (string/blank? extra-import-path))
            :on-action {:event/type ::add-extra-import-source
                        :extra-import-path extra-import-path
                        :extra-import-name extra-import-name}
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-plus:16:white"}}
           {:fx/type :label
            :text " Name: "}
           {:fx/type :text-field
            :text (str extra-import-name)
            :on-text-changed {:event/type ::assoc
                              :key :extra-import-name}}
           {:fx/type :label
            :text " Path: "}
           {:fx/type :text-field
            :text (str extra-import-path)
            :on-text-changed {:event/type ::assoc
                              :key :extra-import-path}}]}
         {:fx/type :label
          :text " Replay Sources"
          :style {:-fx-font-size 24}}
         {:fx/type :v-box
          :children
          (map
            (fn [{:keys [builtin file recursive replay-source-name]}]
              {:fx/type :h-box
               :alignment :center-left
               :children
               [{:fx/type :button
                 :on-action {:event/type ::delete-extra-replay-source
                             :file file}
                 :disable (boolean builtin)
                 :text ""
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-delete:16:white"}}
                {:fx/type :v-box
                 :children
                 [{:fx/type :h-box
                   :children
                   (concat
                     [{:fx/type :label
                       :text (str " " replay-source-name)
                       :style {:-fx-font-size 18}}]
                     (when recursive
                       [{:fx/type :label
                         :text " (recursive)"
                         :style {:-fx-text-fill :red}}]))}
                  {:fx/type :label
                   :text (str " " (fs/canonical-path file))
                   :style {:-fx-font-size 14}}]}]})
            (replay-sources state))}
         {:fx/type :h-box
          :alignment :center-left
          :children
          [
           {:fx/type :button
            :disable (or (string/blank? extra-replay-name)
                         (string/blank? extra-replay-path))
            :on-action {:event/type ::add-extra-replay-source
                        :extra-replay-path extra-replay-path
                        :extra-replay-name extra-replay-name
                        :extra-replay-recursive extra-replay-recursive}
            :text ""
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-plus:16:white"}}
           {:fx/type :label
            :text " Name: "}
           {:fx/type :text-field
            :text (str extra-replay-name)
            :on-text-changed {:event/type ::assoc
                              :key :extra-replay-name}}
           {:fx/type :label
            :text " Path: "}
           {:fx/type :text-field
            :text (str extra-replay-path)
            :on-text-changed {:event/type ::assoc
                              :key :extra-replay-path}}
           {:fx/type :label
            :text " Recursive: "}
           {:fx/type :check-box
            :selected (boolean extra-replay-recursive)
            :on-selected-changed {:event/type ::assoc
                                  :key :extra-replay-recursive}}]}]}}
     {:fx/type :pane})}})

(def bind-keycodes
  {"CTRL" KeyCode/CONTROL
   "ESC" KeyCode/ESCAPE
   "BACKSPACE" KeyCode/BACK_SPACE
   "." KeyCode/PERIOD
   "," KeyCode/COMMA
   "+" KeyCode/PLUS
   "-" KeyCode/MINUS
   "=" KeyCode/EQUALS
   "_" KeyCode/UNDERSCORE
   ";" KeyCode/SEMICOLON
   "^" KeyCode/CIRCUMFLEX
   "`" KeyCode/BACK_QUOTE
   "[" KeyCode/OPEN_BRACKET
   "]" KeyCode/CLOSE_BRACKET
   "/" KeyCode/SLASH
   "\\" KeyCode/BACK_SLASH
   "PAGEUP" KeyCode/PAGE_UP
   "PAGEDOWN" KeyCode/PAGE_DOWN})

(defn- bind-key-to-javafx-keycode [bind-key-piece]
  (or (KeyCode/getKeyCode bind-key-piece)
      (get bind-keycodes bind-key-piece)
      (try (KeyCode/valueOf bind-key-piece)
           (catch Exception e
             (log/trace e "Error getting KeyCode for" bind-key-piece)))))

(def keycode-binds
  (clojure.set/map-invert bind-keycodes))

(defn- key-event-to-uikeys-bind [^KeyEvent key-event]
  (str
    (when (.isAltDown key-event)
      "alt,")
    (when (.isControlDown key-event)
      "ctrl,")
    (when (.isShiftDown key-event)
      "shift,")
    (or (get keycode-binds (.getCode key-event))
        (.getText key-event))))

(defmethod event-handler ::uikeys-pressed [{:fx/keys [^KeyEvent event] :keys [selected-uikeys-action]}]
  (if (.isModifierKey (.getCode event))
    (log/debug "Ignoring modifier key event for uikeys")
    (do
      (log/info event)
      (swap! *state assoc-in [:uikeys selected-uikeys-action] (key-event-to-uikeys-bind event)))))

(defmethod event-handler ::uikeys-select
  [{:fx/keys [event]}]
  (swap! *state assoc :selected-uikeys-action (:bind-action event)))

(defn- uikeys-window [{:keys [filter-uikeys-action selected-uikeys-action show-uikeys-window uikeys]}]
  (let [default-uikeys (or (u/try-log "parse uikeys" (uikeys/parse-uikeys))
                           [])
        filtered-uikeys (->>  default-uikeys
                              (filter
                                (fn [{:keys [bind-action]}]
                                  (if (string/blank? filter-uikeys-action)
                                    true
                                    (string/includes?
                                      (string/lower-case bind-action)
                                      (string/lower-case filter-uikeys-action)))))
                              (sort-by :bind-key))
        uikeys-overrides (or uikeys {})]
    {:fx/type :stage
     :showing (boolean show-uikeys-window)
     :title (str u/app-name " UI Keys Editor")
     :icons icons
     :on-close-request (fn [^Event e]
                         (swap! *state assoc :show-uikeys-window false)
                         (.consume e))
     :width 1200
     :height 1000
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      (if show-uikeys-window
        {:fx/type :v-box
         :style {:-fx-font-size 14}
         :children
         (if show-uikeys-window
           [{:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type :label
               :text " Filter action: "}
              {:fx/type :text-field
               :text (str filter-uikeys-action)
               :prompt-text "filter"
               :on-text-changed {:event/type ::assoc
                                 :key :filter-uikeys-action}}]}
            {:fx/type fx.ext.table-view/with-selection-props
             :v-box/vgrow :always
             :props
             {:selection-mode :single
              :on-selected-item-changed
              {:event/type ::uikeys-select}}
             :desc
             {:fx/type :table-view
              :column-resize-policy :constrained
              :items (or (seq filtered-uikeys) [])
              :on-key-pressed {:event/type ::uikeys-pressed
                               :selected-uikeys-action selected-uikeys-action}
              :columns
              [
               {:fx/type :table-column
                :text "Action"
                :cell-value-factory identity
                :cell-factory
                {:fx/cell-type :table-cell
                 :describe
                 (fn [i]
                   {:text (str (:bind-action i))})}}
               {:fx/type :table-column
                :text "Bind"
                :cell-value-factory identity
                :cell-factory
                {:fx/cell-type :table-cell
                 :describe
                 (fn [i]
                   {:text (pr-str (:bind-key i))})}}
               {:fx/type :table-column
                :text "Parsed"
                :cell-value-factory identity
                :cell-factory
                {:fx/cell-type :table-cell
                 :describe
                 (fn [i]
                   {:text (pr-str (uikeys/parse-bind-keys (:bind-key i)))})}}
               {:fx/type :table-column
                :text "JavaFX KeyCode"
                :cell-value-factory identity
                :cell-factory
                {:fx/cell-type :table-cell
                 :describe
                 (fn [i]
                   (let [bind-key-uc (string/upper-case (:bind-key i))
                         parsed (uikeys/parse-bind-keys bind-key-uc)
                         key-codes (map
                                     (partial map (comp #(when % (str %)) bind-key-to-javafx-keycode))
                                     parsed)]
                     {:text (pr-str key-codes)}))}}
               {:fx/type :table-column
                :text "Comment"
                :cell-value-factory identity
                :cell-factory
                {:fx/cell-type :table-cell
                 :describe
                 (fn [{:keys [bind-comment]}]
                   (merge
                     {:text (str bind-comment)}
                     (when bind-comment
                       {:tooltip
                        {:fx/type :tooltip
                         :show-delay [10 :ms]
                         :style {:-fx-font-size 15}
                         :text (str bind-comment)}})))}}
               {:fx/type :table-column
                :text "Override"
                :cell-value-factory identity
                :cell-factory
                {:fx/cell-type :table-cell
                 :describe
                 (fn [i]
                   {:text (pr-str (get uikeys-overrides (:bind-action i)))})}}]}}]
           {:fx/type :label
            :text "window hidden"})}
        {:fx/type :pane})}}))


(defmethod event-handler ::username-change
  [{:fx/keys [event] :keys [server-url]}]
  (swap! *state
    (fn [state]
      (-> state
          (assoc :username event)
          (assoc-in [:logins server-url :username] event)))))

(defmethod event-handler ::password-change
  [{:fx/keys [event] :keys [server-url]}]
  (swap! *state
    (fn [state]
      (-> state
          (assoc :password event)
          (assoc-in [:logins server-url :password] event)))))

(defmethod event-handler ::server-url-change
  [{:fx/keys [event]}]
  (swap! *state assoc :server-url event))


(defn- open-battle
  [client {:keys [battle-type nat-type battle-password host-port max-players mod-hash rank map-hash
                  engine engine-version map-name title mod-name]
           :or {battle-type 0
                nat-type 0
                battle-password "*"
                host-port 8452
                max-players 8
                rank 0
                engine "Spring"}}]
  (let [password (if (string/blank? battle-password) "*" battle-password)]
    (send-message client
      (str "OPENBATTLE " battle-type " " nat-type " " password " " host-port " " max-players
           " " mod-hash " " rank " " map-hash " " engine "\t" engine-version "\t" map-name "\t" title
           "\t" mod-name))))


(defmethod event-handler ::host-battle
  [{:keys [client scripttags host-battle-state use-springlobby-modname]}]
  (swap! *state assoc
         :battle {}
         :battle-map-details nil
         :battle-mod-details nil)
  (let [{:keys [engine-version map-name mod-name]} host-battle-state]
    (when-not (or (string/blank? engine-version)
                  (string/blank? mod-name)
                  (string/blank? map-name))
      (future
        (try
          (let [mod-name-parsed (parse-mod-name-git mod-name)
                [_ mod-prefix _git] mod-name-parsed
                adjusted-modname (if (and use-springlobby-modname mod-name-parsed)
                                     (str mod-prefix " $VERSION")
                                     mod-name)]
            (open-battle client (assoc host-battle-state :mod-name adjusted-modname)))
          (when (seq scripttags)
            (send-message client (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags))))
          (catch Exception e
            (log/error e "Error opening battle")))))))


(defmethod event-handler ::leave-battle [{:keys [client]}]
  (future
    (try
      (send-message client "LEAVEBATTLE")
      (swap! *state dissoc :battle)
      (catch Exception e
        (log/error e "Error leaving battle")))))


(defmethod event-handler ::join-battle [{:keys [battle-password battle-passworded client selected-battle] :as e}]
  (future
    (try
      (if selected-battle
        (do
          @(event-handler (merge e {:event/type ::leave-battle}))
          (async/<!! (async/timeout 500))
          (send-message client
            (str "JOINBATTLE " selected-battle
                 (if battle-passworded
                   (str " " battle-password)
                   (str " *"))
                 " " (crypto.random/hex 6))))
        (log/warn "No battle to join" e))
      (catch Exception e
        (log/error e "Error joining battle")))))

(defmethod event-handler ::start-singleplayer-battle
  [e]
  (future
    (try
      @(event-handler (merge e {:event/type ::leave-battle}))
      (async/<!! (async/timeout 500))
      (swap! *state
             (fn [{:keys [engine-version map-name mod-name username] :as state}]
               (-> state
                   (assoc-in [:battles :singleplayer] {:battle-version engine-version
                                                       :battle-map map-name
                                                       :battle-modname mod-name
                                                       :host-username username})
                   (assoc :battle {:battle-id :singleplayer ; TODO dedupe
                                   :scripttags {:game {:startpostype 0}}
                                   :users {username {:battle-status handler/default-battle-status}}}))))
      (catch Exception e
        (log/error e "Error joining battle")))))

(defn- update-filter-fn [^javafx.scene.input.KeyEvent event]
  (fn [x]
    (if (= KeyCode/BACK_SPACE (.getCode event))
      (apply str (drop-last x))
      (str x (.getText event)))))

(defmethod event-handler ::maps-key-pressed [{:fx/keys [event]}]
  (swap! *state update :map-input-prefix (update-filter-fn event)))

(defmethod event-handler ::maps-hidden [_e]
  (swap! *state dissoc :map-input-prefix))

(defmethod event-handler ::show-maps-window
  [{:keys [on-change-map]}]
  (let [v true
        inv (not v)]
    (swap! *state assoc
           :show-maps inv
           :on-change-map on-change-map)
    (future
      (Thread/sleep 100)
      (swap! *state assoc :show-maps v))))


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


(defmethod event-handler ::random-map [{:keys [maps on-value-changed]}]
  (event-handler
    (let [random-map-name (:map-name (rand-nth (seq maps)))]
      (assoc on-value-changed :fx/event random-map-name))))


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
  (future
    (try
      (if (fs/wsl-or-windows?)
        (let [runtime (Runtime/getRuntime) ; TODO hacky?
              command ["explorer.exe" (fs/wslpath file)]
              ^"[Ljava.lang.String;" cmdarray (into-array String command)]
          (log/info "Running" (pr-str command))
          (.exec runtime cmdarray nil nil))
        (let [desktop (Desktop/getDesktop)]
          (.browseFileDirectory desktop file)))
      (catch Exception e
        (log/error e "Error browsing file" file)))))

(defmethod event-handler ::desktop-browse-url
  [{:keys [url]}]
  (future
    (try
      (let [desktop (Desktop/getDesktop)]
        (if (.isSupported desktop Desktop$Action/BROWSE)
          (.browse desktop (java.net.URI. url))
          (when (fs/linux?)
            (when (fs/linux?)
              (let [runtime (Runtime/getRuntime)
                    command ["xdg-open" url] ; https://stackoverflow.com/a/5116553/984393
                    ^"[Ljava.lang.String;" cmdarray (into-array String command)]
                (log/info "Running" (pr-str command))
                (.exec runtime cmdarray nil nil))))))
      (catch Exception e
        (log/error e "Error browsing url" url)))))



(defmethod event-handler ::battle-password-change
  [{:fx/keys [event]}]
  (swap! *state assoc :battle-password event))

(defmethod event-handler ::battle-title-change
  [{:fx/keys [event]}]
  (swap! *state assoc :battle-title event))

(defmethod event-handler ::use-springlobby-modname-change
  [{:fx/keys [event]}]
  (swap! *state assoc :use-springlobby-modname event))


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

(defmethod event-handler ::map-window-action
  [{:keys [on-change-map]}]
  (when on-change-map
    (event-handler on-change-map))
  (swap! *state assoc :show-maps false))

(defmethod event-handler ::battle-map-change
  [{:fx/keys [event] :keys [client map-name]}]
  (future
    (try
      (let [spectator-count 0 ; TODO
            locked 0
            map-hash -1 ; TODO
            map-name (or map-name event)
            m (str "UPDATEBATTLEINFO " spectator-count " " locked " " map-hash " " map-name)]
        (send-message client m))
        ;(swap! *state assoc :battle-map-details nil))
      (catch Exception e
        (log/error e "Error changing battle map")))))

(defmethod event-handler ::suggest-battle-map
  [{:fx/keys [event] :keys [battle-status channel-name client map-name]}]
  (future
    (try
      (cond
        (string/blank? channel-name) (log/warn "No channel to suggest battle map")
        (not (:mode battle-status)) (log/info "Cannot suggest battle map as spectator")
        :else
        (let [map-name (or map-name event)]
          (send-message client (str "SAY " channel-name " !map " map-name))))
      (catch Exception e
        (log/error e "Error suggesting map")))))

(defmethod event-handler ::kick-battle
  [{:keys [bot-name client singleplayer username]}]
  (future
    (try
      (if singleplayer
        (do
          (log/info "Singleplayer battle kick")
          (swap! *state
                 (fn [state]
                   (-> state
                       (update-in [:battles :singleplayer :bots] dissoc bot-name)
                       (update-in [:battle :bots] dissoc bot-name)
                       (update-in [:battles :singleplayer :users] dissoc username)
                       (update-in [:battle :users] dissoc username)))))
        (if bot-name
          (send-message client (str "REMOVEBOT " bot-name))
          (send-message client (str "KICKFROMBATTLE " username))))
      (catch Exception e
        (log/error e "Error kicking from battle")))))


(defn available-name [existing-names desired-name]
  (if-not (contains? (set existing-names) desired-name)
    desired-name
    (recur
      existing-names
      (if-let [[_ prefix n suffix] (re-find #"(.*)(\d+)(.*)" desired-name)]
        (let [nn (inc (u/to-number n))]
          (str prefix nn suffix))
        (str desired-name 0)))))

(defmethod event-handler ::add-bot [{:keys [battle bot-username bot-name bot-version client singleplayer username]}]
  (future
    (try
      (let [existing-bots (keys (:bots battle))
            bot-username (available-name existing-bots bot-username)
            status (assoc client/default-battle-status
                          :ready true
                          :mode 1
                          :sync 1
                          :id (battle/available-team-id battle)
                          :ally (battle/available-ally battle)
                          :side (rand-nth [0 1]))
            bot-status (client/encode-battle-status status)
            bot-color (u/random-color)
            message (str "ADDBOT " bot-username " " bot-status " " bot-color " " bot-name "|" bot-version)]
        (if singleplayer
          (do
            (log/info "Singleplayer add bot")
            (swap! *state
                   (fn [state]
                     (let [bot-data {:bot-name bot-username
                                     :ai-name bot-name
                                     :ai-version bot-version
                                     :team-color bot-color
                                     :battle-status status
                                     :owner username}]
                       (-> state
                           (assoc-in [:battles :singleplayer :bots bot-username] bot-data)
                           (assoc-in [:battle :bots bot-username] bot-data))))))
          (send-message client message)))
      (catch Exception e
        (log/error e "Error adding bot")))))

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


(defmethod event-handler ::start-battle
  [{:keys [am-host am-spec battle-status channel-name client host-ingame]}]
  (future
    (try
      (when-not (:mode battle-status)
        (send-message client (str "SAY " channel-name " !joinas spec"))
        (async/<!! (async/timeout 1000)))
      (if (or am-host am-spec host-ingame)
        (spring/start-game @*state) ; TODO remove deref
        (send-message client (str "SAY " channel-name " !cv start")))
      (catch Exception e
        (log/error e "Error starting battle")))))


(defn- spring-script-color-to-int [rgbcolor]
  (let [[r g b] (string/split rgbcolor #"\s")
        color (colors/create-color
                :r (int (* 255 (Double/parseDouble b)))
                :g (int (* 255 (Double/parseDouble g)))
                :b (int (* 255 (Double/parseDouble r))))]
    (colors/rgba-int color)))


(defmethod event-handler ::minimap-mouse-pressed
  [{:fx/keys [^javafx.scene.input.MouseEvent event]
    :keys [start-boxes starting-points startpostype]}]
  (future
    (try
      (cond
        (= startpostype "Choose before game")
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
                                            :y (- ey start-pos-r)})))
        (= startpostype "Choose in game")
        (let [ex (.getX event)
              ey (.getY event)
              allyteam-ids (->> start-boxes
                                (map :allyteam)
                                (filter some?)
                                (map
                                  (fn [allyteam]
                                    (Integer/parseInt allyteam)))
                                set)
              allyteam-id (loop [i 0]
                            (if (contains? allyteam-ids i)
                              (recur (inc i))
                              i))]
          (swap! *state assoc :drag-allyteam {:allyteam-id allyteam-id
                                              :startx ex
                                              :starty ey
                                              :endx ex
                                              :endy ey}))
        :else
        nil)
      (catch Exception e
        (log/error e "Error pressing minimap")))))


(defmethod event-handler ::minimap-mouse-dragged
  [{:fx/keys [^javafx.scene.input.MouseEvent event]}]
  (future
    (try
      (let [x (.getX event)
            y (.getY event)]
        (swap! *state
               (fn [state]
                 (cond
                   (:drag-team state)
                   (update state :drag-team assoc
                           :x (- x start-pos-r)
                           :y (- y start-pos-r))
                   (:drag-allyteam state)
                   (update state :drag-allyteam assoc
                     :endx x
                     :endy y)
                   :else
                   state))))
      (catch Exception e
        (log/error e "Error dragging minimap")))))

(defmethod event-handler ::minimap-mouse-released
  [{:keys [minimap-width minimap-height map-details]}]
  (future
    (try
      (let [[before _after] (swap-vals! *state dissoc :drag-team :drag-allyteam)
            client (:client before)]
        (when-let [{:keys [team x y]} (:drag-team before)]
          (let [{:keys [map-width map-height]} (-> map-details :smf :header)
                x (int (* (/ x minimap-width) map-width spring/map-multiplier))
                z (int (* (/ y minimap-height) map-height spring/map-multiplier))
                scripttags {:game
                            {(keyword (str "team" team))
                             {:startposx x
                              :startposy z ; for SpringLobby bug
                              :startposz z}}}]
            (swap! *state
                   (fn [state]
                     (-> state
                         (update :scripttags u/deep-merge scripttags)
                         (update-in [:battle :scripttags] u/deep-merge scripttags))))
            (send-message client
              (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags)))))
        (when-let [{:keys [allyteam-id startx starty endx endy]} (:drag-allyteam before)]
          (let [l (min startx endx)
                t (min starty endy)
                r (max startx endx)
                b (max starty endy)
                left (/ l (* 1.0 minimap-width))
                top (/ t (* 1.0 minimap-height))
                right (/ r (* 1.0 minimap-width))
                bottom (/ b (* 1.0 minimap-height))]
            (if client
              (send-message client
                (str "ADDSTARTRECT " allyteam-id " "
                     (int (* 200 left)) " "
                     (int (* 200 top)) " "
                     (int (* 200 right)) " "
                     (int (* 200 bottom))))
              (swap! *state update-in [:battle :scripttags :game (keyword (str "allyteam" allyteam-id))]
                     (fn [allyteam]
                       (assoc allyteam
                              :startrectleft left
                              :startrecttop top
                              :startrectright right
                              :startrectbottom bottom)))))))
      (catch Exception e
        (log/error e "Error releasing minimap")))))


(defn- update-copying [f copying]
  (if f
    (swap! *state update-in [:copying (fs/canonical-path f)] merge copying)
    (log/warn "Attempt to update copying for nil file")))

(defmethod event-handler ::add-task [{:keys [task]}]
  (add-task! *state task))

(defn- import-resource [{:keys [importable spring-isolation-dir]}]
  (let [{:keys [resource-file]} importable
        source resource-file
        dest (resource/resource-dest spring-isolation-dir importable)]
    (update-copying source {:status true})
    (update-copying dest {:status true})
    (try
      (if (string/ends-with? (fs/filename source) ".sdp")
        (rapid/copy-package source (fs/parent-file (fs/parent-file dest)))
        (fs/copy source dest))
      (log/info "Finished importing" importable "from" source "to" dest)
      (catch Exception e
        (log/error e "Error importing" importable))
      (finally
        (update-copying source {:status false})
        (update-copying dest {:status false})
        (update-file-cache! source dest)
        (case (:resource-type importable)
          ::map (reconcile-maps *state)
          ::mod (reconcile-mods *state)
          ::engine (reconcile-engines *state)
          nil)))))

(defmethod task-handler ::import [e]
  (import-resource e))

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
          (catch Exception e
            (log/error e "Error during git reset" canonical-path "to ref" battle-mod-git-ref))
          (finally
            (swap! *state assoc-in [:gitting canonical-path] {:status false})))))))


(defmethod event-handler ::minimap-scroll
  [{:fx/keys [^ScrollEvent event] :keys [minimap-type-key]}]
  (.consume event)
  (swap! *state
         (fn [state]
           (let [minimap-type (get state minimap-type-key)
                 direction (if (pos? (.getDeltaY event))
                             dec
                             inc)
                 next-index (mod
                              (direction (.indexOf ^List minimap-types minimap-type))
                              (count minimap-types))
                 next-type (get minimap-types next-index)]
             (assoc state minimap-type-key next-type)))))

(defn- battle-players-and-bots
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


(defn- update-battle-status
  "Sends a message to update battle status for yourself or a bot of yours."
  [client {:keys [is-bot id]} battle-status team-color]
  (let [player-name (or (:bot-name id) (:username id))]
    (if client
      (let [prefix (if is-bot
                     (str "UPDATEBOT " player-name) ; TODO normalize
                     "MYBATTLESTATUS")]
        (log/debug player-name (pr-str battle-status) team-color)
        (send-message client
          (str prefix
               " "
               (client/encode-battle-status battle-status)
               " "
               team-color)))
      (let [data {:battle-status battle-status
                  :team-color team-color}]
        (log/info "No client, assuming singleplayer")
        (swap! *state
               (fn [state]
                 (-> state
                     (update-in [:battles :singleplayer (if is-bot :bots :users) player-name] merge data)
                     (update-in [:battle (if is-bot :bots :users) player-name] merge data))))))))

(defn- update-color [client id {:keys [is-me is-bot] :as opts} color-int]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client (assoc opts :id id) (:battle-status id) color-int)
        (send-message client
          (str "FORCETEAMCOLOR " (:username id) " " color-int)))
      (catch Exception e
        (log/error e "Error updating color")))))

(defn- update-team [client id {:keys [is-me is-bot] :as opts} player-id]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client (assoc opts :id id) (assoc (:battle-status id) :id player-id) (:team-color id))
        (send-message client
          (str "FORCETEAMNO " (:username id) " " player-id)))
      (catch Exception e
        (log/error e "Error updating team")))))

(defn- update-ally [client id {:keys [is-me is-bot] :as opts} ally]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client (assoc opts :id id) (assoc (:battle-status id) :ally ally) (:team-color id))
        (send-message client (str "FORCEALLYNO " (:username id) " " ally)))
      (catch Exception e
        (log/error e "Error updating ally")))))

(defn- update-handicap [client id {:keys [is-bot] :as opts} handicap]
  (future
    (try
      (if is-bot
        (update-battle-status client (assoc opts :id id) (assoc (:battle-status id) :handicap handicap) (:team-color id))
        (send-message client (str "HANDICAP " (:username id) " " handicap)))
      (catch Exception e
        (log/error e "Error updating handicap")))))

(defn- apply-battle-status-changes
  [client id {:keys [is-me is-bot] :as opts} status-changes]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client (assoc opts :id id) (merge (:battle-status id) status-changes) (:team-color id))
        (doseq [[k v] status-changes]
          (let [msg (case k
                      :id "FORCETEAMNO"
                      :ally "FORCEALLYNO"
                      :handicap "HANDICAP")]
            (send-message client (str msg " " (:username id) " " v)))))
      (catch Exception e
        (log/error e "Error applying battle status changes")))))


(defn- n-teams [{:keys [client] :as e} n]
  (future
    (try
      (->> e
           battle-players-and-bots
           (filter (comp :mode :battle-status)) ; remove spectators
           shuffle
           (map-indexed
             (fn [i id]
               (let [a (mod i n)
                     is-bot (boolean (:bot-name id))
                     is-me (= (:username e) (:username id))]
                 (apply-battle-status-changes client id {:is-me is-me :is-bot is-bot} {:id i :ally a}))))
           doall)
      (catch Exception e
        (log/error e "Error updating to" n "teams")))))

(defmethod event-handler ::battle-teams-ffa
  [e]
  (n-teams e 16))

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
  [{:keys [battle client users username]}]
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
            (apply-battle-status-changes client player {:is-me is-me :is-bot false} {:id i :ally 0})))
        players))
    (doall
      (map-indexed
        (fn [b bot]
          (let [i (+ (count players) b)]
            (apply-battle-status-changes client bot {:is-me false :is-bot true} {:id i :ally 1})))
        bots))))


(defn- spring-color
  "Returns the spring bgr int color format from a javafx color."
  [^javafx.scene.paint.Color color]
  (colors/rgba-int
    (colors/create-color
      {:r (Math/round (* 255 (.getBlue color)))  ; switch blue to red
       :g (Math/round (* 255 (.getGreen color)))
       :b (Math/round (* 255 (.getRed color)))   ; switch red to blue
       :a 0})))


(defn- to-bool [n]
  (if (or (not n) (zero? n))
    false
    true))

(defn- parse-skill [skill]
  (cond
    (number? skill)
    skill
    (string? skill)
    (let [[_all n] (re-find #"~?#?([\d]+)#?" skill)]
      (try
        (Double/parseDouble n)
        (catch Exception e
          (log/warn e "Error parsing skill" skill))))
    :else nil))

(defmethod event-handler ::ring
  [{:keys [channel-name client username]}]
  (when channel-name
    (send-message client (str "SAY " channel-name " !ring " username))))


(defmethod event-handler ::hostip-changed
  [{:fx/keys [event]}]
  (future
    (try
      (let [scripttags {:game {:hostip (str event)}}
            state (swap! *state
                         (fn [state]
                           (-> state
                               (update :scripttags u/deep-merge scripttags)
                               (update-in [:battle :scripttags] u/deep-merge scripttags))))]
        (send-message
          (:client state)
          (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags))))
      (catch Exception e
        (log/error e "Error updating hostip")))))

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


; https://github.com/cljfx/cljfx/issues/51#issuecomment-583974585
(def with-scroll-text-prop
  (fx.lifecycle/make-ext-with-props
   fx.lifecycle/dynamic
   {:scroll-text (fx.prop/make
                   (fx.mutator/setter
                     (fn [^TextArea text-area [txt auto-scroll]]
                       (let [scroll-pos (if auto-scroll
                                          ##Inf
                                          (.getScrollTop text-area))]
                         (doto text-area
                           (.setText txt)
                           (some-> .getParent .layout)
                           (.setScrollTop scroll-pos)))))
                   fx.lifecycle/scalar
                   :default ["" 0])}))

(def with-scroll-text-flow-prop
  (fx.lifecycle/make-ext-with-props
   fx.lifecycle/dynamic
   {:auto-scroll (fx.prop/make
                   (fx.mutator/setter
                     (fn [^ScrollPane scroll-pane [_texts auto-scroll]]
                       (let [scroll-pos (if auto-scroll
                                          ##Inf
                                          (.getVvalue scroll-pane))]
                         (doto scroll-pane
                           (some-> .getParent .layout)
                           (.setVvalue scroll-pos)))))
                   fx.lifecycle/scalar
                   :default [[] 0])}))


(defmethod event-handler ::battle-startpostype-change
  [{:fx/keys [event]}]
  (let [startpostype (get spring/startpostypes-by-name event)
        state (swap! *state
                     (fn [state]
                       (-> state
                           (assoc-in [:scripttags :game :startpostype] startpostype)
                           (assoc-in [:battle :scripttags :game :startpostype] startpostype))))]
    (send-message (:client state) (str "SETSCRIPTTAGS game/startpostype=" startpostype))))

(defmethod event-handler ::reset-start-positions
  [_e]
  (let [team-ids (take 16 (iterate inc 0))
        scripttag-keys (map (fn [i] (str "game/team" i)) team-ids)
        team-kws (map #(keyword (str "team" %)) team-ids)
        dissoc-fn #(apply dissoc % team-kws)
        state (swap! *state
                     (fn [state]
                       (-> state
                           (update-in [:scripttags :game] dissoc-fn)
                           (update-in [:battle :scripttags :game] dissoc-fn))))]
    (send-message
      (:client state)
      (str "REMOVESCRIPTTAGS " (string/join " " scripttag-keys)))))

(defmethod event-handler ::clear-start-boxes
  [_e]
  (let [{:keys [battle client]} @*state
        allyteam-ids (->> battle
                          :scripttags
                          :game
                          (filter (comp #(string/starts-with? % "allyteam") name first))
                          (map
                            (fn [[allyteamid _]]
                              (let [[_all id] (re-find #"allyteam(\d+)" (name allyteamid))]
                                id)))
                          set)]
    (doseq [allyteam-id allyteam-ids]
      (let [allyteam-kw (keyword (str "allyteam" allyteam-id))]
        (swap! *state
               (fn [state]
                 (-> state
                     (update-in [:scripttags :game] dissoc allyteam-kw)
                     (update-in [:battle :scripttags :game] dissoc allyteam-kw)))))
      (send-message client (str "REMOVESTARTRECT " allyteam-id)))))

(defmethod event-handler ::modoption-change
  [{:keys [modoption-key singleplayer] :fx/keys [event]}]
  (let [value (str event)
        state (swap! *state
                     (fn [state]
                       (-> state
                           (assoc-in [:scripttags :game :modoptions modoption-key] (str event))
                           (assoc-in [:battle :scripttags :game :modoptions modoption-key] (str event)))))]
    (when-not singleplayer
      (send-message (:client state) (str "SETSCRIPTTAGS game/modoptions/" (name modoption-key) "=" value)))))

(defmethod event-handler ::battle-ready-change
  [{:fx/keys [event] :keys [battle-status client team-color] :as id}]
  (future
    (try
      (update-battle-status client {:id id} (assoc battle-status :ready event) team-color)
      (catch Exception e
        (log/error e "Error updating battle ready")))))


(defmethod event-handler ::battle-spectate-change
  [{:keys [client id is-me is-bot] :fx/keys [event] :as data}]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client data (assoc (:battle-status id) :mode (not event)) (:team-color id))
        (send-message client (str "FORCESPECTATORMODE " (:username id))))
      (catch Exception e
        (log/error e "Error updating battle spectate")))))

(defmethod event-handler ::battle-side-changed
  [{:keys [client id sides] :fx/keys [event] :as data}]
  (future
    (try
      (let [side (get (clojure.set/map-invert sides) event)]
        (if (not= side (-> id :battle-status :side))
          (let [old-side (-> id :battle-status :side)]
            (log/info "Updating side for" id "from" old-side "(" (get sides old-side) ") to" side "(" event ")")
            (update-battle-status client data (assoc (:battle-status id) :side side) (:team-color id)))
          (log/debug "No change for side")))
      (catch Exception e
        (log/error e "Error updating battle side")))))

(defmethod event-handler ::battle-team-changed
  [{:keys [client id] :fx/keys [event] :as data}]
  (future
    (try
      (when-let [player-id (try (Integer/parseInt event) (catch Exception _e))]
        (if (not= player-id (-> id :battle-status :id))
          (do
            (log/info "Updating team for" id "from" (-> id :battle-status :side) "to" player-id)
            (update-team client id data player-id))
          (log/debug "No change for team")))
      (catch Exception e
        (log/error e "Error updating battle team")))))

(defmethod event-handler ::battle-ally-changed
  [{:keys [client id] :fx/keys [event] :as data}]
  (future
    (try
      (when-let [ally (try (Integer/parseInt event) (catch Exception _e))]
        (if (not= ally (-> id :battle-status :ally))
          (do
            (log/info "Updating ally for" id "from" (-> id :battle-status :ally) "to" ally)
            (update-ally client id data ally))
          (log/debug "No change for ally")))
      (catch Exception e
        (log/error e "Error updating battle ally")))))

(defmethod event-handler ::battle-handicap-change
  [{:keys [client id] :fx/keys [event] :as data}]
  (future
    (try
      (when-let [handicap (max 0
                            (min 100
                              event))]
        (if (not= handicap (-> id :battle-status :handicap))
          (do
            (log/info "Updating handicap for" id "from" (-> id :battle-status :ally) "to" handicap)
            (update-handicap client id data handicap))
          (log/debug "No change for handicap")))
      (catch Exception e
        (log/error e "Error updating battle handicap")))))

(defmethod event-handler ::battle-color-action
  [{:keys [client id is-me] :fx/keys [^javafx.event.Event event] :as opts}]
  (future
    (try
      (let [^javafx.scene.control.ColorPicker source (.getSource event)
            javafx-color (.getValue source)
            color-int (spring-color javafx-color)]
        (when is-me
          (swap! *state assoc :preferred-color color-int))
        (update-color client id opts color-int))
      (catch Exception e
        (log/error e "Error updating battle color")))))

(defmethod task-handler ::update-rapid
  [_e]
  (swap! *state assoc :rapid-update true)
  (let [before (u/curr-millis)
        {:keys [engine-version engines file-cache spring-isolation-dir]} @*state ; TODO remove deref
        preferred-engine-details (spring/engine-details engines engine-version)
        engine-details (if (and preferred-engine-details (:file preferred-engine-details))
                         preferred-engine-details
                         (->> engines
                              (filter (comp fs/canonical-path :file))
                              first))]
    (if (and engine-details (:file engine-details))
      (do
        (log/info "Initializing rapid by calling download")
        (deref
          (event-handler
            {:event/type ::rapid-download
             :rapid-id "i18n:test" ; TODO how else to init rapid without download...
             :engine-file (:file engine-details)
             :spring-isolation-dir spring-isolation-dir})))
      (log/warn "No engine details to do rapid init"))
    (log/info "Updating rapid versions in" spring-isolation-dir)
    (let [rapid-repos (rapid/repos spring-isolation-dir)
          _ (log/info "Found" (count rapid-repos) "rapid repos")
          rapid-repo-files (map (partial rapid/version-file spring-isolation-dir) rapid-repos)
          new-files (->> rapid-repo-files
                         (map file-cache-data)
                         file-cache-by-path)
          rapid-versions (->> rapid-repo-files
                              (filter
                                (fn [f]
                                  (let [path (fs/canonical-path f)
                                        prev-time (or (-> file-cache (get path) :last-modified) 0)
                                        curr-time (or (-> new-files (get path) :last-modified) Long/MAX_VALUE)]
                                    (< prev-time curr-time))))
                              (mapcat rapid/rapid-versions)
                              (filter :version)
                              (sort-by :version version/version-compare)
                              reverse)
          _ (log/info "Found" (count rapid-versions) "rapid versions")
          rapid-data-by-hash (->> rapid-versions
                              (map (juxt :hash identity))
                              (into {}))
          rapid-data-by-version (->> rapid-versions
                                     (map (juxt :version identity))
                                     (into {}))]
      (swap! *state
        (fn [state]
          (-> state
              (assoc :rapid-repos rapid-repos)
              (update :rapid-data-by-hash merge rapid-data-by-hash)
              (update :rapid-data-by-version merge rapid-data-by-version)
              (update :rapid-versions (fn [old-versions]
                                        (set (concat old-versions rapid-versions))))
              (update :file-cache merge new-files))))
      (log/info "Updated rapid repo data in" (- (u/curr-millis) before) "ms")
      (add-task! *state {::task-type ::update-rapid-packages}))))

(defmethod task-handler ::update-rapid-packages
  [_e]
  (swap! *state assoc :rapid-update true)
  (let [{:keys [rapid-data-by-hash spring-isolation-dir]} @*state
        sdp-files (doall (rapid/sdp-files spring-isolation-dir))
        packages (->> sdp-files
                      (filter some?)
                      (map
                        (fn [f]
                          (let [rapid-data
                                (->> f
                                     rapid/sdp-hash
                                     (get rapid-data-by-hash))]
                            {:id (->> rapid-data :id str)
                             :filename (-> f fs/filename str)
                             :version (->> rapid-data :version str)})))
                      (sort-by :version version/version-compare)
                      reverse
                      doall)]
    (swap! *state assoc
           :sdp-files sdp-files
           :rapid-packages packages
           :rapid-update false)))

(defmethod event-handler ::rapid-repo-change
  [{:fx/keys [event]}]
  (swap! *state assoc :rapid-repo event))

(defn parse-rapid-progress [line]
  (when (string/starts-with? line "[Progress]")
    (if-let [[_all _percent _bar current total] (re-find #"\[Progress\]\s+(\d+)%\s+\[([\s=]+)\]\s+(\d+)/(\d+)" line)]
      {:current (Long/parseLong current)
       :total (Long/parseLong total)}
      (log/warn "Unable to parse rapid progress" (pr-str line)))))


(defmethod event-handler ::rapid-download
  [{:keys [engine-file rapid-id spring-isolation-dir]}]
  (swap! *state assoc-in [:rapid-download rapid-id] {:running true
                                                     :message "Preparing to run pr-downloader"})
  (future
    (try
      (let [^File root spring-isolation-dir
            pr-downloader-file (fs/pr-downloader-file engine-file)
            _ (fs/set-executable pr-downloader-file)
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
                  (let [progress (try (parse-rapid-progress line)
                                      (catch Exception e
                                        (log/debug e "Error parsing rapid progress")))]
                    (swap! *state update-in [:rapid-download rapid-id] merge
                           {:message line}
                           progress)
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
          (apply update-file-cache! (rapid/sdp-files spring-isolation-dir))
          (add-tasks! *state [{::task-type ::update-rapid-packages}
                              {::task-type ::reconcile-mods}])))
      (catch Exception e
        (log/error e "Error downloading" rapid-id)
        (swap! *state assoc-in [:rapid-download rapid-id :message] (.getMessage e)))
      (finally
        (swap! *state assoc-in [:rapid-download rapid-id :running] false)))))

(defmethod task-handler ::rapid-download
  [task]
  @(event-handler (assoc task :event/type ::rapid-download)))


; https://github.com/dakrone/clj-http/pull/220/files
(defn- print-progress-bar
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

(defn- insert-at
  "Addes value into a vector at an specific index."
  [v idx value]
  (-> (subvec v 0 idx)
      (conj value)
      (into (subvec v idx))))

(defn- insert-after
  "Finds an item into a vector and adds val just after it.
   If needle is not found, the input vector will be returned."
  [^clojure.lang.APersistentVector v needle value]
  (let [index (.indexOf v needle)]
    (if (neg? index)
      v
      (insert-at v (inc index) value))))

(defn- wrap-downloaded-bytes-counter
  "Middleware that provides an CountingInputStream wrapping the stream output"
  [client]
  (fn [req]
    (let [resp (client req)
          counter (CountingInputStream. (:body resp))]
      (merge resp {:body                     counter
                   :downloaded-bytes-counter counter}))))


(defmethod event-handler ::http-download
  [{:keys [dest url]}]
  (swap! *state assoc-in [:http-download url] {:running true})
  (log/info "Request to download" url "to" dest)
  (future
    (try
      (fs/make-parent-dirs dest)
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
                      (try
                        (dh/with-rate-limiter {:ratelimiter limit-download-status
                                               :max-wait-ms 0}
                          (let [current (.getByteCount counter)]
                            (swap! *state update-in [:http-download url]
                                   merge
                                   {:current current
                                    :total length})))
                        (catch Exception e
                          (when-not (:throttled (ex-data e))
                            (log/warn e "Error updating download status")))))
                    (recur))))))))
      (catch Exception e
        (log/error e "Error downloading" url "to" dest)
        (raynes-fs/delete dest))
      (finally
        (swap! *state assoc-in [:http-download url :running] false)
        (update-file-cache! dest)
        (log/info "Finished downloading" url "to" dest)))))

(defn- download-http-resource [{:keys [downloadable spring-isolation-dir]}]
  (log/info "Request to download" downloadable)
  (future
    (deref
      (event-handler
        {:event/type ::http-download
         :dest (resource/resource-dest spring-isolation-dir downloadable)
         :url (:download-url downloadable)}))
    (case (:resource-type downloadable)
      ::map (reconcile-maps *state)
      ::mod (reconcile-mods *state)
      ::engine (reconcile-engines *state)
      nil)))

(defmethod event-handler ::http-downloadable
  [e]
  (download-http-resource e))

(defmethod task-handler ::http-downloadable
  [task]
  @(event-handler (assoc task :event/type ::http-downloadable)))

(defmethod task-handler ::download-bar-replay
  [{:keys [id spring-isolation-dir]}]
  (log/info "Downloading replay id" id)
  (let [{:keys [fileName]} (http/get-bar-replay-details {:id id})]
    (log/info "Downloaded replay details for id" id ":" fileName)
    (swap! *state assoc-in [:online-bar-replays id :filename] fileName)
    @(download-http-resource
       {:downloadable {:download-url (http/bar-replay-download-url fileName)
                       :resource-filename fileName
                       :resource-type ::replay}
        :spring-isolation-dir spring-isolation-dir})
    (add-task! *state {::task-type ::refresh-replays})))

(defmethod task-handler ::download-springfiles
  [{:keys [springname spring-isolation-dir]}]
  (log/info "Searching springfiles for" springname)
  (let [result (->> (clj-http/get "https://api.springfiles.com/json.php"
                      {:query-params {:springname springname}
                       :as :json})
                    :body
                    first)]
    (if result
      (if-let [mirror (-> result :mirrors first)]
        (do
          (swap! *state assoc-in [:springfiles-urls springname] mirror)
          (log/info "Found details for" springname "on springfiles" result)
          @(download-http-resource
             {:downloadable {:download-url mirror
                             :resource-filename (:filename result)
                             :resource-type ::mod} ; TODO other types?
              :spring-isolation-dir spring-isolation-dir})
          (add-task! *state {::task-type ::reconcile-mods}))
        (log/info "No mirror to download" springname "on springfiles" result))
      (log/info "No result for" springname "on springfiles"))))


(defmethod event-handler ::extract-7z
  [{:keys [file dest]}]
  (future
    (let [path (fs/canonical-path file)]
      (try
        (swap! *state assoc-in [:extracting path] true)
        (if dest
          (fs/extract-7z-fast file dest)
          (fs/extract-7z-fast file))
        (reconcile-engines *state)
        (catch Exception e
          (log/error e "Error extracting 7z" file))
        (finally
          (swap! *state assoc-in [:extracting path] false))))))

(defmethod task-handler ::extract-7z
  [task]
  @(event-handler (assoc task :event/type ::extract-7z)))


(def resource-types
  [::engine ::map ::mod ::sdp]) ; TODO split out packaging type from resource type...

(defn- update-importable
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

(defn- importable-data [resource-type import-source-name now resource-file]
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
    (log/info "Found imports" (frequencies (map :resource-type importables)) "from" import-source-name)
    (swap! *state update :importables-by-path
           (fn [old]
             (->> old
                  (remove (comp #{import-source-name} :import-source-name second))
                  (into {})
                  (merge importables-by-path))))
    importables-by-path))


(def springfiles-maps-download-source
  {:download-source-name "SpringFiles Maps"
   :url http/springfiles-maps-url
   :resources-fn http/html-downloadables})

(def download-sources
  [springfiles-maps-download-source
   {:download-source-name "BAR GitHub spring"
    :url http/bar-spring-releases-url
    :browse-url "https://github.com/beyond-all-reason/spring/releases"
    :resources-fn http/get-github-release-engine-downloadables}
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
    :resources-fn http/crawl-springrts-engine-downloadables}])


(def downloadable-update-cooldown
  (* 1000 60 60 24)) ; 1 day


(defn- update-download-source
  [{:keys [force resources-fn url download-source-name] :as source}]
  (log/info "Getting resources for possible download from" download-source-name "at" url)
  (let [now (u/curr-millis)
        last-updated (or (-> *state deref :downloadables-last-updated (get url)) 0)] ; TODO remove deref
    (if (or (< downloadable-update-cooldown (- now last-updated))
            force)
      (do
        (log/info "Updating downloadables from" url)
        (swap! *state assoc-in [:downloadables-last-updated url] now)
        (let [downloadables (resources-fn source)
              downloadables-by-url (->> downloadables
                                        (map (juxt :download-url identity))
                                        (into {}))]
          (log/info "Found downloadables from" download-source-name "at" url
                    (frequencies (map :resource-type downloadables)))
          (swap! *state update :downloadables-by-url
                 (fn [old]
                   (->> old
                        (remove (comp #{download-source-name} :download-source-name second))
                        (into {})
                        (merge downloadables-by-url))))
          downloadables-by-url))
      (log/info "Too soon to check downloads from" url))))

(defmethod task-handler ::update-downloadables
  [source]
  (update-download-source source))


(defmethod event-handler ::import-source-change
  [{:fx/keys [event]}]
  (swap! *state assoc :import-source-name (:import-source-name event)))

(defn- import-source-cell
  [{:keys [file import-source-name]}]
  {:text (str import-source-name
              (when file
                (str " ( at " (fs/canonical-path file) " )")))})

(defn- import-type-cell
  [import-type]
  {:text (if import-type
           (name import-type)
           " < nil > ")})

(defmethod event-handler ::assoc
  [{:fx/keys [event] :as e}]
  (swap! *state assoc (:key e) (or (:value e) event)))

(defmethod event-handler ::assoc-in
  [{:fx/keys [event] :keys [path value] :or {value event}}]
  (swap! *state assoc-in path value))

(defmethod event-handler ::dissoc
  [e]
  (swap! *state dissoc (:key e)))

(defmethod event-handler ::dissoc-in
  [{:keys [path]}]
  (swap! *state update-in (drop-last path) dissoc (last path)))

(defmethod event-handler ::scan-imports
  [{:keys [sources] :or {sources (import-sources (:extra-import-sources @*state))}}]
  (doseq [import-source sources]
    (add-task! *state (merge
                        {::task-type ::scan-imports}
                        import-source))))

(defmethod task-handler ::scan-all-imports [task]
  (event-handler (assoc task ::task-type ::scan-imports)))

(def import-window-keys
  [:copying :extra-import-sources :file-cache :import-filter :import-source-name :import-type
   :importables-by-path :show-importer :show-stale :spring-isolation-dir])

(defn- import-window
  [{:keys [copying extra-import-sources file-cache import-filter import-type import-source-name importables-by-path
           show-importer show-stale spring-isolation-dir tasks-by-type]}]
  (let [import-sources (import-sources extra-import-sources)
        import-source (->> import-sources
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
        import-tasks (->> (get tasks-by-type ::import)
                          (map (comp fs/canonical-path :resource-file :importable))
                          set)
        {:keys [width height]} (screen-bounds)]
    {:fx/type :stage
     :showing (boolean show-importer)
     :title (str u/app-name " Importer")
     :icons icons
     :on-close-request (fn [^javafx.stage.WindowEvent e]
                         (swap! *state assoc :show-importer false)
                         (.consume e))
     :width (min download-window-width width)
     :height (min download-window-height height)
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      (if show-importer
        {:fx/type :v-box
         :children
         [{:fx/type :button
           :style {:-fx-font-size 16}
           :text "Refresh All Imports"
           :on-action {:event/type ::scan-imports
                       :sources import-sources}}
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
               :prompt-text " < pick a source > "
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
             (when import-source
               [{:fx/type :button
                 :text " Refresh "
                 :on-action {:event/type ::add-task
                             :task (merge {::task-type ::scan-imports}
                                          import-source)}
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-refresh:16:white"}}]))}
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
               :prompt-text " < pick a type > "
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
             :text "Filename"
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe (fn [i] {:text (str (:resource-filename i))})}}
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
                      dest-path (some->> importable (resource/resource-dest spring-isolation-dir) fs/canonical-path)
                      copying (or (-> copying (get source-path) :status)
                                  (-> copying (get dest-path) :status))
                      in-progress (boolean
                                    (or (contains? import-tasks source-path)
                                        copying))]
                  {:text ""
                   :graphic
                   (if (fs/file-exists? file-cache dest-path)
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
                                   :importable importable
                                   :spring-isolation-dir spring-isolation-dir}}
                      :graphic
                      {:fx/type font-icon/lifecycle
                       :icon-literal "mdi-content-copy:16:white"}})}))}}]}]}
       {:fx/type :pane})}}))


(defmethod event-handler ::download-source-change
  [{:fx/keys [event]}]
  (swap! *state assoc :download-source-name (:download-source-name event)))

(defn- download-source-cell
  [{:keys [url download-source-name]}]
  {:text (str download-source-name " ( at " url " )")})

(defmethod event-handler ::update-downloadables
  [{:keys [force]}]
  (doseq [download-source download-sources]
    (add-task! *state (merge
                        {::task-type ::update-downloadables
                         :force force}
                        download-source))))

(def download-window-keys
  [:download-filter :download-source-name :download-type :downloadables-by-url :file-cache
   :http-download :show-downloader :show-stale :spring-isolation-dir])

(defn- download-window
  [{:keys [download-filter download-type download-source-name downloadables-by-url file-cache
           http-download show-downloader show-stale spring-isolation-dir]}]
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
                                       true))))
        {:keys [width height]} (screen-bounds)]
    {:fx/type :stage
     :showing (boolean show-downloader)
     :title (str u/app-name " Downloader")
     :icons icons
     :on-close-request (fn [^javafx.stage.WindowEvent e]
                         (swap! *state assoc :show-downloader false)
                         (.consume e))
     :width (min download-window-width width)
     :height (min download-window-height height)
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      (if show-downloader
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
               :prompt-text " < pick a source > "
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
                  :on-action {:event/type ::desktop-browse-url
                              :url (or (:browse-url download-source)
                                       (:url download-source))}
                  :graphic
                  {:fx/type font-icon/lifecycle
                   :icon-literal "mdi-web:16:white"}}}])
             [{:fx/type :button
               :text " Refresh "
               :on-action
               (if download-source
                 {:event/type ::add-task
                  :task
                  (merge
                    {::task-type ::update-downloadables
                     :force true}
                    download-source)}
                 {:event/type ::update-downloadables
                  :force true})
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-refresh:16:white"}}])}
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
               :prompt-text " < pick a type > "
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
             :text "Source"
             :cell-value-factory :download-source-name
             :cell-factory
             {:fx/cell-type :table-cell
              :describe (fn [source] {:text (str source)})}}
            {:fx/type :table-column
             :text "Type"
             :cell-value-factory :resource-type
             :cell-factory
             {:fx/cell-type :table-cell
              :describe import-type-cell}}
            {:fx/type :table-column
             :text "File"
             :cell-value-factory :resource-filename
             :cell-factory
             {:fx/cell-type :table-cell
              :describe (fn [resource-filename] {:text (str resource-filename)})}}
            {:fx/type :table-column
             :text "URL"
             :cell-value-factory :download-url
             :cell-factory
             {:fx/cell-type :table-cell
              :describe (fn [download-url] {:text (str download-url)})}}
            {:fx/type :table-column
             :text "Download"
             :sortable false
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [{:keys [download-url resource-filename] :as downloadable}]
                (let [dest-file (resource/resource-dest spring-isolation-dir downloadable)
                      dest-path (fs/canonical-path dest-file)
                      download (get http-download download-url)
                      in-progress (:running download)
                      extract-file (when dest-file
                                     (io/file spring-isolation-dir "engine" (fs/filename dest-file)))]
                  {:text ""
                   :graphic
                   (cond
                     in-progress
                     {:fx/type :label
                      :text (str (u/download-progress download))}
                     (and (not in-progress)
                          (not (fs/file-exists? file-cache dest-path)))
                     {:fx/type :button
                      :tooltip
                      {:fx/type :tooltip
                       :show-delay [10 :ms]
                       :text (str "Download to " dest-path)}
                      :on-action {:event/type ::add-task
                                  :task {::task-type ::http-downloadable
                                         :downloadable downloadable
                                         :spring-isolation-dir spring-isolation-dir}}
                      :graphic
                      {:fx/type font-icon/lifecycle
                       :icon-literal "mdi-download:16:white"}}
                     (and
                          (fs/file-exists? file-cache dest-path)
                          dest-file
                          (or
                            (http/engine-archive? resource-filename)
                            (http/bar-engine-filename? resource-filename))
                          extract-file
                          (not (fs/file-exists? file-cache (fs/canonical-path extract-file))))
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
                      :icon-literal "mdi-check:16:white"})}))}}]}]}
       {:fx/type :pane})}}))


(def rapid-download-window-keys
  [:engine-version :engines :rapid-data-by-hash :rapid-download :rapid-filter :rapid-repo
   :rapid-repos :rapid-packages :rapid-versions :sdp-files :show-rapid-downloader :spring-isolation-dir])

(defn- rapid-download-window
  [{:keys [engine-version engines rapid-download rapid-filter rapid-repo rapid-repos rapid-versions
           rapid-packages sdp-files show-rapid-downloader spring-isolation-dir]}]
  (let [sdp-files (or sdp-files [])
        sdp-hashes (set (map rapid/sdp-hash sdp-files))
        sorted-engine-versions (->> engines
                                    (map :engine-version)
                                    sort)
        filtered-rapid-versions (->> rapid-versions
                                     (filter
                                       (fn [{:keys [id]}]
                                         (if rapid-repo
                                           (string/starts-with? id (str rapid-repo ":"))
                                           true)))
                                     (filter
                                       (fn [{:keys [version] :as i}]
                                         (if-not (string/blank? rapid-filter)
                                           (or
                                             (string/includes?
                                               (string/lower-case version)
                                               (string/lower-case rapid-filter))
                                             (string/includes?
                                               (:hash i)
                                               (string/lower-case rapid-filter)))
                                           true))))
        engines-by-version (into {} (map (juxt :engine-version identity) engines))
        engine-file (:file (get engines-by-version engine-version))
        {:keys [width height]} (screen-bounds)]
    {:fx/type :stage
     :showing (boolean show-rapid-downloader)
     :title (str u/app-name " Rapid Downloader")
     :icons icons
     :on-close-request (fn [^WindowEvent e]
                         (swap! *state assoc :show-rapid-downloader false)
                         (.consume e))
     :width (min download-window-width width)
     :height (min download-window-height height)
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      (if show-rapid-downloader
        {:fx/type :v-box
         :children
         [{:fx/type :h-box
           :style {:-fx-font-size 16}
           :alignment :center-left
           :children
           [{:fx/type :label
             :text " Engine for pr-downloader: "}
            {:fx/type :combo-box
             :value (str engine-version)
             :items (or (seq sorted-engine-versions)
                        [])
             :on-value-changed {:event/type ::version-change}}
            {:fx/type :button
             :text " Refresh "
             :on-action {:event/type ::add-task
                         :task {::task-type ::update-rapid}}
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-refresh:16:white"}}]}
          {:fx/type :h-box
           :style {:-fx-font-size 16}
           :alignment :center-left
           :children
           (concat
             [{:fx/type :label
               :text " Filter Repo: "}
              {:fx/type :combo-box
               :value (str rapid-repo)
               :items (or (seq rapid-repos)
                          [])
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
           :items (or (seq filtered-rapid-versions)
                      [])
           :columns
           [{:fx/type :table-column
             :sortable false
             :text "ID"
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                {:text (str (:id i))})}}
            {:fx/type :table-column
             :sortable false
             :text "Hash"
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                {:text (str (:hash i))})}}
            {:fx/type :table-column
             :text "Version"
             :cell-value-factory :version
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [version]
                {:text (str version)})}}
            {:fx/type :table-column
             :text "Download"
             :sortable false
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                (let [download (get rapid-download (:id i))]
                  (merge
                    {:text (str (:message download))
                     :style {:-fx-font-family monospace-font-family}}
                    (cond
                      (sdp-hashes (:hash i))
                      {:graphic
                       {:fx/type font-icon/lifecycle
                        :icon-literal "mdi-check:16:white"}}
                      (:running download)
                      nil
                      (not engine-file)
                      {:text "Needs an engine"}
                      :else
                      {:graphic
                       {:fx/type :button
                        :on-action {:event/type ::add-task
                                    :task
                                    {::task-type ::rapid-download
                                     :engine-file engine-file
                                     :rapid-id (:id i)
                                     :spring-isolation-dir spring-isolation-dir}}
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
                          :file (io/file spring-isolation-dir "packages")}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-folder:16:white"}}}]}
          {:fx/type :table-view
           :column-resize-policy :constrained ; TODO auto resize
           :items (or (seq rapid-packages)
                      [])
           :columns
           [{:fx/type :table-column
             :text "Filename"
             :sortable false
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i] {:text (:filename i)})}}
            {:fx/type :table-column
             :sortable false
             :text "ID"
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i] {:text (:id i)})}}
            {:fx/type :table-column
             :text "Version"
             :cell-value-factory :version
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [version] {:text (str version)})}}]}]}
       {:fx/type :pane})}}))


(defmethod event-handler ::watch-replay
  [{:keys [engine-version engines replay spring-isolation-dir]}]
  (future
    (try
      (let [replay-file (:file replay)]
        (swap! *state assoc-in [:replays-watched (fs/canonical-path replay-file)] true)
        (spring/watch-replay
          {:engine-version engine-version
           :engines engines
           :replay-file replay-file
           :spring-isolation-dir spring-isolation-dir}))
      (catch Exception e
        (log/error e "Error watching replay" replay)))))


(defmethod event-handler ::select-replay
  [{:fx/keys [event]}]
  (future
    (let [replay-game (-> event :body :script-data :game)]
      (swap! *state
        (fn [state]
          (cond-> state
            true
            (assoc :selected-replay-file (:file event))
            (:id event)
            (assoc :selected-replay-id (:id event))
            (or (not (:gametype replay-game))
                (not= (:gametype replay-game)
                      (:mod-name (:replay-mod-details state))))
            (assoc :replay-mod-details nil)
            (or (not (:gametype replay-game))
                (not= (:mapname replay-game)
                      (:map-name (:replay-map-details state))))
            (assoc :replay-map-details nil)))))))


(defn- sanitize-replay-filter [s]
  (-> s (string/replace #"[^\p{Alnum}]" "") string/lower-case))

(defn- replay-player-count
  [{:keys [player-counts]}]
  (reduce (fnil + 0) 0 player-counts))

(defn- replay-skills
  [{:keys [body]}]
  (let [skills (some->> body :script-data :game
                        (filter (comp #(string/starts-with? % "player") name first))
                        (filter (comp #{0 "0"} :spectator second))
                        (map (comp :skill second))
                        (map parse-skill)
                        (filter some?))]
    skills))

(defn- min-skill [coll]
  (when (seq coll)
    (reduce min Long/MAX_VALUE coll)))

(defn- average-skill [coll]
  (when (seq coll)
    (with-precision 3
      (/ (bigdec (reduce + coll))
         (bigdec (count coll))))))

(defn- max-skill [coll]
  (when (seq coll)
    (reduce max 0 coll)))

(defn process-bar-replay [replay]
  (let [player-counts (->> replay
                           :AllyTeams
                           (map
                             (fn [allyteam]
                               (count (mapcat allyteam [:Players :AIs])))))
        teams (->> replay
                   :AllyTeams
                   (mapcat
                     (fn [allyteam]
                       (map
                         (fn [player]
                           [(str "team" (:playerId player))
                            {:team (:playerId player)
                             :allyteam (:allyTeamId allyteam)}])
                         (:Players allyteam)))))
        players (->> replay
                     :AllyTeams
                     (mapcat
                       (fn [allyteam]
                         (map
                           (fn [{:keys [playerId] :as player}]
                             [(str "player" playerId)
                              {:team playerId
                               :username (:name player)}])
                           (:Players allyteam)))))
        spectators (->> replay
                        :Spectators
                        (map (fn [{:keys [playerId] :as spec}]
                               [(str "player" playerId)
                                {:username (:name spec)
                                 :spectator 1}])))]
    (-> replay
        (assoc :source-name "BAR Online")
        (assoc :body {:script-data {:game (into {} (concat (:hostSettings replay) players teams spectators))}})
        (assoc :header {:unix-time (quot (inst-ms (java-time/instant (:startTime replay))) 1000)})
        (assoc :player-counts player-counts)
        (assoc :game-type (replay-game-type player-counts)))))

(defmethod task-handler ::download-bar-replays [{:keys [page]}]
  (let [new-bar-replays (->> (http/get-bar-replays {:page page})
                             (map process-bar-replay))]
    (log/info "Got" (count new-bar-replays) "BAR replays from page" page)
    (let [[before after] (swap-vals! *state
                           (fn [state]
                             (-> state
                                 (assoc :bar-replays-page ((fnil inc 0) (int (u/to-number page))))
                                 (update :online-bar-replays
                                   (fn [online-bar-replays]
                                     (u/deep-merge
                                       online-bar-replays
                                       (into {}
                                         (map
                                           (juxt :id identity)
                                           new-bar-replays))))))))
          new-count (- (count (:online-bar-replays after))
                       (count (:online-bar-replays before)))]
      (log/info "Got" new-count "new online replays")
      (swap! *state assoc :new-online-replays-count new-count))))


(def replays-window-keys
  [:bar-replays-page :battle-players-color-allyteam :copying :engines :extra-replay-sources :extracting :file-cache :filter-replay :filter-replay-max-players :filter-replay-min-players :filter-replay-min-skill :filter-replay-source
   :filter-replay-type :http-download :maps :mods :new-online-replays-count :on-close-request :online-bar-replays :parsed-replays-by-path :rapid-data-by-version :rapid-download
   :rapid-update
   :replay-downloads-by-engine :replay-downloads-by-map :replay-downloads-by-mod
   :replay-imports-by-map :replay-imports-by-mod :replay-map-details :replay-minimap-type :replay-mod-details :replays-filter-specs :replays-watched :replays-window-details :selected-replay-file :selected-replay-id :settings-button
   :show-replays :spring-isolation-dir :update-engines :update-maps :update-mods])

(defn replays-window
  [{:keys [bar-replays-page battle-players-color-allyteam copying engines extra-replay-sources extracting file-cache filter-replay filter-replay-max-players filter-replay-min-players filter-replay-min-skill filter-replay-source
           filter-replay-type http-download maps mods new-online-replays-count on-close-request online-bar-replays parsed-replays-by-path rapid-data-by-version rapid-download
           rapid-update replay-downloads-by-engine replay-downloads-by-map replay-downloads-by-mod
           replay-imports-by-map replay-imports-by-mod replay-map-details replay-minimap-type replay-mod-details replays-filter-specs replays-watched replays-window-details selected-replay-file selected-replay-id
           show-replays spring-isolation-dir tasks-by-type title update-engines update-maps update-mods]}]
  (let [local-filenames (->> parsed-replays-by-path
                             vals
                             (map :filename)
                             (filter some?)
                             set)
        online-only-replays (->> online-bar-replays
                                 vals
                                 (remove (comp local-filenames :filename)))
        all-replays (->> parsed-replays-by-path
                         vals
                         (concat online-only-replays)
                         (sort-by (comp str :unix-time :header))
                         reverse
                         doall)
        replay-types (set (map :game-type all-replays))
        num-players (->> all-replays
                         (map replay-player-count)
                         set
                         sort)
        filter-terms (->> (string/split (or filter-replay "") #"\s+")
                          (remove string/blank?)
                          (map string/lower-case))
        includes-term? (fn [s term]
                         (let [lc (string/lower-case (or s ""))]
                           (string/includes? lc term)))
        replays (->> all-replays
                     (filter
                       (fn [replay]
                         (if filter-replay-source
                           (= filter-replay-source (:source-name replay))
                           true)))
                     (filter
                       (fn [replay]
                         (if filter-replay-type
                           (= filter-replay-type (:game-type replay))
                           true)))
                     (filter
                       (fn [replay]
                         (if filter-replay-min-players
                           (<= filter-replay-min-players (replay-player-count replay))
                           true)))
                     (filter
                       (fn [replay]
                         (if filter-replay-max-players
                           (<= (replay-player-count replay) filter-replay-max-players)
                           true)))
                     (filter
                       (fn [replay]
                         (if filter-replay-min-skill
                           (if-let [avg (average-skill (replay-skills replay))]
                             (<= filter-replay-min-skill avg)
                             false)
                           true)))
                     (filter
                       (fn [replay]
                         (if (empty? filter-terms)
                           true
                           (every?
                             (some-fn
                               (partial includes-term? (:filename replay))
                               (partial includes-term? (-> replay :header :engine-version))
                               (partial includes-term? (-> replay :body :script-data :game :gametype))
                               (partial includes-term? (-> replay :body :script-data :game :mapname))
                               (fn [term]
                                 (let [players (some->> replay :body :script-data :game
                                                        (filter (comp #(string/starts-with? % "player") name first))
                                                        (filter
                                                          (if replays-filter-specs
                                                            (constantly true)
                                                            (comp #{0 "0"} :spectator second)))
                                                        (map (comp sanitize-replay-filter :name second)))]
                                   (some #(includes-term? % term) players))))
                             filter-terms)))))
        selected-replay (or (get parsed-replays-by-path (fs/canonical-path selected-replay-file))
                            (get online-bar-replays selected-replay-id))
        engines-by-version (into {} (map (juxt :engine-version identity) engines))
        mods-by-version (into {} (map (juxt :mod-name identity) mods))
        maps-by-version (into {} (map (juxt :map-name identity) maps))

        selected-engine-version (-> selected-replay :header :engine-version)
        selected-matching-engine (get engines-by-version selected-engine-version)
        selected-matching-mod (get mods-by-version (-> selected-replay :body :script-data :game :gametype))
        selected-matching-map (get maps-by-version (-> selected-replay :body :script-data :game :mapname))
        extract-tasks (->> (get tasks-by-type ::extract-7z)
                           (map (comp fs/canonical-path :file))
                           set)
        import-tasks (->> (get tasks-by-type ::import)
                          (map (comp fs/canonical-path :resource-file :importable))
                          set)
        refresh-tasks (get tasks-by-type ::refresh-replays)
        index-downloads-tasks (get tasks-by-type ::download-bar-replays)
        download-tasks (->> (get tasks-by-type ::download-bar-replay)
                            (map :id)
                            set)
        {:keys [width height]} (screen-bounds)
        time-zone-id (.toZoneId (TimeZone/getDefault))
        sources (replay-sources {:extra-replay-sources extra-replay-sources})]
    {:fx/type :stage
     :showing (boolean show-replays)
     :title (or title (str u/app-name " Replays"))
     :icons icons
     :on-close-request
     (or
       on-close-request
       (fn [^javafx.stage.WindowEvent e]
         (swap! *state assoc :show-replays false)
         (.consume e)))
     :width (min replays-window-width width)
     :height (min replays-window-height height)
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      (if show-replays
        {:fx/type :v-box
         :style {:-fx-font-size 14}
         :children
         (concat
           [{:fx/type :h-box
             :alignment :top-left
             :style {:-fx-font-size 16}
             :children
             [
              {:fx/type :flow-pane
               :h-box/hgrow :always
               :style {:-fx-pref-width 200}
               :children
               (concat
                 [{:fx/type :label
                   :text " Filter: "}
                  {:fx/type :text-field
                   :style {:-fx-min-width 400}
                   :text (str filter-replay)
                   :prompt-text "Filter by filename, engine, map, game, player"
                   :on-text-changed {:event/type ::assoc
                                     :key :filter-replay}}]
                 (when-not (string/blank? filter-replay)
                   [{:fx/type fx.ext.node/with-tooltip-props
                     :props
                     {:tooltip
                      {:fx/type :tooltip
                       :show-delay [10 :ms]
                       :text "Clear filter"}}
                     :desc
                     {:fx/type :button
                      :on-action {:event/type ::dissoc
                                  :key :filter-replay}
                      :graphic
                      {:fx/type font-icon/lifecycle
                       :icon-literal "mdi-close:16:white"}}}])
                 [{:fx/type :h-box
                   :alignment :center-left
                   :children
                   [
                    {:fx/type :label
                     :text " Filter specs:"}
                    {:fx/type :check-box
                     :selected (boolean replays-filter-specs)
                     :h-box/margin 8
                     :on-selected-changed {:event/type ::assoc
                                           :key :replays-filter-specs}}]}
                  {:fx/type :h-box
                   :alignment :center-left
                   :children
                   (concat
                     [{:fx/type :label
                       :text " Source: "}
                      {:fx/type :combo-box
                       :value filter-replay-source
                       :on-value-changed {:event/type ::assoc
                                          :key :filter-replay-source}
                       :items (concat [nil] (sort (map :replay-source-name sources)))}]
                     (when filter-replay-source
                       [{:fx/type fx.ext.node/with-tooltip-props
                         :props
                         {:tooltip
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
                           :text "Clear source"}}
                         :desc
                         {:fx/type :button
                          :on-action {:event/type ::dissoc
                                      :key :filter-replay-source}
                          :graphic
                          {:fx/type font-icon/lifecycle
                           :icon-literal "mdi-close:16:white"}}}]))}
                  {:fx/type :h-box
                   :alignment :center-left
                   :children
                   (concat
                     [{:fx/type :label
                       :text " Type: "}
                      {:fx/type :combo-box
                       :value filter-replay-type
                       :on-value-changed {:event/type ::assoc
                                          :key :filter-replay-type}
                       :items (concat [nil] replay-types)}]
                     (when filter-replay-type
                       [{:fx/type fx.ext.node/with-tooltip-props
                         :props
                         {:tooltip
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
                           :text "Clear type"}}
                         :desc
                         {:fx/type :button
                          :on-action {:event/type ::dissoc
                                      :key :filter-replay-type}
                          :graphic
                          {:fx/type font-icon/lifecycle
                           :icon-literal "mdi-close:16:white"}}}]))}
                  {:fx/type :h-box
                   :alignment :center-left
                   :children
                   (concat
                     [{:fx/type :label
                       :text " Min Players: "}
                      {:fx/type :combo-box
                       :value filter-replay-min-players
                       :on-value-changed {:event/type ::assoc
                                          :key :filter-replay-min-players}
                       :items (concat [nil] num-players)}]
                     (when filter-replay-min-players
                       [{:fx/type fx.ext.node/with-tooltip-props
                         :props
                         {:tooltip
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
                           :text "Clear min players"}}
                         :desc
                         {:fx/type :button
                          :on-action {:event/type ::dissoc
                                      :key :filter-replay-min-players}
                          :graphic
                          {:fx/type font-icon/lifecycle
                           :icon-literal "mdi-close:16:white"}}}]))}
                  {:fx/type :h-box
                   :alignment :center-left
                   :children
                   (concat
                     [{:fx/type :label
                       :text " Max Players: "}
                      {:fx/type :combo-box
                       :value filter-replay-max-players
                       :on-value-changed {:event/type ::assoc
                                          :key :filter-replay-max-players}
                       :items (concat [nil] num-players)}]
                     [{:fx/type :label
                       :text " Min Avg Skill: "}
                      {:fx/type :text-field
                       :style {:-fx-max-width 60}
                       :text-formatter
                       {:fx/type :text-formatter
                        :value-converter :integer
                        :value (int (or filter-replay-min-skill 0))
                        :on-value-changed {:event/type ::assoc
                                           :key :filter-replay-min-skill}}}]
                     (when filter-replay-max-players
                       [{:fx/type fx.ext.node/with-tooltip-props
                         :props
                         {:tooltip
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
                           :text "Clear max players"}}
                         :desc
                         {:fx/type :button
                          :on-action {:event/type ::dissoc
                                      :key :filter-replay-max-players}
                          :graphic
                          {:fx/type font-icon/lifecycle
                           :icon-literal "mdi-close:16:white"}}}]))}
                  {:fx/type :h-box
                   :alignment :center-left
                   :children
                   [{:fx/type :check-box
                     :selected (boolean replays-window-details)
                     :h-box/margin 8
                     :on-selected-changed {:event/type ::assoc
                                           :key :replays-window-details}}
                    {:fx/type :label
                     :text "Detailed table "}]}
                  (let [refreshing (boolean (seq refresh-tasks))]
                    {:fx/type :button
                     :text (if refreshing
                             " Refreshing... "
                             " Refresh ")
                     :on-action {:event/type ::add-task
                                 :task {::task-type ::refresh-replays}}
                     :disable refreshing
                     :graphic
                     {:fx/type font-icon/lifecycle
                      :icon-literal "mdi-refresh:16:white"}})]
                (let [downloading (boolean (seq index-downloads-tasks))
                      page (u/to-number bar-replays-page)]
                  [{:fx/type :button
                    :text (if downloading
                            " Getting Online BAR Replays... "
                            " Get Online BAR Replays")
                    :on-action {:event/type ::add-task
                                :task {::task-type ::download-bar-replays
                                       :page page}}
                    :disable downloading
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal "mdi-download:16:white"}}
                   {:fx/type :label
                    :text " Page: "}
                   {:fx/type :text-field
                    :text (str page)
                    :style {:-fx-max-width 56}
                    :on-text-changed {:event/type ::assoc
                                      :key :bar-replays-page}}
                   {:fx/type :label
                    :text (str (when new-online-replays-count
                                 (str " Got " new-online-replays-count " new")))}]))}
              {:fx/type :pane
               :h-box/hgrow :sometimes}
              {:fx/type :button
               :text "Settings"
               :on-action {:event/type ::toggle
                           :key :show-settings-window}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-settings:16:white"}}]}
            (if all-replays
              (if (empty? all-replays)
                {:fx/type :label
                 :style {:-fx-font-size 24}
                 :text " No replays"}
                {:fx/type fx.ext.table-view/with-selection-props
                 :v-box/vgrow :always
                 :props {:selection-mode :single
                         :on-selected-item-changed {:event/type ::select-replay}
                         :selected-item selected-replay}
                 :desc
                 {:fx/type ext-recreate-on-key-changed
                  :key (str replays-window-details)
                  :desc
                  {:fx/type :table-view
                   :column-resize-policy :constrained ; TODO auto resize
                   :items replays
                   :columns
                   (concat
                     (when replays-window-details
                       [{:fx/type :table-column
                         :text "Source"
                         :cell-value-factory :source-name
                         :cell-factory
                         {:fx/cell-type :table-cell
                          :describe
                          (fn [source]
                            {:text (str source)})}}
                        {:fx/type :table-column
                         :text "Filename"
                         :cell-value-factory #(-> % :file fs/filename)
                         :cell-factory
                         {:fx/cell-type :table-cell
                          :describe
                          (fn [filename]
                            {:text (str filename)})}}])
                     [
                      {:fx/type :table-column
                       :text "Map"
                       :cell-value-factory #(-> % :body :script-data :game :mapname)
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [map-name]
                          {:text (str map-name)})}}
                      {:fx/type :table-column
                       :text "Game"
                       :cell-value-factory #(-> % :body :script-data :game :gametype)
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [mod-name]
                          {:text (str mod-name)})}}
                      {:fx/type :table-column
                       :text "Timestamp"
                       :cell-value-factory #(some-> % :header :unix-time (* 1000))
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [unix-time]
                          (let [ts (when unix-time
                                     (java-time/format
                                       (LocalDateTime/ofInstant
                                         (java-time/instant unix-time)
                                         time-zone-id)))]
                            {:text (str ts)}))}}
                      {:fx/type :table-column
                       :text "Type"
                       :cell-value-factory #(some-> % :game-type name)
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [game-type]
                          {:text (str game-type)})}}
                      {:fx/type :table-column
                       :text "Player Counts"
                       :cell-value-factory :player-counts
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [player-counts]
                          {:text (->> player-counts (string/join "v"))})}}
                      {:fx/type :table-column
                       :text "Skill Min"
                       :cell-value-factory (comp min-skill replay-skills)
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [min-skill]
                          {:text (str min-skill)})}}
                      {:fx/type :table-column
                       :text "Skill Avg"
                       :cell-value-factory (comp average-skill replay-skills)
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [avg-skill]
                          {:text (str avg-skill)})}}
                      {:fx/type :table-column
                       :text "Skill Max"
                       :cell-value-factory (comp max-skill replay-skills)
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [max-skill]
                          {:text (str max-skill)})}}]
                     (when replays-window-details
                       [{:fx/type :table-column
                         :text "Engine"
                         :cell-value-factory #(-> % :header :engine-version)
                         :cell-factory
                         {:fx/cell-type :table-cell
                          :describe
                          (fn [engine-version]
                            {:text (str engine-version)})}}
                        {:fx/type :table-column
                         :text "Size"
                         :cell-value-factory :file-size
                         :cell-factory
                         {:fx/cell-type :table-cell
                          :describe
                          (fn [file-size]
                            {:text (u/format-bytes file-size)})}}])
                     [{:fx/type :table-column
                       :text "Duration"
                       :cell-value-factory #(-> % :header :game-time)
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [game-time]
                          (let [duration (when game-time (java-time/duration game-time :seconds))
                                ; https://stackoverflow.com/a/44343699/984393
                                formatted (when duration
                                            (format "%d:%02d:%02d"
                                              (.toHours duration)
                                              (.toMinutesPart duration)
                                              (.toSecondsPart duration)))]
                            {:text (str formatted)}))}}
                      {:fx/type :table-column
                       :text "Watched"
                       :sortable false
                       :cell-value-factory identity
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [{:keys [file]}]
                          (let [path (fs/canonical-path file)]
                            {:text ""
                             :graphic
                             {:fx/type ext-recreate-on-key-changed
                              :key (str path)
                              :desc
                              {:fx/type :check-box
                               :selected (boolean (get replays-watched path))
                               :on-selected-changed {:event/type ::assoc-in
                                                     :path [:replays-watched path]}}}}))}}
                      {:fx/type :table-column
                       :text "Watch"
                       :sortable false
                       :cell-value-factory identity
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [i]
                          (let [engine-version (-> i :header :engine-version)
                                matching-engine (get engines-by-version engine-version)
                                engine-downloadable (get replay-downloads-by-engine engine-version)
                                mod-version (-> i :body :script-data :game :gametype)
                                matching-mod (get mods-by-version mod-version)
                                mod-downloadable (get replay-downloads-by-mod mod-version)
                                mod-importable (get replay-imports-by-mod mod-version)
                                mod-rapid (get rapid-data-by-version mod-version)
                                map-name (-> i :body :script-data :game :mapname)
                                matching-map (get maps-by-version map-name)
                                map-downloadable (get replay-downloads-by-map map-name)
                                map-importable (get replay-imports-by-map map-name)
                                mod-rapid-download (get rapid-download (:id mod-rapid))]
                            {:text ""
                             :graphic
                             (cond
                               (:id i) ; BAR online replay
                               (let [fileName (:fileName i)
                                     download-url (when fileName (http/bar-replay-download-url fileName))
                                     {:keys [running] :as download} (get http-download download-url)
                                     in-progress (or running
                                                     (contains? download-tasks (:id i)))]
                                 {:fx/type :button
                                  :text
                                  (if in-progress
                                    (str (u/download-progress download))
                                    " Download replay")
                                  :disable (boolean in-progress)
                                  :on-action {:event/type ::add-task
                                              :task {::task-type ::download-bar-replay
                                                     :id (:id i)
                                                     :spring-isolation-dir spring-isolation-dir}}
                                  :graphic
                                  {:fx/type font-icon/lifecycle
                                   :icon-literal "mdi-download:16:white"}})
                               (and matching-engine matching-mod matching-map)
                               {:fx/type :button
                                :text " Watch"
                                :on-action
                                {:event/type ::watch-replay
                                 :engines engines
                                 :engine-version engine-version
                                 :replay i
                                 :spring-isolation-dir spring-isolation-dir}
                                :graphic
                                {:fx/type font-icon/lifecycle
                                 :icon-literal "mdi-movie:16:white"}}
                               (and (not matching-engine) update-engines)
                               {:fx/type :button
                                :text " Engines updating..."
                                :disable true}
                               (and (not matching-engine) engine-downloadable)
                               (let [source (resource/resource-dest spring-isolation-dir engine-downloadable)]
                                 (if (fs/file-exists? file-cache source)
                                   (let [dest (io/file spring-isolation-dir "engine"
                                                       (fs/without-extension
                                                         (:resource-filename engine-downloadable)))
                                         in-progress (boolean
                                                       (or (get extracting (fs/canonical-path source))
                                                           (contains? extract-tasks (fs/canonical-path source))))]
                                     {:fx/type :button
                                      :text (if in-progress "Extracting..." " Extract engine")
                                      :disable in-progress
                                      :graphic
                                      {:fx/type font-icon/lifecycle
                                       :icon-literal "mdi-archive:16:white"}
                                      :on-action
                                      {:event/type ::add-task
                                       :task
                                       {::task-type ::extract-7z
                                        :file source
                                        :dest dest}}})
                                   (let [{:keys [download-url]} engine-downloadable
                                         {:keys [running] :as download} (get http-download download-url)]
                                     {:fx/type :button
                                      :text (if running
                                              (str (u/download-progress download))
                                              " Download engine")
                                      :disable (boolean running)
                                      :on-action {:event/type ::add-task
                                                  :task {::task-type ::http-downloadable
                                                         :downloadable engine-downloadable
                                                         :spring-isolation-dir spring-isolation-dir}}
                                      :graphic
                                      {:fx/type font-icon/lifecycle
                                       :icon-literal "mdi-download:16:white"}})))
                               (:running mod-rapid-download)
                               {:fx/type :button
                                :text (str (u/download-progress mod-rapid-download))
                                :disable true}
                               (and (not matching-mod) update-mods)
                               {:fx/type :button
                                :text " Games updating..."
                                :disable true}
                               (and (not matching-mod) mod-importable)
                               (let [{:keys [resource-file]} mod-importable
                                     resource-path (fs/canonical-path resource-file)
                                     in-progress (boolean
                                                   (or (-> copying (get resource-path) :status boolean)
                                                       (contains? import-tasks resource-path)))]
                                 {:fx/type :button
                                  :text (if in-progress
                                          " Importing..."
                                          " Import game")
                                  :disable in-progress
                                  :on-action {:event/type ::add-task
                                              :task
                                              {::task-type ::import
                                               :importable mod-importable
                                               :spring-isolation-dir spring-isolation-dir}}
                                  :graphic
                                  {:fx/type font-icon/lifecycle
                                   :icon-literal "mdi-content-copy:16:white"}})
                               (and (not matching-mod) mod-rapid matching-engine)
                               {:fx/type :button
                                :text (str " Download game")
                                :on-action {:event/type ::add-task
                                            :task
                                            {::task-type ::rapid-download
                                             :engine-file (:file matching-engine)
                                             :rapid-id (:id mod-rapid)
                                             :spring-isolation-dir spring-isolation-dir}}
                                :graphic
                                {:fx/type font-icon/lifecycle
                                 :icon-literal "mdi-download:16:white"}}
                               (and (not matching-mod) mod-downloadable)
                               (let [{:keys [download-url]} mod-downloadable
                                     {:keys [running] :as download} (get http-download download-url)]
                                 {:fx/type :button
                                  :text (if running
                                          (str (u/download-progress download))
                                          " Download game")
                                  :disable (boolean running)
                                  :on-action {:event/type ::add-task
                                              :task {::task-type ::http-downloadable
                                                     :downloadable mod-downloadable
                                                     :spring-isolation-dir spring-isolation-dir}}
                                  :graphic
                                  {:fx/type font-icon/lifecycle
                                   :icon-literal "mdi-download:16:white"}})
                               (and (not matching-map) update-maps)
                               {:fx/type :button
                                :text " Maps updating..."
                                :disable true}
                               (and (not matching-map) map-importable)
                               (let [{:keys [resource-file]} map-importable
                                     resource-path (fs/canonical-path resource-file)
                                     in-progress (boolean
                                                   (or (-> copying (get resource-path) :status boolean)
                                                       (contains? import-tasks resource-path)))]
                                 {:fx/type :button
                                  :text (if in-progress
                                          " Importing..."
                                          " Import map")
                                  :tooltip
                                  {:fx/type :tooltip
                                   :show-delay [10 :ms]
                                   :text (str (:resource-file map-importable))}
                                  :disable in-progress
                                  :on-action
                                  {:event/type ::add-task
                                   :task
                                   {::task-type ::import
                                    :importable map-importable
                                    :spring-isolation-dir spring-isolation-dir}}
                                  :graphic
                                  {:fx/type font-icon/lifecycle
                                   :icon-literal "mdi-content-copy:16:white"}})
                               (and (not matching-map) map-downloadable)
                               (let [{:keys [download-url]} map-downloadable
                                     {:keys [running] :as download} (get http-download download-url)]
                                 {:fx/type :button
                                  :text (if running
                                          (str (u/download-progress download))
                                          " Download map")
                                  :disable (boolean running)
                                  :on-action
                                  {:event/type ::add-task
                                   :task
                                   {::task-type ::http-downloadable
                                    :downloadable map-downloadable
                                    :spring-isolation-dir spring-isolation-dir}}
                                  :graphic
                                  {:fx/type font-icon/lifecycle
                                   :icon-literal "mdi-download:16:white"}})
                               (not matching-engine)
                               {:fx/type :label
                                :text " No engine"}
                               (not matching-mod)
                               {:fx/type :button
                                :text (if rapid-update
                                        " Updating rapid..."
                                        " Update rapid")
                                :disable (boolean rapid-update)
                                :on-action {:event/type ::add-task
                                            :task {::task-type ::update-rapid}}
                                :graphic
                                {:fx/type font-icon/lifecycle
                                 :icon-literal "mdi-refresh:16:white"}}
                               (not matching-map)
                               {:fx/type :button
                                :text " No map, update downloads"
                                :on-action
                                {:event/type ::add-task
                                 :task
                                 {::task-type ::fn
                                  :description "update downloadables"
                                  :function
                                  (fn []
                                    (update-download-source
                                      (assoc springfiles-maps-download-source :force true)))}}})}))}}])}}})
             {:fx/type :label
              :style {:-fx-font-size 24}
              :text " Loading replays..."})]
           (when selected-replay
             (let [script-data (-> selected-replay :body :script-data)
                   {:keys [gametype mapname] :as game} (:game script-data)
                   teams-by-id (->> game
                                    (filter (comp #(string/starts-with? % "team") name first))
                                    (map
                                      (fn [[teamid team]]
                                        (let [[_all id] (re-find #"team(\d+)" (name teamid))]
                                          [id team])))
                                    (into {}))
                   sides (spring/mod-sides replay-mod-details)
                   players (->> game
                                (filter (comp #(string/starts-with? % "player") name first))
                                (map
                                  (fn [[playerid {:keys [spectator team] :as player}]]
                                    (let [[_all id] (re-find #"player(\d+)" (name playerid))
                                          {:keys [allyteam handicap rgbcolor side] :as team} (get teams-by-id (str team))
                                          team-color (try (spring-script-color-to-int rgbcolor)
                                                          (catch Exception e
                                                            (log/debug e "Error parsing color")
                                                            0))
                                          side-id-by-name (clojure.set/map-invert sides)]
                                      (-> player
                                          (clojure.set/rename-keys
                                            {:name :username
                                             :countrycode :country})
                                          (assoc :battle-status
                                                 {:id id
                                                  :team team
                                                  :mode (not (to-bool spectator))
                                                  :handicap handicap
                                                  :side (get side-id-by-name side)
                                                  :ally allyteam}
                                                 :team-color team-color))))))
                   bots (->> game
                             (filter (comp #(string/starts-with? % "ai") name first))
                             (map
                               (fn [[aiid {:keys [team] :as ai}]]
                                 (let [{:keys [allyteam handicap rgbcolor side] :as team} (get teams-by-id (str team))
                                       team-color (try (spring-script-color-to-int rgbcolor)
                                                       (catch Exception e
                                                         (log/debug e "Error parsing color")
                                                         0))
                                       side-id-by-name (clojure.set/map-invert sides)]
                                   (-> ai
                                       (clojure.set/rename-keys
                                         {:name :username})
                                       (assoc :battle-status
                                              {:id aiid
                                               :team team
                                               :mode true
                                               :handicap handicap
                                               :side (get side-id-by-name side)
                                               :ally allyteam}
                                              :team-color team-color))))))]
               [{:fx/type :h-box
                 :alignment :center-left
                 :children
                 [
                  {:fx/type :v-box
                   :h-box/hgrow :always
                   :children
                   (concat
                     [{:fx/type fx.players-table/players-table
                       :v-box/vgrow :always
                       :am-host false
                       :battle-modname gametype
                       :battle-players-color-allyteam battle-players-color-allyteam
                       :players (concat players bots)
                       :sides sides}
                      {:fx/type :h-box
                       :alignment :center-left
                       :children
                       [{:fx/type :check-box
                         :selected (boolean battle-players-color-allyteam)
                         :on-selected-changed {:event/type ::assoc
                                               :key :battle-players-color-allyteam}}
                        {:fx/type :label
                         :text " Color player name by allyteam"}]}]
                     (when (and selected-matching-engine selected-matching-mod selected-matching-map)
                       (let [watch-button {:fx/type :button
                                           :style {:-fx-font-size 24}
                                           :text " Watch"
                                           :on-action
                                           {:event/type ::watch-replay
                                            :engines engines
                                            :engine-version selected-engine-version
                                            :replay selected-replay
                                            :spring-isolation-dir spring-isolation-dir}
                                           :graphic
                                           {:fx/type font-icon/lifecycle
                                            :icon-literal "mdi-movie:24:white"}}]
                         [{:fx/type :h-box
                           :children
                           [watch-button
                            {:fx/type :pane
                             :h-box/hgrow :always}
                            watch-button]}])))}
                  {:fx/type :v-box
                   :children
                   [
                    {:fx/type fx.minimap/minimap-pane
                     :map-name mapname
                     :map-details replay-map-details
                     :minimap-type replay-minimap-type
                     :minimap-type-key :replay-minimap-type
                     :scripttags script-data}
                    {:fx/type :h-box
                     :alignment :center-left
                     :children
                     [{:fx/type :label
                       :text (str " Size: "
                                  (when-let [{:keys [map-width map-height]} (-> replay-map-details :smf :header)]
                                    (str
                                      (when map-width (quot map-width 64))
                                      " x "
                                      (when map-height (quot map-height 64)))))}
                      {:fx/type :pane
                       :h-box/hgrow :always}
                      {:fx/type :combo-box
                       :value replay-minimap-type
                       :items minimap-types
                       :on-value-changed {:event/type ::assoc
                                          :key :replay-minimap-type}}]}]}]}])))}
        {:fx/type :pane})}}))

(defn- maps-window
  [{:keys [filter-maps-name maps on-change-map show-maps]}]
  (let [{:keys [width height]} (screen-bounds)]
    {:fx/type :stage
     :showing (boolean show-maps)
     :title (str u/app-name " Maps")
     :icons icons
     :on-close-request (fn [^javafx.stage.WindowEvent e]
                         (swap! *state assoc :show-maps false)
                         (.consume e))
     :width (min download-window-width width)
     :height (min download-window-height height)
     :scene
     {:fx/type :scene
      :stylesheets stylesheets
      :root
      (if show-maps
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
          {:fx/type :scroll-pane
           :fit-to-width true
           :content
           {:fx/type :flow-pane
            :vgap 5
            :hgap 5
            :padding 5
            :children
            (map
              (fn [{:keys [map-name]}]
                {:fx/type :button
                 :style
                 {:-fx-min-width map-browse-image-size
                  :-fx-max-width map-browse-image-size
                  :-fx-min-height map-browse-box-height
                  :-fx-max-height map-browse-box-height}
                 :on-action {:event/type ::map-window-action
                             :on-change-map (assoc on-change-map :map-name map-name :value map-name)}
                 :graphic
                 {:fx/type :v-box
                  :children
                  [{:fx/type :image-view
                    :image {:url (-> map-name fs/minimap-image-cache-file io/as-url str)
                            :background-loading true}
                    :fit-width map-browse-image-size
                    :fit-height map-browse-image-size
                    :preserve-ratio true}
                   {:fx/type :pane
                    :v-box/vgrow :always}
                   {:fx/type :label
                    :style {:-fx-font-size 16}
                    :text (str " " map-name)
                    :wrap-text true}]}})
              (let [filter-lc ((fnil string/lower-case "") filter-maps-name)]
                (->> maps
                     (filter (fn [{:keys [map-name]}]
                               (and map-name
                                    (string/includes? (string/lower-case map-name) filter-lc))))
                     (sort-by :map-name))))}}]}
        {:fx/type :pane})}}))

(defn- main-window-on-close-request
  [client standalone e]
  (log/debug "Main window close request" e)
  (when standalone
    (loop []
      (if (and client (not (s/closed? client)))
        (do
          (client/disconnect client)
          (recur))
        (System/exit 0)))))

(defmethod event-handler ::my-channels-tab-action [e]
  (log/info e))

(defmethod event-handler ::send-message [{:keys [channel-name client message]}]
  (future
    (try
      (swap! *state update :message-drafts dissoc channel-name)
      (cond
        (string/blank? channel-name)
        (log/info "Skipping message" (pr-str message) "to empty channel" (pr-str channel-name))
        (string/blank? message)
        (log/info "Skipping empty message" (pr-str message) "to" (pr-str channel-name))
        :else
        (let [[private-message username] (re-find #"^@(.*)$" channel-name)]
          (if-let [[_all message] (re-find #"^/me (.*)$" message)]
            (if private-message
              (send-message client (str "SAYPRIVATEEX " username " " message))
              (send-message client (str "SAYEX " channel-name " " message)))
            (if private-message
              (send-message client (str "SAYPRIVATE " username " " message))
              (send-message client (str "SAY " channel-name " " message))))))
      (catch Exception e
        (log/error e "Error sending message" message "to channel" channel-name)))))


(defmethod event-handler ::selected-item-changed-channel-tabs [{:fx/keys [^Tab event]}]
  (swap! *state assoc :selected-tab-channel (.getId event)))

(defn- focus-text-field [^Tab tab]
  (when-let [content (.getContent tab)]
    (let [^Node text-field (-> content (.lookupAll "#channel-text-field") first)]
      (log/info "Found text field" (.getId text-field))
      (Platform/runLater
        (fn []
          (.requestFocus text-field))))))

(def my-channels-view-keys
  [:channels :chat-auto-scroll :client :message-drafts :my-channels :selected-tab-channel])

(defn- my-channels-view
  [{:keys [channels chat-auto-scroll client message-drafts my-channels selected-tab-channel]}]
  (let [my-channel-names (->> my-channels
                              keys
                              (remove u/battle-channel-name?)
                              sort)
        selected-index (if (contains? (set my-channel-names) selected-tab-channel)
                         (.indexOf ^List my-channel-names selected-tab-channel)
                         0)]
    (if (seq my-channel-names)
      {:fx/type fx.ext.tab-pane/with-selection-props
       :props
       {:on-selected-item-changed {:event/type ::selected-item-changed-channel-tabs}
        :selected-index selected-index}
       :desc
       {:fx/type :tab-pane
        :on-tabs-changed {:event/type ::my-channels-tab-action}
        :style {:-fx-font-size 16}
        :tabs
        (map
          (fn [channel-name]
            {:fx/type :tab
             :graphic {:fx/type :label
                       :text (str channel-name)}
             :id channel-name
             :closable (not (u/battle-channel-name? channel-name))
             :on-close-request {:event/type ::leave-channel
                                :channel-name channel-name
                                :client client}
             :on-selection-changed (fn [^Event ev] (focus-text-field (.getTarget ev)))
             :content
             {:fx/type fx.channel/channel-view
              :channel-name channel-name
              :channels channels
              :chat-auto-scroll chat-auto-scroll
              :client client
              :message-draft (get message-drafts channel-name)}})
          my-channel-names)}}
      {:fx/type :pane})))


(defmethod event-handler ::selected-item-changed-main-tabs [{:fx/keys [^Tab event]}]
  (swap! *state assoc :selected-tab-main (.getId event)))

(defmethod event-handler ::send-console [{:keys [client message]}]
  (future
    (try
      (swap! *state dissoc :console-message-draft)
      (when-not (string/blank? message)
        (send-message client message))
      (catch Exception e
        (log/error e "Error sending message" message "to server")))))

(def channels-table-keys
  [:channels :client :my-channels])

(def main-tab-ids
  ["battles" "chat" "console"])
(def main-tab-id-set (set main-tab-ids))

(def main-tab-view-keys
  [:battles :client :channels :console-auto-scroll :console-log :console-message-draft :join-channel-name
   :selected-tab-main :users])

(defn- main-tab-view
  [{:keys [battles client channels console-auto-scroll console-log console-message-draft join-channel-name
           selected-tab-main users]
    :as state}]
  (let [selected-index (if (contains? (set main-tab-ids) selected-tab-main)
                         (.indexOf ^List main-tab-ids selected-tab-main)
                         0)
        users-view {:fx/type :v-box
                    :children
                    [{:fx/type :label
                      :text (str "Users (" (count users) ")")}
                     (merge
                       {:fx/type users-table
                        :v-box/vgrow :always}
                       (select-keys state users-table-keys))]}]
    {:fx/type fx.ext.tab-pane/with-selection-props
     :props
     (merge
       {:on-selected-item-changed {:event/type ::selected-item-changed-main-tabs}}
       (when (< selected-index (count main-tab-ids))
         {:selected-index selected-index}))
     :desc
     {:fx/type :tab-pane
      :style {:-fx-font-size 16
              :-fx-min-height 200
              :-fx-pref-height 300}
      :tabs
      [{:fx/type :tab
        :graphic {:fx/type :label
                  :text "Battles"}
        :closable false
        :id "battles"
        :content
        (if (= 0 selected-index)
          {:fx/type :split-pane
           :divider-positions [0.80]
           :items
           [
            {:fx/type :v-box
             :children
             [{:fx/type :label
               :text (str "Battles (" (count battles) ")")}
              (merge
                {:fx/type battles-table
                 :v-box/vgrow :always}
                (select-keys state battles-table-keys))]}
            users-view]}
          {:fx/type :pane})}
       {:fx/type :tab
        :graphic {:fx/type :label
                  :text "Chat"}
        :closable false
        :id "chat"
        :content
        (if (= 1 selected-index)
          {:fx/type :split-pane
           :divider-positions [0.70 0.9]
           :items
           [(merge
              {:fx/type my-channels-view}
              (select-keys state my-channels-view-keys))
            users-view
            {:fx/type :v-box
             :children
             [{:fx/type :label
               :text (str "Channels (" (->> channels vals non-battle-channels count) ")")}
              (merge
                {:fx/type channels-table
                 :v-box/vgrow :always}
                (select-keys state channels-table-keys))
              {:fx/type :h-box
               :alignment :center-left
               :children
               [{:fx/type :label
                 :text " Custom Channel: "}
                {:fx/type :text-field
                 :text join-channel-name
                 :prompt-text "Name"
                 :on-text-changed {:event/type ::assoc
                                   :key :join-channel-name}
                 :on-action {:event/type ::join-channel
                             :channel-name join-channel-name
                             :client client}}
                {:fx/type :button
                 :text "Join"
                 :on-action {:event/type ::join-channel
                             :channel-name join-channel-name
                             :client client}}]}]}]}
          {:fx/type :pane})}
       {:fx/type :tab
        :graphic {:fx/type :label
                  :text "Console"}
        :closable false
        :id "console"
        :content
        (if (= 2 selected-index)
          (let [time-zone-id (.toZoneId (TimeZone/getDefault))
                console-text (string/join "\n"
                               (map
                                 (fn [{:keys [message source timestamp]}]
                                   (str (u/format-hours time-zone-id timestamp)
                                        (case source
                                          :server " < "
                                          :client " > "
                                          " ")
                                        message))
                                 (reverse console-log)))]
            {:fx/type :v-box
             :children
             [{:fx/type with-scroll-text-prop
               :v-box/vgrow :always
               :props {:scroll-text [console-text console-auto-scroll]}
               :desc
               {:fx/type :text-area
                :editable false
                :wrap-text true
                :style {:-fx-font-family monospace-font-family}}}
              {:fx/type :h-box
               :alignment :center-left
               :children
               [{:fx/type :button
                 :text "Send"
                 :on-action {:event/type ::send-console
                             :client client
                             :message console-message-draft}}
                {:fx/type :text-field
                 :h-box/hgrow :always
                 :text (str console-message-draft)
                 :on-text-changed {:event/type ::assoc
                                   :key :console-message-draft}
                 :on-action {:event/type ::send-console
                             :client client
                             :message console-message-draft}}
                {:fx/type fx.ext.node/with-tooltip-props
                 :props
                 {:tooltip
                  {:fx/type :tooltip
                   :show-delay [10 :ms]
                   :text "Auto scroll"}}
                 :desc
                 {:fx/type :h-box
                  :alignment :center-left
                  :children
                  [
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-autorenew:20:white"}
                   {:fx/type :check-box
                    :selected (boolean console-auto-scroll)
                    :on-selected-changed {:event/type ::assoc
                                          :key :console-auto-scroll}}]}}]}]})
          {:fx/type :pane})}]}}))

(def tasks-window-keys
  [:current-tasks :show-tasks-window :tasks])

(defn- tasks-window [{:keys [current-tasks show-tasks-window tasks]}]
  {:fx/type :stage
   :showing (boolean show-tasks-window)
   :title (str u/app-name " Tasks")
   :icons icons
   :on-close-request (fn [^Event e]
                       (swap! *state assoc :show-tasks-window false)
                       (.consume e))
   :width 1200
   :height 1000
   :scene
   {:fx/type :scene
    :stylesheets stylesheets
    :root
    (if show-tasks-window
      {:fx/type :v-box
       :style {:-fx-font-size 16}
       :children
       [{:fx/type :label
         :text "Workers"
         :style {:-fx-font-size 24}}
        {:fx/type :v-box
         :alignment :center-left
         :children
         (map
           (fn [[k v]]
             {:fx/type :h-box
              :children
              [{:fx/type :label
                :style {:-fx-font-size 20}
                :text (str " Priority " k ": ")}
               {:fx/type :label
                :text (str (::task-type v))}]})
           current-tasks)}
        {:fx/type :label
         :text "Task Queue"
         :style {:-fx-font-size 24}}
        {:fx/type :table-view
         :v-box/vgrow :always
         :column-resize-policy :constrained
         :items (or (seq tasks) [])
         :columns
         [
          {:fx/type :table-column
           :text "Type"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (::task-type i))})}}
          {:fx/type :table-column
           :text "Priority"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (or (::task-priority i) default-task-priority))})}}
          {:fx/type :table-column
           :text "Keys"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [i]
              {:text (str (keys i))})}}]}]}
      {:fx/type :pane})}})

(def matchmaking-window-keys
  [:client :matchmaking-queues :show-matchmaking-window])

(defn- matchmaking-window [{:keys [client matchmaking-queues show-matchmaking-window]}]
  {:fx/type :stage
   :showing (boolean show-matchmaking-window)
   :title (str u/app-name " Matchmaking")
   :icons icons
   :on-close-request (fn [^Event e]
                       (swap! *state assoc :show-matchmaking-window false)
                       (.consume e))
   :width 600
   :height 700
   :scene
   {:fx/type :scene
    :stylesheets stylesheets
    :root
    (if show-matchmaking-window
      {:fx/type :v-box
       :style {:-fx-font-size 16}
       :children
       [{:fx/type :button
         :text "List All Queues"
         :on-action (fn [_e] (send-message client "c.matchmaking.list_all_queues"))}
        {:fx/type :button
         :text "List My Queues"
         :on-action (fn [_e] (send-message client "c.matchmaking.list_my_queues"))}
        {:fx/type :button
         :text "Leave All Queues"
         :on-action (fn [_e] (send-message client "c.matchmaking.leave_all_queues"))}
        {:fx/type :label
         :text "Queues"
         :style {:-fx-font-size 24}}
        {:fx/type :table-view
         :v-box/vgrow :always
         :style {:-fx-font-size 16}
         :column-resize-policy :constrained ; TODO auto resize
         :items (or (sort-by first matchmaking-queues)
                    [])
         :columns
         [{:fx/type :table-column
           :text "Queue"
           :cell-value-factory (comp :queue-name second)
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [queue-name] {:text (str queue-name)})}}
          {:fx/type :table-column
           :text "Current Search Time"
           :cell-value-factory (comp :current-search-time second)
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [current-search-time] {:text (str current-search-time)})}}
          {:fx/type :table-column
           :text "Current Size"
           :cell-value-factory (comp :current-size second)
           :cell-factory
           {:fx/cell-type :table-cell
            :describe (fn [current-size] {:text (str current-size)})}}
          {:fx/type :table-column
           :text "Actions"
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [[queue-id {:keys [am-in ready-check]}]]
              {:text ""
               :graphic
               {:fx/type :h-box
                :children
                (concat
                  [{:fx/type :button
                    :text (cond
                            ready-check "Ready"
                            am-in "Leave"
                            :else "Join")
                    :on-action
                    (fn [_e]
                      (send-message client
                        (if ready-check
                          "c.matchmaking.ready"
                          (str
                            (if am-in
                              "c.matchmaking.leave_queue"
                              "c.matchmaking.join_queue")
                            " " queue-id)))
                      (when ready-check
                        (swap! *state assoc-in [:matchmaking-queues queue-id :ready-check] false)))}]
                  (when ready-check
                    [{:fx/type :button
                      :text "Decline"
                      :on-action
                      (fn [_e]
                        (send-message client "c.matchmaking.decline")
                        (swap! *state assoc-in [:matchmaking-queues queue-id :ready-check] false))}]))}})}}]}]}
      {:fx/type :pane})}})

(defn- root-view
  [{{:keys [agreement battle client current-tasks last-failed-message password pop-out-battle
            selected-tab-main standalone tasks username verification-code]
     :as state}
    :state}]
  (let [{:keys [width height]} (screen-bounds)
        all-tasks (filter some? (concat tasks (vals current-tasks)))
        tasks-by-type (group-by ::task-type all-tasks)
        selected-tab-main (get main-tab-ids selected-tab-main (first main-tab-ids))]
    {:fx/type fx/ext-many
     :desc
     [{:fx/type :stage
       :showing true
       :title (str "skylobby " app-version)
       :icons icons
       :x 100
       :y 100
       :width (min main-window-width width)
       :height (min main-window-height height)
       :on-close-request (partial main-window-on-close-request client standalone)
       :scene
       {:fx/type :scene
        :stylesheets stylesheets
        :root
        {:fx/type :v-box
         :style {:-fx-font-size 14}
         :alignment :top-left
         :children
         (concat
           [(merge
              {:fx/type client-buttons}
              (select-keys state client-buttons-keys))]
           (when agreement
             [{:fx/type :label
               :style {:-fx-font-size 20}
               :text " Server agreement: "}
              {:fx/type :text-area
               :editable false
               :text (str agreement)}
              {:fx/type :h-box
               :style {:-fx-font-size 20}
               :children
               [{:fx/type :text-field
                 :prompt-text "Email Verification Code"
                 :text verification-code
                 :on-text-changed {:event/type ::assoc
                                   :key :verification-code}}
                {:fx/type :button
                 :text "Confirm"
                 :on-action {:event/type ::confirm-agreement
                             :client client
                             :password password
                             :username username
                             :verification-code verification-code}}]}])
           [(merge
              {:fx/type main-tab-view
               :v-box/vgrow :always
               :selected-tab-main selected-tab-main}
              (select-keys state
                (concat main-tab-view-keys battles-table-keys my-channels-view-keys channels-table-keys)))
            (merge
              {:fx/type fx.battles-buttons/battles-buttons-view}
              (select-keys state fx.battles-buttons/battles-buttons-keys))]
           (when battle
             (if (:battle-id battle)
               (when (not pop-out-battle)
                 [(merge
                    {:fx/type fx.battle/battle-view
                     :tasks-by-type tasks-by-type}
                    (select-keys state fx.battle/battle-view-keys))])
               [{:fx/type :h-box
                 :alignment :top-left
                 :children
                 [{:fx/type :v-box
                   :h-box/hgrow :always
                   :children
                   [{:fx/type :label
                     :style {:-fx-font-size 20}
                     :text "Waiting for server to open battle..."}]}]}]))
           [{:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type :label
               :text (str last-failed-message)
               :style {:-fx-text-fill "#FF0000"}}
              {:fx/type :pane
               :h-box/hgrow :always}
              {:fx/type :button
               :text (str (count (seq tasks)) " tasks")
               :on-action {:event/type ::toggle
                           :key :show-tasks-window}}]}])}}}
      (let [show-battle-window (boolean (and battle pop-out-battle))]
        {:fx/type :stage
         :showing show-battle-window
         :title (str u/app-name " Battle")
         :icons icons
         :on-close-request {:event/type ::dissoc
                            :key :pop-out-battle}
         :width (min battle-window-width width)
         :height (min battle-window-height height)
         :scene
         {:fx/type :scene
          :stylesheets stylesheets
          :root
          (if show-battle-window
            (merge
              {:fx/type fx.battle/battle-view
               :tasks-by-type tasks-by-type}
              (select-keys state fx.battle/battle-view-keys))
            {:fx/type :pane})}})
      (merge
        {:fx/type download-window}
        (select-keys state download-window-keys))
      (merge
        {:fx/type import-window
         :tasks-by-type tasks-by-type}
        (select-keys state import-window-keys))
      (merge
        {:fx/type maps-window}
        (select-keys state
          [:filter-maps-name :maps :on-change-map :show-maps]))
      (merge
        {:fx/type rapid-download-window}
        (select-keys state rapid-download-window-keys))
      (merge
        {:fx/type replays-window
         :tasks-by-type tasks-by-type}
        (select-keys state replays-window-keys))
      (merge
        {:fx/type matchmaking-window}
        (select-keys state matchmaking-window-keys))
      (merge
        {:fx/type servers-window}
        (select-keys state servers-window-keys))
      (merge
        {:fx/type register-window}
        (select-keys state register-window-keys))
      (merge
        {:fx/type settings-window}
        (select-keys state settings-window-keys))
      (merge
        {:fx/type uikeys-window}
        (select-keys state
          [:filter-uikeys-action :selected-uikeys-action :show-uikeys-window :uikeys]))
      (merge
        {:fx/type tasks-window}
        (select-keys state tasks-window-keys))]}))


(defn- init
  "Things to do on program init, or in dev after a recompile."
  [state-atom]
  (log/info "Initializing periodic jobs")
  (let [low-tasks-chimer (tasks-chimer-fn state-atom 1)
        high-tasks-chimer (tasks-chimer-fn state-atom 3)
        update-channels-chimer (update-channels-chimer-fn state-atom)
        check-app-update-chimer (check-app-update-chimer-fn state-atom)]
    (add-watchers state-atom)
    (add-task! state-atom {::task-type ::reconcile-engines})
    (add-task! state-atom {::task-type ::reconcile-mods})
    (add-task! state-atom {::task-type ::reconcile-maps})
    (add-task! state-atom {::task-type ::refresh-replays})
    (add-task! state-atom {::task-type ::update-rapid})
    (event-handler {:event/type ::update-downloadables})
    (event-handler {:event/type ::scan-imports})
    (log/info "Finished periodic jobs init")
    {:chimers [low-tasks-chimer high-tasks-chimer
               update-channels-chimer check-app-update-chimer]}))

(defn init-async [state-atom]
  (future
    (log/info "Sleeping to let JavaFX start")
    (async/<!! (async/timeout wait-before-init-tasks-ms))
    (init state-atom)))

(defn -main [& _args]
  (u/log-to-file (fs/canonical-path (fs/config-file (str u/app-name ".log"))))
  (let [before (u/curr-millis)]
    (log/info "Main start")
    (Platform/setImplicitExit true)
    (log/info "Set JavaFX implicit exit")
    (future
      (log/info "Start 7Zip init, async")
      (fs/init-7z!)
      (log/info "Finished 7Zip init"))
    (let [before-state (u/curr-millis)
          _ (log/info "Loading initial state")
          state (assoc (initial-state) :standalone true)]
      (log/info "Loaded initial state in" (- (u/curr-millis) before-state) "ms")
      (reset! *state state))
    (log/info "Creating renderer")
    (let [r (fx/create-renderer
              :middleware (fx/wrap-map-desc
                            (fn [state]
                              {:fx/type root-view
                               :state state}))
              :opts {:fx.opt/map-event-handler event-handler})]
      (log/info "Mounting renderer")
      (fx/mount-renderer *state r))
    (init-async *state)
    (log/info "Main finished in" (- (u/curr-millis) before) "ms")))
