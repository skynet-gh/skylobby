(ns spring-lobby
  (:require
    [aleph.http :as aleph-http]
    [chime.core :as chime]
    [clj-http.client :as clj-http]
    [cljfx.css :as css]
    [clojure.core.async :as async]
    [clojure.core.cache :as cache]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.set]
    [clojure.string :as string]
    [crypto.random]
    [diehard.core :as dh]
    hashp.core
    java-time
    [manifold.deferred :as deferred]
    [manifold.stream :as s]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.params :refer [wrap-params]]
    [skylobby.color :as color]
    skylobby.fx
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.import :as fx.import]
    [skylobby.fx.minimap :as fx.minimap]
    [skylobby.fx.replay :as fx.replay]
    [skylobby.resource :as resource]
    [skylobby.task :as task]
    [spring-lobby.battle :as battle]
    [spring-lobby.client :as client]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.client.message :as message]
    [spring-lobby.client.util :as cu]
    [spring-lobby.fs :as fs]
    [spring-lobby.fs.sdfz :as replay]
    [spring-lobby.git :as git]
    [spring-lobby.http :as http]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte]
    [version-clj.core :as version])
  (:import
    (java.awt Desktop Desktop$Action)
    (java.io File)
    (java.net InetAddress InetSocketAddress URL)
    (java.util List)
    (javafx.event Event)
    (javafx.scene.control Tab)
    (javafx.scene.input KeyCode ScrollEvent)
    (javafx.stage DirectoryChooser)
    (manifold.stream SplicedStream))
  (:gen-class))


(set! *warn-on-reflection* true)


(declare limit-download-status)


(def app-version (u/app-version))

(def wait-before-init-tasks-ms 10000)

(dh/defratelimiter limit-download-status {:rate 1}) ; one update per second


(def start-pos-r 10.0)

(def minimap-size 512)


(def map-browse-image-size 162)
(def map-browse-box-height 224)


; https://github.com/clojure/clojure/blob/28efe345d5e995dc152a0286fb0be81443a0d9ac/src/clj/clojure/instant.clj#L274-L279
(defn- read-file-tag [cs]
  (io/file cs))
(defn- read-url-tag [spec]
  (URL. spec))

; https://github.com/clojure/clojure/blob/0754746f476c4ddf6a6b699d9547830b2fdad17c/src/clj/clojure/core.clj#L7755-L7761
(def custom-readers
  {'spring-lobby/java.io.File #'spring-lobby/read-file-tag
   'spring-lobby/java.net.URL #'spring-lobby/read-url-tag})

; https://stackoverflow.com/a/23592006/984393
(defmethod print-method File [f ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.io.File " (pr-str (fs/canonical-path f)))))
(defmethod print-method URL [url ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.net.URL " (pr-str (str url)))))


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


(defn- initial-file-events []
  (clojure.lang.PersistentQueue/EMPTY))


(def config-keys
  [:auto-get-resources :battle-title :battle-password :bot-name :bot-version :chat-auto-scroll
   :console-auto-scroll :css :engine-version :extra-import-sources :extra-replay-sources :filter-replay
   :filter-replay-type :filter-replay-max-players :filter-replay-min-players :filter-users :logins :map-name
   :mod-name :my-channels :password :pop-out-battle :preferred-color :rapid-repo
   :replays-watched :replays-window-details :server :servers :spring-isolation-dir :spring-settings :uikeys
   :username])


(defn- select-config [state]
  (select-keys state config-keys))

(defn- select-spring [state]
  (select-keys state [:by-spring-root]))

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
   {:select-fn select-spring
    :filename "spring.edn"}
   {:select-fn select-importables
    :filename "importables.edn"}
   {:select-fn select-downloadables
    :filename "downloadables.edn"}
   {:select-fn select-replays
    :filename "replays.edn"}])


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


(defn initial-state []
  (merge
    {:auto-get-resources true
     :battle-players-color-allyteam true
     :spring-isolation-dir (fs/default-isolation-dir)
     :servers default-servers}
    (apply
      merge
      (doall
        (map
          (comp slurp-config-edn :filename) state-to-edn)))
    (slurp-config-edn "parsed-replays.edn")
    {:tasks-by-kind {}
     :current-tasks (->> task/task-kinds (map (juxt identity (constantly nil))) (into {}))
     :file-events (initial-file-events)
     :minimap-type (first fx.minimap/minimap-types)
     :replay-minimap-type (first fx.minimap/minimap-types)
     :map-details (cache/fifo-cache-factory {} :threshold 8)
     :mod-details (cache/fifo-cache-factory {} :threshold 8)}))


(def ^:dynamic *state (atom {}))


(defn- client-message [{:keys [client] :as client-data} message]
  (message/send-message client message)
  (u/append-console-log *state (u/server-key client-data) :client message))


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


(defn- spit-state-config-to-edn [old-state new-state]
  (doseq [{:keys [select-fn filename] :as opts} state-to-edn]
    (try
      (let [old-data (select-fn old-state)
            new-data (select-fn new-state)]
        (when (not= old-data new-data)
          (u/try-log (str "update " filename)
            (spit-app-edn new-data filename opts))))
      (catch Exception e
        (log/error e "Error writing config edn" filename)))))


(defn- read-map-details [{:keys [map-name map-file]}]
  (let [log-map-name (str "'" map-name "'")]
    (u/try-log (str "reading map details for " log-map-name)
      (if map-file
        (fs/read-map-data map-file)
        (log/warn "No file found for map" log-map-name)))))


(defn- read-mod-data
  ([f]
   (read-mod-data f nil))
  ([f opts]
   (try
     (let [mod-data
           (if (string/ends-with? (fs/filename f) ".sdp")
             (rapid/read-sdp-mod f opts)
             (fs/read-mod-file f opts))
           mod-name (u/mod-name mod-data)]
       (assoc mod-data :mod-name mod-name))
     (catch Exception e
       (log/warn e "Error reading mod details")))))


(defn- update-mod [state-atom spring-root-path file]
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
    (swap! state-atom update-in [:by-spring-root spring-root-path :mods]
           (fn [mods]
             (set
               (cond->
                 (remove (comp #{path} fs/canonical-path :file) mods)
                 mod-details (conj mod-details)))))
    mod-data))


(defmulti event-handler :event/type)


; tasks by kind

(defn add-task! [state-atom task]
  (if task
    (let [task-kind (task/task-kind task)]
      (log/info "Adding task" (pr-str task) "to" task-kind)
      (swap! state-atom update-in [:tasks-by-kind task-kind]
        (fn [tasks]
          (set (conj tasks task)))))
    (log/warn "Attempt to add nil task" task)))

(defn add-multiple-tasks [tasks-by-kind new-tasks]
  (reduce-kv
    (fn [m k new-tasks]
      (update m k (fn [existing]
                    (set (concat new-tasks existing)))))
    tasks-by-kind
    (group-by task/task-kind new-tasks)))

(defn- add-tasks! [state-atom new-tasks]
  (log/info "Adding tasks" (pr-str new-tasks))
  (swap! state-atom update :tasks-by-kind add-multiple-tasks new-tasks))


(defn spring-roots [{:keys [spring-isolation-dir servers]}]
  (set
    (filter some?
      (concat
        [spring-isolation-dir]
        (map
          (comp fs/file :spring-isolation-dir second)
          servers)))))


(defn- battle-map-details-relevant-keys [state]
  (-> state
      (select-keys [:by-server :by-spring-root :current-tasks :map-details :servers :spring-isolation-dir :tasks])
      (update :by-server
        (fn [by-server]
          (reduce-kv
            (fn [m k v]
              (assoc m k
                (select-keys v [:client-data :battle :battles])))
            {}
            by-server)))))

(defn- battle-mod-details-relevant-data [state]
  (-> state
      (select-keys [:by-server :by-spring-root :current-tasks :mod-details :servers :spring-isolation-dir :tasks])
      (update :by-server
        (fn [by-server]
          (reduce-kv
            (fn [m k v]
              (assoc m k
                (select-keys v [:client-data :battle :battles])))
            {}
            by-server)))))

(defn- replay-map-and-mod-details-relevant-keys [state]
  (select-keys
    state
    [:by-spring-root :map-details :mod-details :online-bar-replays :parsed-replays-by-path
     :selected-replay-file :selected-replay-id :servers :spring-isolation-dir]))

(defn- fix-resource-relevant-keys [state]
  (select-keys
    state
    [:engine-version :engines :map-name :maps :mod-name :mods]))

(defn- auto-get-resources-relevant-keys [state]
  (select-keys
    state
    [:auto-get-resources
     :by-server :by-spring-root
     :current-tasks :downloadables-by-url :engines :file-cache :importables-by-path
     :rapid-data-by-version :servers :spring-isolation-dir :tasks]))

(defn- auto-get-resources-server-relevant-keys [state]
  (select-keys
    state
    [:battle :battles]))

(defn- fix-selected-server-relevant-keys [state]
  (select-keys
    state
    [:server :servers]))

(defn- update-battle-status-sync-relevant-data [state]
  (-> state
      (select-keys [:by-server :by-spring-root :mod-details :map-details :servers :spring-isolation-dir])
      (update :by-server
        (fn [by-server]
          (reduce-kv
            (fn [m k v]
              (assoc m k
                (select-keys v [:client-data :battle :battles])))
            {}
            by-server)))))



(defn server-auto-resources [_old-state new-state old-server new-server]
  (when (not= (auto-get-resources-server-relevant-keys old-server)
              (auto-get-resources-server-relevant-keys new-server))
    (try
      (when (:auto-get-resources new-state)
        (let [{:keys [current-tasks downloadables-by-url file-cache importables-by-path
                      rapid-data-by-version servers spring-isolation-dir tasks]} new-state
              {:keys [battle battles client-data]} new-server
              server-url (:server-url client-data)
              spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                              spring-isolation-dir)
              spring-root-path (fs/canonical-path spring-root)
              {:keys [engines maps mods]} (-> new-state :by-spring-root (get spring-root-path))
              old-battle-details (-> old-server :battles (get (-> old-server :battle :battle-id)))
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
                                   (filter (comp (partial resource/same-resource-file? map-importable) :importable))
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
                                     (filter (comp (partial resource/same-resource-filename? map-downloadable) :downloadable))
                                     first)
              engine-details (spring/engine-details engines battle-version)
              engine-importable (some->> importables
                                         (filter (comp #{::engine} :resource-type))
                                         (filter (partial resource/could-be-this-engine? battle-version))
                                         first)
              engine-import-task (->> all-tasks
                                      (filter (comp #{::import} ::task-type))
                                      (filter (comp (partial resource/same-resource-file? engine-importable) :importable))
                                      first)
              engine-downloadable (->> downloadables
                                       (filter (comp #{::engine} :resource-type))
                                       (filter (partial resource/could-be-this-engine? battle-version))
                                       first)
              engine-download-task (->> all-tasks
                                        (filter (comp #{::download-and-extract ::http-downloadable} ::task-type))
                                        (filter (comp (partial resource/same-resource-filename? engine-downloadable) :downloadable))
                                        first)
              mod-downloadable (->> downloadables
                                    (filter (comp #{::mod} :resource-type))
                                    (filter (partial resource/could-be-this-mod? battle-modname))
                                    first)
              mod-download-task (->> all-tasks
                                     (filter (comp #{::http-downloadable} ::task-type))
                                     (filter (comp (partial resource/same-resource-filename? mod-downloadable) :downloadable))
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
                              (not (fs/file-exists? file-cache (resource/resource-dest spring-root engine-importable))))
                         (do
                           (log/info "Adding task to auto import engine" engine-importable)
                           {::task-type ::import
                            :importable engine-importable
                            :spring-isolation-dir spring-root})
                         (and (not engine-importable)
                              engine-downloadable
                              (not engine-download-task)
                              (not (fs/file-exists? file-cache (resource/resource-dest spring-root engine-downloadable))))
                         (do
                           (log/info "Adding task to auto download engine" engine-downloadable)
                           {::task-type ::download-and-extract
                            :downloadable engine-downloadable
                            :spring-isolation-dir spring-root})
                         :else
                         nil))
                     (when
                       (and (= battle-map (:battle-map old-battle-details))
                            no-map)
                       (cond
                         (and map-importable
                              (not map-import-task)
                              (not (fs/file-exists? file-cache (resource/resource-dest spring-root map-importable))))
                         (do
                           (log/info "Adding task to auto import map" map-importable)
                           {::task-type ::import
                            :importable map-importable
                            :spring-isolation-dir spring-root})
                         (and (not map-importable)
                              map-downloadable
                              (not map-download-task)
                              (not (fs/file-exists? file-cache (resource/resource-dest spring-root map-downloadable))))
                         (do
                           (log/info "Adding task to auto download map" map-downloadable)
                           {::task-type ::http-downloadable
                            :downloadable map-downloadable
                            :spring-isolation-dir spring-root})
                         :else
                         nil))
                     (when
                       (and (= battle-modname (:battle-modname old-battle-details))
                            no-mod)
                       (cond
                         (and rapid-id
                              (not rapid-task)
                              engine-file
                              (not (fs/file-exists? file-cache (rapid/sdp-file spring-root (str (:hash rapid-data) ".sdp")))))
                         (do
                           (log/info "Adding task to auto download rapid" rapid-id)
                           {::task-type ::rapid-download
                            :engine-file engine-file
                            :rapid-id rapid-id
                            :spring-isolation-dir spring-root})
                         (and (not rapid-id)
                              mod-downloadable
                              (not mod-download-task)
                              (not (fs/file-exists? file-cache (resource/resource-dest spring-root mod-downloadable))))
                         (do
                           (log/info "Adding task to auto download mod" mod-downloadable)
                           {::task-type ::http-downloadable
                            :downloadable mod-downloadable
                            :spring-isolation-dir spring-root})
                         :else
                         nil))]]
         (filter some? tasks)))
      (catch Exception e
        (log/error e "Error in :auto-get-resources state watcher for server" (first new-server))))))


(defn battle-map-details-watcher [_k state-atom old-state new-state]
  (when (not= (battle-map-details-relevant-keys old-state)
              (battle-map-details-relevant-keys new-state))
    (try
      (doseq [[server-key new-server] (-> new-state :by-server seq)]
        (let [old-server (-> old-state :by-server (get server-key))
              server-url (-> new-server :client-data :server-url)
              {:keys [current-tasks servers spring-isolation-dir tasks]} new-state
              spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                              spring-isolation-dir)
              spring-root-path (fs/canonical-path spring-root)
              old-maps (-> old-state :by-spring-root (get spring-root-path) :maps)
              new-maps (-> new-state :by-spring-root (get spring-root-path) :maps)
              old-battle-id (-> old-server :battle :battle-id)
              new-battle-id (-> new-server :battle :battle-id)
              old-map (-> old-server :battles (get old-battle-id) :battle-map)
              new-map (-> new-server :battles (get new-battle-id) :battle-map)
              map-exists (->> new-maps (filter (comp #{new-map} :map-name)) first)
              map-details (-> new-state :map-details (get (fs/canonical-path (:file map-exists))))
              tries (or (:tries map-details) resource/max-tries)
              all-tasks (concat tasks (vals current-tasks))]
          (when (and (or (not (resource/details? map-details))
                         (< tries resource/max-tries))
                     (or (and (and (not (string/blank? new-map))
                                   (not (resource/details? map-details)))
                              (or (not= old-battle-id new-battle-id)
                                  (not= old-map new-map)
                                  (and
                                    (empty? (filter (comp #{::map-details} ::task-type) all-tasks))
                                    map-exists)))
                         (and
                           (or (not (some (comp #{new-map} :map-name) old-maps)))
                           map-exists)))
            (add-task! state-atom
              {::task-type ::map-details
               :map-name new-map
               :map-file (:file map-exists)
               :tries tries}))))
      (catch Exception e
        (log/error e "Error in :battle-map-details state watcher")))))


(defn battle-mod-details-watcher [_k state-atom old-state new-state]
  (when (not= (battle-mod-details-relevant-data old-state)
              (battle-mod-details-relevant-data new-state))
    (try
      (doseq [[server-key new-server] (-> new-state :by-server seq)]
        (let [old-server (-> old-state :by-server (get server-key))
              server-url (-> new-server :client-data :server-url)
              {:keys [current-tasks servers spring-isolation-dir tasks]} new-state
              spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                              spring-isolation-dir)
              spring-root-path (fs/canonical-path spring-root)
              old-mods (-> old-state :by-spring-root (get spring-root-path) :mods)
              new-mods (-> new-state :by-spring-root (get spring-root-path) :mods)
              old-battle-id (-> old-server :battle :battle-id)
              new-battle-id (-> new-server :battle :battle-id)
              old-mod (-> old-server :battles (get old-battle-id) :battle-modname)
              new-mod (-> new-server :battles (get new-battle-id) :battle-modname)
              new-mod-sans-git (u/mod-name-sans-git new-mod)
              mod-name-set (set [new-mod new-mod-sans-git])
              filter-fn (comp mod-name-set u/mod-name-sans-git :mod-name)
              mod-exists (->> new-mods (filter filter-fn) first)
              mod-details (-> new-state :mod-details (get (fs/canonical-path (:file mod-exists))))
              all-tasks (concat tasks (vals current-tasks))]
          (when (or (and (and (not (string/blank? new-mod))
                              (not (seq mod-details)))
                         (or (not= old-battle-id new-battle-id)
                             (not= old-mod new-mod)
                             (and
                               (empty? (filter (comp #{::mod-details} ::task-type) all-tasks))
                               mod-exists)))
                    (and
                      (or (not (some (comp #{new-mod} :mod-name) old-mods)))
                      mod-exists))
            (add-task! state-atom
              {::task-type ::mod-details
               :mod-name new-mod
               :mod-file (:file mod-exists)}))))
      (catch Exception e
        (log/error e "Error in :battle-mod-details state watcher")))))


(defn replay-map-and-mod-details-watcher [_k state-atom old-state new-state]
  (when (not= (replay-map-and-mod-details-relevant-keys old-state)
              (replay-map-and-mod-details-relevant-keys new-state))
    (try
      (let [old-selected-replay-file (:selected-replay-file old-state)
            old-replay-id (:selected-replay-id old-state)
            {:keys [online-bar-replays parsed-replays-by-path selected-replay-file selected-replay-id spring-isolation-dir]} new-state

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

            map-details (-> new-state :map-details (get new-map))
            mod-details (-> new-state :mod-details (get new-mod))

            map-changed (not= new-map (:map-name map-details))
            mod-changed (not= new-mod (:mod-name mod-details))

            spring-root-path (fs/canonical-path spring-isolation-dir)

            old-maps (-> old-state :by-spring-root (get spring-root-path) :maps)
            new-maps (-> new-state :by-spring-root (get spring-root-path) :maps)

            old-mods (-> old-state :by-spring-root (get spring-root-path) :mods)
            new-mods (-> new-state :by-spring-root (get spring-root-path) :mods)

            new-mod-sans-git (u/mod-name-sans-git new-mod)
            mod-name-set (set [new-mod new-mod-sans-git])
            filter-fn (comp mod-name-set u/mod-name-sans-git :mod-name)

            map-exists (->> new-maps (filter (comp #{new-map} :map-name)) first)
            mod-exists (->> new-mods (filter filter-fn) first)]
        (when (or (and (or (not= old-replay-path new-replay-path)
                           (not= old-mod new-mod))
                       (and (not (string/blank? new-mod))
                            (or (not (seq mod-details))
                                mod-changed)))
                  (and
                    (or (not (some filter-fn old-mods)))
                    mod-exists))
          (add-task! state-atom
            {::task-type ::mod-details
             :mod-name new-mod
             :mod-file (:file mod-exists)}))
        (when (or (and (or (not= old-replay-path new-replay-path)
                           (not= old-map new-map))
                       (and (not (string/blank? new-map))
                            (or (not (seq map-details))
                                map-changed)))
                  (and
                    (or (not (some (comp #{new-map} :map-name) old-maps)))
                    map-exists))
          (add-task! *state {::task-type ::map-details
                             :map-name new-map
                             :map-file (:file map-exists)})))
      (catch Exception e
        (log/error e "Error in :replay-map-and-mod-details state watcher")))))


(defn fix-missing-resource-watcher [_k state-atom old-state new-state]
  (tufte/profile {:id ::state-watcher}
    (tufte/p :fix-missing-resource-watcher
      (when (not= (fix-resource-relevant-keys old-state)
                  (fix-resource-relevant-keys new-state))
        (try
          (let [{:keys [engine-version engines map-name maps mod-name mods]} new-state
                engine-fix (when engine-version
                             (when-not (->> engines
                                            (filter (comp #{engine-version} :engine-version))
                                            first)
                               (-> engines first :engine-version)))
                games (filter :is-game mods)
                mod-fix (when mod-name
                          (when-not (->> games
                                         (filter (comp #{mod-name} :mod-name))
                                         first)
                            (-> games first :mod-name)))
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
            (log/error e "Error in :battle-map-details state watcher")))))))


(defn fix-spring-isolation-dir-watcher [_k state-atom old-state new-state]
  (tufte/profile {:id ::state-watcher}
    (tufte/p :fix-spring-isolation-dir-watcher
      (when (not= old-state new-state)
        (try
          (let [{:keys [spring-isolation-dir]} new-state]
            (when-not (and spring-isolation-dir
                           (instance? File spring-isolation-dir))
              (log/info "Fixed spring isolation dir, was" spring-isolation-dir)
              (swap! state-atom assoc :spring-isolation-dir (fs/default-isolation-dir))))
          (catch Exception e
            (log/error e "Error in :fix-spring-isolation-dir state watcher")))))))

(defn spring-isolation-dir-changed-watcher [_k state-atom old-state new-state]
  (tufte/profile {:id ::state-watcher}
    (tufte/p :spring-isolation-dir-changed-watcher
      (when (not= old-state new-state)
        (try
          (let [{:keys [spring-isolation-dir]} new-state]
            (when (and spring-isolation-dir
                       (instance? File spring-isolation-dir)
                       (not= (fs/canonical-path spring-isolation-dir)
                             (fs/canonical-path (:spring-isolation-dir old-state))))
              (log/info "Spring isolation dir changed from" (:spring-isolation-dir old-state)
                        "to" spring-isolation-dir "updating resources")
              (swap! state-atom
                (fn [{:keys [extra-import-sources] :as state}]
                  (-> state
                      (update :tasks-by-kind
                        add-multiple-tasks
                        [{::task-type ::reconcile-engines}
                         {::task-type ::reconcile-mods}
                         {::task-type ::reconcile-maps}
                         {::task-type ::scan-imports
                          :sources (fx.import/import-sources extra-import-sources)}
                         {::task-type ::update-rapid}
                         {::task-type ::refresh-replays}]))))))
          (catch Exception e
            (log/error e "Error in :spring-isolation-dir-changed state watcher")))))))


(defn auto-get-resources-watcher [_k state-atom old-state new-state]
  (tufte/profile {:id ::state-watcher}
    (tufte/p :auto-get-resources-watcher
      (when (not= (auto-get-resources-relevant-keys old-state)
                  (auto-get-resources-relevant-keys new-state))
        (try
          (when-let [tasks (->> new-state
                                :by-server
                                (mapcat
                                  (fn [[server-key  new-server]]
                                    (let [old-server (-> old-state :by-server (get server-key))]
                                      (server-auto-resources old-state new-state old-server new-server))))
                                (filter some?)
                                seq)]
            (log/info "Adding" (count tasks) "to auto get resources")
            (add-tasks! state-atom tasks))
          (catch Exception e
            (log/error e "Error in :auto-get-resources state watcher")))))))


(defn fix-selected-server-watcher [_k state-atom old-state new-state]
  (tufte/profile {:id ::state-watcher}
    (tufte/p :fix-selected-server-watcher
      (when (not= (fix-selected-server-relevant-keys old-state)
                  (fix-selected-server-relevant-keys new-state))
        (try
          (let [{:keys [server servers]} new-state
                [server-url server-data] server
                actual-server-data (get servers server-url)]
            (when (not= server-data actual-server-data)
              (let [new-server [server-url actual-server-data]]
                (log/info "Fixing selected server from" server "to" new-server)
                (swap! state-atom assoc :server new-server))))
          (catch Exception e
            (log/error e "Error in :fix-selected-server state watcher")))))))


(defn update-battle-status-sync-watcher [_k _ref old-state new-state]
  (when (not= (update-battle-status-sync-relevant-data old-state)
              (update-battle-status-sync-relevant-data new-state))
    (try
      (doseq [[server-key new-server] (-> new-state :by-server seq)]
        (let [old-server (-> old-state :by-server (get server-key))
              server-url (-> new-server :client-data :server-url)
              {:keys [servers spring-isolation-dir]} new-state
              spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                              spring-isolation-dir)
              spring-root-path (fs/canonical-path spring-root)

              old-spring (-> old-state :by-spring-root (get spring-root-path))
              new-spring (-> new-state :by-spring-root (get spring-root-path))

              old-sync (resource/sync-status old-server old-spring (:mod-details old-state) (:map-details old-state))
              new-sync (resource/sync-status new-server new-spring (:mod-details new-state) (:map-details new-state))

              new-sync-number (handler/sync-number new-sync)
              battle (:battle new-server)
              client-data (:client-data new-server)
              my-username (:username client-data)
              {:keys [battle-status team-color]} (-> battle :users (get my-username))
              old-sync-number (-> battle :users (get my-username) :battle-status :sync)]
          (when (and (:battle-id battle)
                     (or (not= old-sync new-sync)
                         (not= (:battle-id battle)
                               (-> old-server :battle :battle-id))))
            (log/info "Updating battle sync status for" server-key "from" old-sync
                      "(" old-sync-number ") to" new-sync "(" new-sync-number ")")
            (let [new-battle-status (assoc battle-status :sync new-sync-number)]
              (client-message client-data
                (str "MYBATTLESTATUS " (handler/encode-battle-status new-battle-status) " " team-color))))))
      (catch Exception e
        (log/error e "Error in :update-battle-status-sync state watcher")))))


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
  (remove-watch state-atom :fix-selected-server)
  (remove-watch state-atom :update-battle-status-sync)
  #_
  (add-watch state-atom :battle-map-details
    (fn [_k state-atom old-state new-state]
      (tufte/profile {:id ::state-watcher}
        (tufte/p :battle-map-details-watcher
          (battle-map-details-watcher _k state-atom old-state new-state)))))
  #_
  (add-watch state-atom :battle-mod-details
    (fn [_k state-atom old-state new-state]
      (tufte/profile {:id ::state-watcher}
        (tufte/p :battle-mod-details-watcher
          (battle-mod-details-watcher _k state-atom old-state new-state)))))
  (add-watch state-atom :replay-map-and-mod-details
    (fn [_k state-atom old-state new-state]
      (tufte/profile {:id ::state-watcher}
        (tufte/p :replay-map-and-mod-details-watcher
          (replay-map-and-mod-details-watcher _k state-atom old-state new-state)))))
  (add-watch state-atom :fix-missing-resource fix-missing-resource-watcher)
  (add-watch state-atom :fix-spring-isolation-dir fix-spring-isolation-dir-watcher)
  (add-watch state-atom :spring-isolation-dir-changed spring-isolation-dir-changed-watcher)
  (add-watch state-atom :auto-get-resources auto-get-resources-watcher)
  (add-watch state-atom :fix-selected-server fix-selected-server-watcher)
  #_
  (add-watch state-atom :update-battle-status-sync
    (fn [_k state-atom old-state new-state]
      (tufte/profile {:id ::state-watcher}
        (tufte/p :update-battle-status-sync-watcher
          (update-battle-status-sync-watcher))))))


(defmulti task-handler ::task-type)

(def ^:dynamic handle-task task-handler) ; for overriding in dev


(defmethod task-handler :default [task]
  (when task
    (log/warn "Unknown task type" task)))


(defn handle-task!
  ([state-atom task-kind]
   (let [[_before after] (swap-vals! state-atom
                           (fn [{:keys [tasks-by-kind] :as state}]
                             (if (empty? (get tasks-by-kind task-kind))
                               state ; don't update unnecessarily
                               (let [task (-> tasks-by-kind (get task-kind) shuffle first)]
                                 (-> state
                                     (update-in [:tasks-by-kind task-kind]
                                       (fn [tasks]
                                         (disj (set tasks) task)))
                                     (assoc-in [:current-tasks task-kind] task))))))
         task (-> after :current-tasks (get task-kind))]
     (try
       (handle-task task)
       (catch Throwable t
         (log/error t "Critical error running task"))
       (catch Exception e
         (log/error e "Error running task"))
       (finally
         (when task
           (swap! state-atom update :current-tasks assoc task-kind nil))))
     task)))


(defn- tasks-chimer-fn
  ([state-atom task-kind]
   (log/info "Starting tasks chimer for" task-kind)
   (let [chimer
         (chime/chime-at
           (chime/periodic-seq
             (java-time/plus (java-time/instant) (java-time/duration 1 :seconds))
             (java-time/duration 1 :seconds))
           (fn [_chimestamp]
             (handle-task! state-atom task-kind))
           {:error-handler
            (fn [e]
              (log/error e "Error handling task of kind" task-kind)
              true)})]
     (fn [] (.close chimer)))))


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
                    (fx.replay/replay-sources state))
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
                                [(fs/canonical-path f)
                                 (merge
                                   (replay/parse-replay f)
                                   {:source-name source})]))
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
   (reconcile-engines state-atom nil))
  ([state-atom spring-root]
   (swap! state-atom assoc :update-engines true) ; TODO remove
   (log/info "Reconciling engines")
   (apply fs/update-file-cache! state-atom (file-seq (fs/download-dir))) ; TODO move this somewhere
   (let [before (u/curr-millis)
         {:keys [spring-isolation-dir] :as state} @state-atom
         spring-root (or spring-root spring-isolation-dir)
         _ (log/info "Updating engines in" spring-root)
         engine-dirs (fs/engine-dirs spring-root)
         spring-root-path (fs/canonical-path spring-root)
         known-canonical-paths (->> (-> state
                                        :by-spring-root
                                        (get spring-root-path))
                                    :engines
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
                                (remove (comp (partial fs/descendant? spring-root) io/file)))))
         to-remove (set
                     (concat missing-files
                             (remove canonical-path-set known-canonical-paths)))]
     (apply fs/update-file-cache! state-atom known-canonical-paths)
     (log/info "Found" (count to-add) "engines to load in" (- (u/curr-millis) before) "ms")
     (doseq [engine-dir to-add]
       (log/info "Detecting engine data for" engine-dir)
       (let [engine-data (fs/engine-data engine-dir)]
         (swap! state-atom update-in [:by-spring-root spring-root-path :engines]
                (fn [engines]
                  (set (conj engines engine-data))))))
     (log/debug "Removing" (count to-remove) "engines")
     (swap! state-atom update-in [:by-spring-root spring-root-path :engines]
            (fn [engines]
              (->> engines
                   (filter (comp fs/canonical-path :file))
                   (remove (comp to-remove fs/canonical-path :file))
                   set)))
     (swap! state-atom assoc :update-engines false) ; TODO remove
     {:to-add-count (count to-add)
      :to-remove-count (count to-remove)})))


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
  ([state-atom]
   (reconcile-mods state-atom nil))
  ([state-atom spring-root]
   (swap! state-atom assoc :update-mods true) ; TODO remove
   (log/info "Reconciling mods")
   (remove-all-duplicate-mods state-atom)
   (let [before (u/curr-millis)
         {:keys [spring-isolation-dir] :as state} @state-atom
         spring-root (or spring-root spring-isolation-dir)
         _ (log/info "Updating mods in" spring-root)
         spring-root-path (fs/canonical-path spring-root)
         mods (-> state :by-spring-root (get spring-root-path) :mods)
         {:keys [rapid archive directory]} (group-by ::fs/source mods)
         known-file-paths (set (map (comp fs/canonical-path :file) (concat archive directory)))
         known-rapid-paths (set (map (comp fs/canonical-path :file) rapid))
         mod-files (fs/mod-files spring-root)
         sdp-files (rapid/sdp-files spring-root)
         _ (log/info "Found" (count mod-files) "files and"
                     (count sdp-files) "rapid archives to scan for mods")
         to-add-file (set
                       (concat
                         (remove (comp known-file-paths fs/canonical-path) mod-files)
                         (map :file directory))) ; always scan dirs in case git changed
         to-add-rapid (remove (comp known-rapid-paths fs/canonical-path) sdp-files)
         todo (concat to-add-file to-add-rapid)
         ; TODO prioritize mods in battles
         battle-mods (->> state
                          :by-server
                          (map
                            (fn [{:keys [battle battles]}]
                              (-> battles (get (:battle-id battle)) :battle-modname)))
                          (filter some?))
         priorities (->> todo
                         (filter
                           (comp
                             (fn [resource]
                               (some
                                 #(resource/could-be-this-mod? % resource)
                                 battle-mods))
                             (fn [f]
                               {:resource-filename (fs/filename f)}))))
         _ (log/info "Prioritizing mods in battles" (pr-str priorities))
         this-round (concat priorities (take 5 todo))
         next-round (drop 5 todo)
         all-paths (filter some? (concat known-file-paths known-rapid-paths))
         missing-files (set
                         (concat
                           (->> all-paths
                                (remove (comp fs/exists io/file)))
                           (->> all-paths
                                (remove (comp (partial fs/descendant? spring-root) io/file)))))]
     (apply fs/update-file-cache! state-atom all-paths)
     (log/info "Found" (count to-add-file) "mod files and" (count to-add-rapid)
               "rapid files to scan for mods in" (- (u/curr-millis) before) "ms")
     (when (seq this-round)
       (log/info "Adding" (count this-round) "mods this iteration"))
     (doseq [file this-round]
       (log/info "Reading mod from" file)
       (update-mod *state spring-root-path file)) ; TODO inline?
     (log/info "Removing mods with no name, and" (count missing-files) "mods with missing files")
     (swap! state-atom
            (fn [state]
              (-> state
                  (update-in [:by-spring-root spring-root-path :mods]
                    (fn [mods]
                      (->> mods
                           (filter #(contains? % :is-game))
                           (remove (comp string/blank? :mod-name))
                           (remove (comp missing-files fs/canonical-path :file))
                           set)))
                  (dissoc :update-mods))))
     (when (seq next-round)
       (log/info "Scheduling mod load since there are" (count next-round) "mods left to load")
       (add-task! state-atom {::task-type ::reconcile-mods}))
     {:to-add-file-count (count to-add-file)
      :to-add-rapid-count (count to-add-rapid)})))


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
              (sort-by :map-name))
         per-round 3
         this-round (take per-round to-update)
         next-round (drop per-round to-update)]
     (log/info (count to-update) "maps do not have cached minimap image files")
     (doseq [map-details this-round]
       (if-let [map-file (:file map-details)]
         (do
           (log/info "Caching minimap for" map-file)
           (let [{:keys [map-name smf]} (fs/read-map-data map-file)]
             (when-let [minimap-image-scaled (:minimap-image-scaled smf)]
               (fs/write-image-png minimap-image-scaled (fs/minimap-image-cache-file map-name)))))
         (log/error "Map is missing file" (:map-name map-details))))
     (when (seq next-round)
       (add-task! *state {::task-type ::update-cached-minimaps})))))

(defn- reconcile-maps
  "Reads map details and caches for maps missing from :maps in state."
  ([state-atom]
   (reconcile-maps state-atom nil))
  ([state-atom spring-root]
   (swap! state-atom assoc :update-maps true)
   (log/info "Reconciling maps")
   (let [before (u/curr-millis)
         {:keys [spring-isolation-dir] :as state} @state-atom
         spring-root (or spring-root spring-isolation-dir)
         _ (log/info "Updating maps in" spring-root)
         spring-root-path (fs/canonical-path spring-root)
         maps (-> state :by-spring-root (get spring-root-path) :maps)
         map-files (fs/map-files spring-root)
         known-files (->> maps (map :file) set)
         known-paths (->> known-files (map fs/canonical-path) set)
         todo (remove (comp known-paths fs/canonical-path) map-files)
         battle-maps (->> state
                          :by-server
                          (map
                            (fn [{:keys [battle battles]}]
                              (-> battles (get (:battle-id battle)) :battle-map)))
                          (filter some?))
         priorities (->> todo
                         (filter
                           (comp
                             (fn [resource]
                               (some
                                 #(resource/could-be-this-map? % resource)
                                 battle-maps))
                             (fn [f]
                               {:resource-filename (fs/filename f)}))))
         _ (log/info "Prioritizing maps in battles" (pr-str priorities))
         this-round (concat priorities (take 5 todo))
         next-round (drop 5 todo)
         missing-paths (set
                         (concat
                           (->> known-files
                                (remove fs/exists)
                                (map fs/canonical-path))
                           (->> known-files
                                (remove (partial fs/descendant? spring-root))
                                (map fs/canonical-path))))]
     (apply fs/update-file-cache! state-atom map-files)
     (log/info "Found" (count todo) "maps to load in" (- (u/curr-millis) before) "ms")
     (fs/make-dirs fs/maps-cache-root)
     (when (seq this-round)
       (log/info "Adding" (count this-round) "maps this iteration"))
     (doseq [map-file this-round]
       (log/info "Reading" map-file)
       (let [{:keys [map-name] :as map-data} (fs/read-map-data map-file)]
         (if map-name
           (swap! state-atom update-in [:by-spring-root spring-root-path :maps]
                  (fn [maps]
                    (set (conj maps (select-keys map-data [:file :map-name])))))
           (log/warn "No map name found for" map-file))))
     (log/debug "Removing maps with no name, and" (count missing-paths) "maps with missing files")
     (swap! state-atom update-in [:by-spring-root spring-root-path :maps]
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
     {:todo-count (count todo)})))


(defn- truncate-messages-chimer-fn [state-atom]
  (log/info "Starting message truncate chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 1 :minutes))
            (java-time/duration 5 :minutes))
          (fn [_chimestamp]
            (log/info "Truncating message logs")
            (swap! state-atom update :by-server
              (fn [by-server]
                (reduce-kv
                  (fn [m k v]
                    (assoc m k
                      (-> v
                          (update :console-log (partial take u/max-messages))
                          (update :channels
                            (fn [channels]
                              (reduce-kv
                                (fn [m k v]
                                  (assoc m k (update v :messages (partial take u/max-messages))))
                                {}
                                channels))))))
                  {}
                  by-server))))

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
  (log/info "Starting app update check chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 5 :minutes))
            (java-time/duration 1 :hours))
          (fn [_chimestamp]
            (check-app-update state-atom))
          {:error-handler
           (fn [e]
             (log/error e "Error checking for app update")
             true)})]
    (fn [] (.close chimer))))


(defn- spit-app-config-chimer-fn [state-atom]
  (log/info "Starting app config spit chimer")
  (let [old-state-atom (atom {})
        chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 10 :seconds))
            (java-time/duration 3 :seconds))
          (fn [_chimestamp]
            (let [old-state @old-state-atom
                  new-state @state-atom]
              (spit-state-config-to-edn old-state new-state)
              (reset! old-state-atom new-state)))
          {:error-handler
           (fn [e]
             (log/error e "Error spitting app config edn")
             true)})]
    (fn [] (.close chimer))))


(defn- state-change-chimer-fn
  "Creates a chimer that runs a state watcher fn periodically."
  [state-atom k watcher-fn]
  (let [old-state-atom (atom {})
        chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/instant)
            (java-time/duration 3 :seconds))
          (fn [_chimestamp]
            (let [old-state @old-state-atom
                  new-state @state-atom]
              (tufte/profile {:id ::chimer}
                (tufte/p k
                  (watcher-fn k state-atom old-state new-state)))
              (reset! old-state-atom new-state)))
          {:error-handler
           (fn [e]
             (log/error e "Error in" k "state change chimer")
             true)})]
    (fn [] (.close chimer))))


; https://github.com/ptaoussanis/tufte/blob/master/examples/clj/src/example/server.clj
(defn- profile-print-chimer-fn [_state-atom]
  (log/info "Starting profile print chimer")
  (let [stats-accumulator (tufte/add-accumulating-handler! {:ns-pattern "*"})
        chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 5 :seconds))
            (java-time/duration 1 :minutes))
          (fn [_chimestamp]
            (if-let [m (not-empty @stats-accumulator)]
              (log/info (str "Profiler stats:\n" (tufte/format-grouped-pstats m)))
              (log/warn "No profiler stats to print")))
          {:error-handler
           (fn [e]
             (log/error e "Error in profiler print")
             true)})]
    (fn [] (.close chimer))))


(defn reconcile-engines-all-spring-roots []
  (let [spring-roots (spring-roots @*state)]
    (log/info "Reconciling engines in" (pr-str spring-roots))
    (doseq [spring-root spring-roots]
      (reconcile-engines *state spring-root))))

(defmethod task-handler ::reconcile-engines [_]
  (reconcile-engines-all-spring-roots))


(defn reconcile-mods-all-spring-roots []
  (let [spring-roots (spring-roots @*state)]
    (log/info "Reconciling mods in" (pr-str spring-roots))
    (doseq [spring-root spring-roots]
      (reconcile-mods *state spring-root))))

(defmethod task-handler ::reconcile-mods [_]
  (reconcile-mods-all-spring-roots))

(defn reconcile-maps-all-spring-roots []
  (let [spring-roots (spring-roots @*state)]
    (log/info "Reconciling maps in" (pr-str spring-roots))
    (doseq [spring-root spring-roots]
      (reconcile-maps *state spring-root))))

(defmethod task-handler ::reconcile-maps [_]
  (reconcile-maps-all-spring-roots))


(defmethod task-handler ::update-file-cache [{:keys [file]}]
  (fs/update-file-cache! *state file))


(defmethod task-handler ::map-details [{:keys [map-name map-file tries] :as map-data}]
  (let [new-tries ((fnil inc 0) tries)
        error-data {:error true
                    :file map-file
                    :map-name map-name
                    :tries new-tries}
        cache-key (fs/canonical-path map-file)]
    (try
      (if map-file
        (do
          (log/info "Updating battle map details for" map-name)
          (let [map-details (or (read-map-details map-data) error-data)]
            (log/info "Got map details for" map-name map-file (keys map-details))
            (swap! *state update :map-details cache/miss cache-key map-details)))
        (do
          (log/info "Map not found, setting empty details for" map-name)
          (swap! *state update :map-details cache/miss cache-key {:tries new-tries})))
      (catch Throwable t
        (log/error t "Error updating map details")
        (swap! *state update :map-details cache/miss cache-key error-data)
        (throw t)))))


(defmethod task-handler ::mod-details [{:keys [mod-name mod-file]}]
  (let [error-data {:mod-name mod-name
                    :file mod-file
                    :error true
                    :tries 1} ; TODO inc
        cache-key (fs/canonical-path mod-file)]
    (try
      (if mod-file
        (do
          (log/info "Updating mod details for" mod-name)
          (let [mod-details (or (read-mod-data mod-file) error-data)]
            (log/info "Got mod details for" mod-name mod-file (keys mod-details))
            (swap! *state update :mod-details cache/miss cache-key mod-details)))
        (do
          (log/info "Battle mod not found, setting empty details for" mod-name)
          (swap! *state update :mod-details cache/miss cache-key {})))
      (catch Throwable t
        (log/error t "Error updating mod details")
        (swap! *state update :mod-details cache/miss cache-key error-data)
        (throw t)))))


(defmethod task-handler ::update-cached-minimaps [_]
  (let [{:keys [by-spring-root]} @*state
        all-maps (mapcat :maps (vals by-spring-root))]
    (log/info "Found" (count all-maps) "maps to update cached minimaps for")
    (update-cached-minimaps all-maps)))

(defmethod task-handler ::refresh-replays [_]
  (refresh-replays *state))

(defmethod task-handler ::refresh-replay-resources [_]
  (refresh-replay-resources *state))


(defmethod event-handler ::select-battle [{:fx/keys [event] :keys [server-key]}]
  (swap! *state assoc-in [:by-server server-key :selected-battle] (:battle-id event)))


(defmethod event-handler ::on-mouse-clicked-battles-row
  [{:fx/keys [^javafx.scene.input.MouseEvent event] :as e}]
  (future
    (when (= 2 (.getClickCount event))
      @(event-handler (merge e {:event/type ::join-battle})))))


(defmethod event-handler ::join-direct-message
  [{:keys [server-key username]}]
  (swap! *state
    (fn [state]
      (let [channel-name (str "@" username)]
        (-> state
            (assoc-in [:by-server server-key :my-channels channel-name] {})
            (assoc :selected-tab-main "chat")
            (assoc :selected-tab-channel channel-name))))))

(defmethod event-handler ::on-mouse-clicked-users-row
  [{:fx/keys [^javafx.scene.input.MouseEvent event] :as e}]
  (future
    (when (< 1 (.getClickCount event))
      (when (:username e)
        (event-handler (merge e {:event/type ::join-direct-message}))))))


(defmethod event-handler ::join-channel [{:keys [channel-name client-data]}]
  (future
    (try
      (let [server-key (u/server-key client-data)]
        (swap! *state
          (fn [state]
            (-> state
                (assoc-in [:by-server server-key :join-channel-name] "")
                (assoc-in [:my-channels server-key channel-name] {}))))
        (client-message client-data (str "JOIN " channel-name)))
      (catch Exception e
        (log/error e "Error joining channel" channel-name)))))

(defmethod event-handler ::leave-channel
  [{:keys [channel-name client-data] :fx/keys [^Event event]}]
  (future
    (try
      (let [server-key (u/server-key client-data)]
        (swap! *state
          (fn [state]
            (-> state
                (update-in [:by-server server-key :my-channels] dissoc channel-name)
                (update-in [:my-channels server-key] dissoc channel-name)))))
      (when-not (string/starts-with? channel-name "@")
        (client-message client-data (str "LEAVE " channel-name)))
      (catch Exception e
        (log/error e "Error leaving channel" channel-name))))
  (.consume event))


(defn- update-disconnected!
  [state-atom server-key]
  (log/info "Disconnecting from" (pr-str server-key))
  (let [[old-state _new-state] (swap-vals! state-atom update :by-server dissoc server-key)
        {:keys [client-data ping-loop print-loop]} (-> old-state :by-server (get server-key))
        {:keys [client]} client-data]
    (if client
      (client/disconnect client)
      (log/warn (ex-info "stacktrace" {:server-key server-key}) "No client to disconnect!"))
    (if ping-loop
      (future-cancel ping-loop)
      (log/warn (ex-info "stacktrace" {:server-key server-key}) "No ping loop to cancel!"))
    (if print-loop
      (future-cancel print-loop)
      (log/warn (ex-info "stacktrace" {:server-key server-key}) "No print loop to cancel!")))
  nil)

(defmethod event-handler ::print-state [_e]
  (pprint *state))


(defmethod event-handler ::disconnect [{:keys [server-key]}]
  (update-disconnected! *state server-key))

(defn- connect
  [state-atom {:keys [client-deferred server server-key password username] :as state}]
  (future
    (try
      (let [^SplicedStream client @client-deferred]
        (s/on-closed client
          (fn []
            (log/info "client closed")
            (update-disconnected! *state server-key)))
        (s/on-drained client
          (fn []
            (log/info "client drained")
            (update-disconnected! *state server-key)))
        (if (s/closed? client)
          (log/warn "client was closed on create")
          (let [[server-url server-data] server
                client-data {:client client
                             :client-deferred client-deferred
                             :server-key server-key
                             :server-url server-url
                             :ssl (:ssl server-data)
                             :password password
                             :username username}]
            (log/info "Connecting to" server-key)
            (swap! state-atom
              (fn [state]
                (-> state
                    (update :login-error dissoc server-url)
                    (assoc-in [:by-server server-key :client-data] client-data))))
            (client/connect state-atom (assoc state :client-data client-data)))))
      (catch Exception e
        (log/error e "Connect error")
        (swap! state-atom assoc-in [:by-server server-key :login-error] (str (.getMessage e)))
        (update-disconnected! *state server-key)))
    nil))

(defmethod event-handler ::connect [{:keys [server server-key password username] :as state}]
  (future
    (try
      (let [[server-url server-opts] server
            client-deferred (client/client server-url server-opts)]
        (swap! *state
               (fn [state]
                 (-> state
                     (assoc :selected-server-tab server-key)
                     (update-in [:by-server server-key]
                       assoc :client-data {:client-deferred client-deferred
                                           :server-url server-url
                                           :ssl (:ssh (second server))
                                           :password password
                                           :username username}
                             :server server))))
        (connect *state (assoc state
                               :client-deferred client-deferred
                               :password password
                               :username username)))
      (catch Exception e
        (log/error e "Error connecting")))))

(defmethod event-handler ::cancel-connect [{:keys [client client-deferred server-key]}]
  (future
    (try
      (if client-deferred
        (deferred/error! client-deferred (ex-info "User cancel connect" {}))
        (log/warn "No client-deferred to cancel"))
      (when client
        (log/warn "client found during cancel")
        (s/close! client))
      (catch Exception e
        (log/error e "Error cancelling connect"))
      (finally
        (update-disconnected! *state server-key)))))


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


(defmethod event-handler ::register [{:keys [email password server username]}]
  (future
    (try
      (let [[server-url server-opts] server
            client-deferred (client/client server-url server-opts)
            client @client-deferred
            client-data {:client client
                         :client-deferred client-deferred
                         :server-url server-url}
            server-key (u/server-key client-data)]
        (swap! *state dissoc :password-confirm)
        (client-message client-data
          (str "REGISTER " username " " (u/base64-md5 password) " " email))
        (loop []
          (when-let [d (s/take! client)]
            (when-let [m @d]
              (log/info "(register) <" (str "'" m "'"))
              (swap! *state assoc-in [:by-server server-key :register-response] m)
              (when-not (Thread/interrupted)
                (recur)))))
        (s/close! client))
      (catch Exception e
        (log/error e "Error registering with" server "as" username)))))

(defmethod event-handler ::confirm-agreement [{:keys [client-data password username verification-code]}]
  (future
    (try
      (client-message client-data (str "CONFIRMAGREEMENT " verification-code))
      (swap! *state dissoc :agreement :verification-code)
      (client/login client-data username password)
      (catch Exception e
        (log/error e "Error confirming agreement")))))

(defmethod event-handler ::edit-server
  [{:fx/keys [event]}]
  (let [[_server-url server-data] event]
    (swap! *state assoc
           :server-edit event
           :server-host (:host server-data)
           :server-port (:port server-data)
           :server-alias (:alias server-data)
           :server-auto-connect (:auto-connect server-data)
           :server-ssl (:ssl server-data)
           :server-spring-root-draft (fs/canonical-path (:spring-isolation-dir server-data)))))

(defmethod event-handler ::update-server
  [{:keys [server-url server-data]}]
  (log/info "Updating server" server-url "to" server-data)
  (swap! *state update-in [:servers server-url] merge server-data)
  (event-handler {:event/type ::edit-server
                  :fx/event [server-url server-data]}))


(defmethod event-handler ::delete-extra-import-source [{:keys [file]}]
  (swap! *state update :extra-import-sources
    (fn [extra-import-sources]
      (remove (comp #{(fs/canonical-path file)} fs/canonical-path :file) extra-import-sources))))

(defmethod event-handler ::delete-extra-replay-source [{:keys [file]}]
  (swap! *state update :extra-replay-sources
    (fn [extra-replay-sources]
      (remove (comp #{(fs/canonical-path file)} fs/canonical-path :file) extra-replay-sources))))


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


; TODO uikeys
#_
(defmethod event-handler ::uikeys-pressed [{:fx/keys [^KeyEvent event] :keys [selected-uikeys-action]}]
  (if (.isModifierKey (.getCode event))
    (log/debug "Ignoring modifier key event for uikeys")
    (do
      (log/info event)
      (swap! *state assoc-in [:uikeys selected-uikeys-action] (key-event-to-uikeys-bind event)))))

#_
(defmethod event-handler ::uikeys-select
  [{:fx/keys [event]}]
  (swap! *state assoc :selected-uikeys-action (:bind-action event)))


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
  [client-data
   {:keys [battle-type nat-type battle-password host-port max-players mod-hash rank map-hash
           engine engine-version map-name title mod-name]
    :or {battle-type 0
         nat-type 0
         battle-password "*"
         host-port 8452
         max-players 8
         rank 0
         engine "Spring"}}]
  (let [password (if (string/blank? battle-password) "*" battle-password)]
    (client-message client-data
      (str "OPENBATTLE " battle-type " " nat-type " " password " " host-port " " max-players
           " " mod-hash " " rank " " map-hash " " engine "\t" engine-version "\t" map-name "\t" title
           "\t" mod-name))))


(defmethod event-handler ::host-battle
  [{:keys [client-data scripttags host-battle-state use-springlobby-modname]}]
  (let [{:keys [engine-version map-name mod-name]} host-battle-state]
    (when-not (or (string/blank? engine-version)
                  (string/blank? mod-name)
                  (string/blank? map-name))
      (future
        (try
          (let [adjusted-modname (if use-springlobby-modname
                                   (u/mod-name-fix-git mod-name)
                                   mod-name)]
            (open-battle client-data (assoc host-battle-state :mod-name adjusted-modname)))
          (when (seq scripttags)
            (client-message client-data (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags))))
          (catch Exception e
            (log/error e "Error opening battle")))))))


(defmethod event-handler ::leave-battle [{:keys [client-data]}]
  (future
    (try
      (client-message client-data "LEAVEBATTLE")
      (let [server-key (u/server-key client-data)]
        (swap! *state update-in [:by-server server-key] dissoc :battle))
      (catch Exception e
        (log/error e "Error leaving battle")))))


(defmethod event-handler ::join-battle
  [{:keys [battle battle-password battle-passworded client-data selected-battle] :as e}]
  (future
    (try
      (when battle
        @(event-handler (merge e {:event/type ::leave-battle}))
        (async/<!! (async/timeout 500)))
      (if selected-battle
        (do
          (swap! *state assoc :selected-battle nil :battle {})
          (client-message client-data
            (str "JOINBATTLE " selected-battle
                 (if battle-passworded
                   (str " " battle-password)
                   (str " *"))
                 " " (crypto.random/hex 6))))
        (log/warn "No battle to join" e))
      (catch Exception e
        (log/error e "Error joining battle")))))

(defmethod event-handler ::start-singleplayer-battle
  [_e]
  (future
    (try
      (swap! *state
             (fn [{:keys [engine-version map-name mod-name username] :as state}]
               (-> state
                   (assoc-in [:by-server :local :username] username)
                   (assoc-in [:by-server :local :battles :singleplayer] {:battle-version engine-version
                                                                         :battle-map map-name
                                                                         :battle-modname mod-name
                                                                         :host-username username})
                   (assoc-in [:by-server :local :battle]
                             {:battle-id :singleplayer
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


; https://github.com/cljfx/cljfx/blob/ec3c34e619b2408026b9f2e2ff8665bebf70bf56/examples/e33_file_chooser.clj
(defmethod event-handler ::file-chooser-spring-root
  [{:fx/keys [event] :keys [spring-isolation-dir target] :or {target [:spring-isolation-dir]}}]
  (let [window (.getWindow (.getScene (.getTarget event)))
        chooser (doto (DirectoryChooser.)
                  (.setTitle "Select Spring Directory")
                  (.setInitialDirectory spring-isolation-dir))]
    (when-let [file (.showDialog chooser window)]
      (log/info "Setting spring isolation dir at" target "to" file)
      (swap! *state assoc-in target file))))


(defmethod event-handler ::update-css
  [{:keys [css]}]
  (let [registered (css/register :skylobby.fx/current css)]
    (swap! *state assoc :css registered)))

(defmethod event-handler ::load-custom-css
  [{:keys [file]}]
  (if (fs/exists? file)
    (let [css (edn/read-string (slurp file))]
      (event-handler {:css css
                      :event/type ::update-css}))
    (log/warn "Custom CSS file does not exist" file)))


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
  [{:fx/keys [event] :keys [client-data map-name]}]
  (future
    (try
      (let [spectator-count 0 ; TODO
            locked 0
            map-hash -1 ; TODO
            map-name (or map-name event)
            m (str "UPDATEBATTLEINFO " spectator-count " " locked " " map-hash " " map-name)]
        (client-message client-data m))
      (catch Exception e
        (log/error e "Error changing battle map")))))

(defmethod event-handler ::suggest-battle-map
  [{:fx/keys [event] :keys [battle-status channel-name client-data map-name]}]
  (future
    (try
      (cond
        (string/blank? channel-name) (log/warn "No channel to suggest battle map")
        (not (:mode battle-status)) (log/info "Cannot suggest battle map as spectator")
        :else
        (let [map-name (or map-name event)]
          @(event-handler
             {:event/type ::send-message
              :channel-name channel-name
              :client-data client-data
              :message (str "!map " map-name)})))
      (catch Exception e
        (log/error e "Error suggesting map")))))

(defmethod event-handler ::kick-battle
  [{:keys [bot-name client-data singleplayer username]}]
  (future
    (try
      (if singleplayer
        (do
          (log/info "Singleplayer battle kick")
          (swap! *state
                 (fn [state]
                   (-> state
                       (update-in [:by-server :local :battles :singleplayer :bots] dissoc bot-name)
                       (update-in [:by-server :local :battle :bots] dissoc bot-name)
                       (update-in [:by-server :local :battles :singleplayer :users] dissoc username)
                       (update-in [:by-server :local :battle :users] dissoc username)))))
        (if bot-name
          (client-message client-data (str "REMOVEBOT " bot-name))
          (client-message client-data (str "KICKFROMBATTLE " username))))
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

(defmethod event-handler ::add-bot
  [{:keys [battle bot-username bot-name bot-version client-data singleplayer username]}]
  (future
    (try
      (let [existing-bots (keys (:bots battle))
            bot-username (available-name existing-bots bot-username)
            status (assoc handler/default-battle-status
                          :ready true
                          :mode 1
                          :sync 1
                          :id (battle/available-team-id battle)
                          :ally (battle/available-ally battle)
                          :side (rand-nth [0 1]))
            bot-status (handler/encode-battle-status status)
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
                           (assoc-in [:by-server :local :battles :singleplayer :bots bot-username] bot-data)
                           (assoc-in [:by-server :local :battle :bots bot-username] bot-data))))))
          (client-message client-data message)))
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
  [{:keys [am-host am-spec battle-status channel-name client-data host-ingame] :as state}]
  (future
    (try
      (when-not (:mode battle-status)
        @(event-handler
           {:event/type ::send-message
            :channel-name channel-name
            :client-data client-data
            :message (str "!joinas spec")})
        (async/<!! (async/timeout 1000)))
      (if (or am-host am-spec host-ingame)
        (spring/start-game state)
        @(event-handler
           {:event/type ::send-message
            :channel-name channel-name
            :client-data client-data
            :message (str "!cv start")}))
      (catch Exception e
        (log/error e "Error starting battle")))))


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
  [{:keys [am-host client-data minimap-width minimap-height map-details singleplayer] :as e}]
  (future
    (try
      (let [[before _after] (swap-vals! *state dissoc :drag-team :drag-allyteam)]
        (when-let [{:keys [team x y]} (:drag-team before)]
          (let [{:keys [map-width map-height]} (-> map-details :smf :header)
                x (int (* (/ x minimap-width) map-width spring/map-multiplier))
                z (int (* (/ y minimap-height) map-height spring/map-multiplier))
                team-data {:startposx x
                           :startposy z ; for SpringLobby bug
                           :startposz z}
                scripttags {:game {(keyword (str "team" team)) team-data}}]
            (if singleplayer
              (swap! *state update-in
                     [:by-server :local :battle :scripttags :game (keyword (str "team" team))]
                     merge team-data)
              (client-message client-data
                (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags))))))
        (when-let [{:keys [allyteam-id startx starty endx endy]} (:drag-allyteam before)]
          (let [l (min startx endx)
                t (min starty endy)
                r (max startx endx)
                b (max starty endy)
                left (/ l (* 1.0 minimap-width))
                top (/ t (* 1.0 minimap-height))
                right (/ r (* 1.0 minimap-width))
                bottom (/ b (* 1.0 minimap-height))]
            (if singleplayer
              (swap! *state update-in [:by-server :local :battle :scripttags :game (keyword (str "allyteam" allyteam-id))]
                     (fn [allyteam]
                       (assoc allyteam
                              :startrectleft left
                              :startrecttop top
                              :startrectright right
                              :startrectbottom bottom)))
              (if am-host
                (client-message client-data
                  (str "ADDSTARTRECT " allyteam-id " "
                       (int (* 200 left)) " "
                       (int (* 200 top)) " "
                       (int (* 200 right)) " "
                       (int (* 200 bottom))))
                (event-handler
                  (assoc e
                         :event/type ::send-message
                         :message
                         (str "!addBox "
                              (int (* 200 left)) " "
                              (int (* 200 top)) " "
                              (int (* 200 right)) " "
                              (int (* 200 bottom)) " "
                              (inc allyteam-id)))))))))
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
        (fs/update-file-cache! *state source dest)
        (case (:resource-type importable)
          ::map (reconcile-maps-all-spring-roots)
          ::mod (reconcile-mods-all-spring-roots)
          ::engine (reconcile-engines-all-spring-roots)
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
          (reconcile-mods-all-spring-roots)
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
                              (direction (.indexOf ^List fx.minimap/minimap-types minimap-type))
                              (count fx.minimap/minimap-types))
                 next-type (get fx.minimap/minimap-types next-index)]
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
  [client-data {:keys [is-bot id]} battle-status team-color]
  (let [player-name (or (:bot-name id) (:username id))]
    (if client-data
      (let [prefix (if is-bot
                     (str "UPDATEBOT " player-name) ; TODO normalize
                     "MYBATTLESTATUS")
            battle-status (if (and (not is-bot) (:mode battle-status))
                            (assoc battle-status :ready true)
                            battle-status)]
        (log/debug player-name (pr-str battle-status) team-color)
        (client-message client-data
          (str prefix
               " "
               (handler/encode-battle-status battle-status)
               " "
               team-color)))
      (let [data {:battle-status battle-status
                  :team-color team-color}]
        (log/info "No client, assuming singleplayer")
        (swap! *state
               (fn [state]
                 (-> state
                     (update-in [:by-server :local :battles :singleplayer (if is-bot :bots :users) player-name] merge data)
                     (update-in [:by-server :local :battle (if is-bot :bots :users) player-name] merge data))))))))

(defmethod event-handler ::update-battle-status
  [{:keys [client-data opts battle-status team-color]}]
  (update-battle-status client-data opts battle-status team-color))

(defmethod event-handler ::update-client-status [{:keys [client-data client-status]}]
  (client-message client-data (str "MYSTATUS " (cu/encode-client-status client-status))))

(defn- update-color [client-data id {:keys [is-me is-bot] :as opts} color-int]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client-data (assoc opts :id id) (:battle-status id) color-int)
        (client-message client-data
          (str "FORCETEAMCOLOR " (:username id) " " color-int)))
      (catch Exception e
        (log/error e "Error updating color")))))

(defn- update-team [client-data id {:keys [is-me is-bot] :as opts} player-id]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client-data (assoc opts :id id) (assoc (:battle-status id) :id player-id) (:team-color id))
        (client-message client-data
          (str "FORCETEAMNO " (:username id) " " player-id)))
      (catch Exception e
        (log/error e "Error updating team")))))

(defn- update-ally [client-data id {:keys [is-me is-bot] :as opts} ally]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client-data (assoc opts :id id) (assoc (:battle-status id) :ally ally) (:team-color id))
        (client-message client-data (str "FORCEALLYNO " (:username id) " " ally)))
      (catch Exception e
        (log/error e "Error updating ally")))))

(defn- update-handicap [client-data id {:keys [is-bot] :as opts} handicap]
  (future
    (try
      (if is-bot
        (update-battle-status client-data (assoc opts :id id) (assoc (:battle-status id) :handicap handicap) (:team-color id))
        (client-message client-data (str "HANDICAP " (:username id) " " handicap)))
      (catch Exception e
        (log/error e "Error updating handicap")))))

(defn- apply-battle-status-changes
  [client-data id {:keys [is-me is-bot] :as opts} status-changes]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client-data (assoc opts :id id) (merge (:battle-status id) status-changes) (:team-color id))
        (doseq [[k v] status-changes]
          (let [msg (case k
                      :id "FORCETEAMNO"
                      :ally "FORCEALLYNO"
                      :handicap "HANDICAP")]
            (client-message client-data (str msg " " (:username id) " " v)))))
      (catch Exception e
        (log/error e "Error applying battle status changes")))))


(defn balance-teams [battle-players-and-bots teams-count]
  (let [nonspec (->> battle-players-and-bots
                     (filter (comp u/to-bool :mode :battle-status)))] ; remove spectators
    (->> nonspec
         shuffle
         (map-indexed
           (fn [i id]
             (let [ally (mod i teams-count)]
               {:id id
                :opts {:is-bot (boolean (:bot-name id))}
                :status-changes {:ally ally}})))
         (sort-by (comp :ally :status-changes))
         (map-indexed
           (fn [i data]
             (assoc-in data [:status-changes :id] i))))))


(defn- n-teams [{:keys [client-data] :as e} n]
  (future
    (try
      (let [new-teams (balance-teams (battle-players-and-bots e) n)]
        (->> new-teams
             (map
               (fn [{:keys [id opts status-changes]}]
                 (let [is-me (= (:username e) (:username id))]
                   (apply-battle-status-changes client-data (assoc id :is-me is-me) opts status-changes))))
             doall))
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
  [{:keys [battle client-data users username]}]
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
            (apply-battle-status-changes client-data player {:is-me is-me :is-bot false} {:id i :ally 0})))
        players))
    (doall
      (map-indexed
        (fn [b bot]
          (let [i (+ (count players) b)]
            (apply-battle-status-changes client-data bot {:is-me false :is-bot true} {:id i :ally 1})))
        bots))))


(defmethod event-handler ::ring
  [{:keys [channel-name client-data username]}]
  (when channel-name
    @(event-handler
       {:event/type ::send-message
        :channel-name channel-name
        :client-data client-data
        :message (str "!ring " username)})))


(defmethod event-handler ::battle-startpostype-change
  [{:fx/keys [event] :keys [am-host client-data singleplayer] :as e}]
  (let [startpostype (get spring/startpostypes-by-name event)]
    (if am-host
      (if singleplayer
        (swap! *state
               (fn [state]
                 (-> state
                     (assoc-in [:by-server :local :scripttags :game :startpostype] startpostype)
                     (assoc-in [:by-server :local :battle :scripttags :game :startpostype] startpostype))))
        (client-message client-data (str "SETSCRIPTTAGS game/startpostype=" startpostype)))
      (event-handler
        (assoc e
               :event/type ::send-message
               :message (str "!bSet startpostype " startpostype))))))

(defmethod event-handler ::reset-start-positions
  [{:keys [client-data server-key]}]
  (let [team-ids (take 16 (iterate inc 0))
        scripttag-keys (map (fn [i] (str "game/team" i)) team-ids)
        team-kws (map #(keyword (str "team" %)) team-ids)
        dissoc-fn #(apply dissoc % team-kws)]
    (swap! *state update-in [:by-server server-key]
           (fn [state]
             (-> state
                 (update-in [:scripttags :game] dissoc-fn)
                 (update-in [:battle :scripttags :game] dissoc-fn))))
    (client-message
      client-data
      (str "REMOVESCRIPTTAGS " (string/join " " scripttag-keys)))))

(defmethod event-handler ::clear-start-boxes
  [{:keys [allyteam-ids client-data server-key]}]
  (doseq [allyteam-id allyteam-ids]
    (let [allyteam-kw (keyword (str "allyteam" allyteam-id))]
      (swap! *state update-in [:by-server server-key]
             ; TODO one swap
             (fn [state]
               (-> state
                   (update-in [:scripttags :game] dissoc allyteam-kw)
                   (update-in [:battle :scripttags :game] dissoc allyteam-kw)))))
    (client-message client-data (str "REMOVESTARTRECT " allyteam-id))))

(defmethod event-handler ::modoption-change
  [{:keys [am-host client-data modoption-key modoption-type singleplayer] :fx/keys [event] :as e}]
  (let [value (if (= "list" modoption-type)
                (str event)
                (u/to-number event))]
    (if singleplayer
      (swap! *state
             (fn [state]
               (-> state
                   (assoc-in [:by-server :local :scripttags :game :modoptions modoption-key] (str event))
                   (assoc-in [:by-server :local :battle :scripttags :game :modoptions modoption-key] (str event)))))
      (if am-host
        (client-message client-data (str "SETSCRIPTTAGS game/modoptions/" (name modoption-key) "=" value))
        (event-handler
          (assoc e
                 :event/type ::send-message
                 :message (str "!bSet " (name modoption-key) " " value)))))))

(defmethod event-handler ::battle-ready-change
  [{:fx/keys [event] :keys [battle-status client-data team-color] :as id}]
  (future
    (try
      (update-battle-status client-data {:id id} (assoc battle-status :ready event) team-color)
      (catch Exception e
        (log/error e "Error updating battle ready")))))


(defmethod event-handler ::battle-spectate-change
  [{:keys [client-data id is-me is-bot] :fx/keys [event] :as data}]
  (future
    (try
      (if (or is-me is-bot)
        (let [mode (if (contains? data :value)
                     (:value data)
                     (not event))]
          (update-battle-status client-data data (assoc (:battle-status id) :mode mode) (:team-color id)))
        (client-message client-data (str "FORCESPECTATORMODE " (:username id))))
      (catch Exception e
        (log/error e "Error updating battle spectate")))))

(defmethod event-handler ::battle-side-changed
  [{:keys [client-data id sides] :fx/keys [event] :as data}]
  (future
    (try
      (let [side (get (clojure.set/map-invert sides) event)]
        (if (not= side (-> id :battle-status :side))
          (let [old-side (-> id :battle-status :side)]
            (log/info "Updating side for" id "from" old-side "(" (get sides old-side) ") to" side "(" event ")")
            (update-battle-status client-data data (assoc (:battle-status id) :side side) (:team-color id)))
          (log/debug "No change for side")))
      (catch Exception e
        (log/error e "Error updating battle side")))))

(defmethod event-handler ::battle-team-changed
  [{:keys [client-data id] :fx/keys [event] :as data}]
  (future
    (try
      (when-let [player-id (try (Integer/parseInt event) (catch Exception _e))]
        (if (not= player-id (-> id :battle-status :id))
          (do
            (log/info "Updating team for" id "from" (-> id :battle-status :side) "to" player-id)
            (update-team client-data id data player-id))
          (log/debug "No change for team")))
      (catch Exception e
        (log/error e "Error updating battle team")))))

(defmethod event-handler ::battle-ally-changed
  [{:keys [client-data id] :fx/keys [event] :as data}]
  (future
    (try
      (when-let [ally (try (Integer/parseInt event) (catch Exception _e))]
        (if (not= ally (-> id :battle-status :ally))
          (do
            (log/info "Updating ally for" id "from" (-> id :battle-status :ally) "to" ally)
            (update-ally client-data id data ally))
          (log/debug "No change for ally")))
      (catch Exception e
        (log/error e "Error updating battle ally")))))

(defmethod event-handler ::battle-handicap-change
  [{:keys [client-data id] :fx/keys [event] :as data}]
  (future
    (try
      (when-let [handicap (max 0
                            (min 100
                              event))]
        (if (not= handicap (-> id :battle-status :handicap))
          (do
            (log/info "Updating handicap for" id "from" (-> id :battle-status :ally) "to" handicap)
            (update-handicap client-data id data handicap))
          (log/debug "No change for handicap")))
      (catch Exception e
        (log/error e "Error updating battle handicap")))))

(defmethod event-handler ::battle-color-action
  [{:keys [client-data id is-me] :fx/keys [^javafx.event.Event event] :as opts}]
  (future
    (try
      (let [^javafx.scene.control.ColorPicker source (.getSource event)
            javafx-color (.getValue source)
            color-int (u/javafx-color-to-spring javafx-color)]
        (when is-me
          (swap! *state assoc :preferred-color color-int))
        (update-color client-data id opts color-int))
      (catch Exception e
        (log/error e "Error updating battle color")))))

(defmethod event-handler ::battle-balance [{:keys [client-data channel-name]}]
  @(event-handler
     {:event/type ::send-message
      :channel-name channel-name
      :client-data client-data
      :message (str "!balance")}))

(defmethod event-handler ::battle-fix-colors
  [{:keys [am-host client-data channel-name] :as e}]
  (if am-host
    (->> e
         battle-players-and-bots
         (filter (comp :mode :battle-status)) ; remove spectators
         (map-indexed
           (fn [_i {:keys [battle-status] :as id}]
             (let [is-bot (boolean (:bot-name id))
                   is-me (= (:username e) (:username id))
                   color (color/team-color battle-status)
                   opts {:id id :is-me is-me :is-bot is-bot}]
               (update-battle-status client-data opts battle-status color))))
         doall)
    @(event-handler
       {:event/type ::send-message
        :channel-name channel-name
        :client-data client-data
        :message (str "!fixcolors")})))


(defmethod task-handler ::update-rapid
  [{:keys [engine-version mod-name spring-isolation-dir] :as e}]
  (swap! *state assoc :rapid-update true)
  (let [before (u/curr-millis)
        {:keys [by-spring-root file-cache] :as state} @*state ; TODO remove deref
        engines (-> by-spring-root (get (fs/canonical-path spring-isolation-dir)) :engines)
        engine-version (or engine-version (:engine-version state))
        spring-isolation-dir (or spring-isolation-dir (:spring-isolation-dir state))
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
             :rapid-id
             (or (when mod-name
                   (cond
                     (string/includes? mod-name "Beyond All Reason") "byar:test"
                     :else nil))
                 "i18n:test")
             ; TODO how else to init rapid without download...
             :engine-file (:file engine-details)
             :spring-isolation-dir spring-isolation-dir})))
      (log/warn "No engine details to do rapid init"))
    (log/info "Updating rapid versions in" spring-isolation-dir)
    (let [rapid-repos (rapid/repos spring-isolation-dir)
          _ (log/info "Found" (count rapid-repos) "rapid repos")
          rapid-repo-files (map (partial rapid/version-file spring-isolation-dir) rapid-repos)
          new-files (->> rapid-repo-files
                         (map fs/file-cache-data)
                         fs/file-cache-by-path)
          rapid-versions (->> rapid-repo-files
                              (filter
                                (fn [f]
                                  (let [path (fs/canonical-path f)
                                        prev-time (or (-> file-cache (get path) :last-modified) 0)
                                        curr-time (or (-> new-files (get path) :last-modified) Long/MAX_VALUE)]
                                    (or
                                      (< prev-time curr-time)
                                      (:force e)))))
                              (mapcat rapid/rapid-versions)
                              (filter :version)
                              (sort-by :version version/version-compare)
                              reverse)
          _ (log/info "Found" (count rapid-versions) "rapid versions")
          rapid-data-by-hash (->> rapid-versions
                              (map (juxt :hash identity))
                              (into {}))
          rapid-data-by-id (->> rapid-versions
                                (map (juxt :id identity))
                                (into {}))
          rapid-data-by-version (->> rapid-versions
                                     (map (juxt :version identity))
                                     (into {}))]
      (swap! *state
        (fn [state]
          (-> state
              (assoc :rapid-repos rapid-repos)
              (update :rapid-data-by-hash merge rapid-data-by-hash)
              (update :rapid-data-by-id merge rapid-data-by-id)
              (update :rapid-data-by-version merge rapid-data-by-version)
              (update :rapid-versions (fn [old-versions]
                                        (set (concat old-versions rapid-versions))))
              (update :file-cache merge new-files))))
      (log/info "Updated rapid repo data in" (- (u/curr-millis) before) "ms"))
    (add-task! *state
      (merge
        {::task-type ::update-rapid-packages}
        (when spring-isolation-dir
          {:spring-isolation-dir spring-isolation-dir})))))

(defmethod task-handler ::update-rapid-packages
  [{:keys [spring-isolation-dir]}]
  (swap! *state assoc :rapid-update true)
  (let [{:keys [rapid-data-by-hash] :as state} @*state
        spring-isolation-dir (or spring-isolation-dir (:spring-isolation-dir state))
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
          (apply fs/update-file-cache! *state (rapid/sdp-files spring-isolation-dir))
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


(defmethod event-handler ::http-download
  [{:keys [dest url]}]
  (http/download-file *state url dest))

(defn- download-http-resource [{:keys [downloadable spring-isolation-dir]}]
  (log/info "Request to download" downloadable)
  (future
    (deref
      (event-handler
        {:event/type ::http-download
         :dest (resource/resource-dest spring-isolation-dir downloadable)
         :url (:download-url downloadable)}))
    (case (:resource-type downloadable)
      ::map (reconcile-maps-all-spring-roots)
      ::mod (reconcile-mods-all-spring-roots)
      ::engine (reconcile-engines-all-spring-roots)
      nil)))

(defmethod task-handler ::http-downloadable
  [task]
  @(download-http-resource task))

(defmethod task-handler ::download-and-extract
  [{:keys [downloadable spring-isolation-dir] :as task}]
  @(download-http-resource task)
  (let [download-file (resource/resource-dest spring-isolation-dir downloadable)
        extract-file (when download-file
                       (io/file spring-isolation-dir "engine" (fs/filename download-file)))]
    @(event-handler
       (assoc task
              :event/type ::extract-7z
              :file download-file
              :dest extract-file))))

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

(defn search-springfiles
  "Search springfiles.com for the given resource name, returns a string mirror url for the resource,
  or nil if not found."
  [{:keys [category springname]}]
  (log/info "Searching springfiles for" springname)
  (let [result (->> (clj-http/get "https://api.springfiles.com/json.php"
                      {:query-params
                       (merge
                         {:springname springname
                          :nosensitive "on"
                          :category (or category "**")})
                       :as :json})
                    :body
                    first)]
    (log/info "First result for" springname "search on springfiles:" result)
    (when-let [mirrors (->> result :mirrors (filter some?) (remove #(string/includes? % "spring1.admin-box.com")) seq)]
      {:filename (:filename result)
       :mirrors mirrors})))

(defmethod task-handler ::search-springfiles
  [{:keys [springname] :as e}]
  (if-not (string/blank? springname)
    (let [search-result (search-springfiles e)]
      (log/info "Found details for" springname "on springfiles" search-result)
      (swap! *state assoc-in [:springfiles-search-results springname] search-result)
      search-result)
    (log/warn "No springname to search springfiles" e)))

(defmethod task-handler ::download-springfiles
  [{:keys [resource-type search-result springname spring-isolation-dir]}]
  (if-let [{:keys [filename mirrors] :as search-result} (or search-result
                                                            (search-springfiles springname))]
    (do
      (swap! *state assoc-in [:springfiles-search-results springname] search-result)
      (log/info "Found details for" springname "on springfiles" search-result)
      (add-task! *state
        {::task-type ::http-downloadable
         :downloadable {:download-url (rand-nth mirrors)
                        :resource-filename filename
                        :resource-type resource-type}
         :spring-isolation-dir spring-isolation-dir}))
    (log/info "No mirror to download" springname "on springfiles")))


(defmethod event-handler ::extract-7z
  [{:keys [file dest]}]
  (future
    (fs/update-file-cache! *state file dest)
    (let [path (fs/canonical-path file)]
      (try
        (swap! *state assoc-in [:extracting path] true)
        (if dest
          (fs/extract-7z-fast file dest)
          (fs/extract-7z-fast file))
        (reconcile-engines-all-spring-roots)
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

(def hakora-maps-download-source
  {:download-source-name "Hakora Maps"
   :url "http://www.hakora.xyz/files/springrts/maps"
   :resources-fn http/html-downloadables})

(def download-sources
  [springfiles-maps-download-source
   hakora-maps-download-source
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


(defn update-download-source
  [{:keys [resources-fn url download-source-name] :as source}]
  (log/info "Getting resources for possible download from" download-source-name "at" url)
  (let [now (u/curr-millis)
        last-updated (or (-> *state deref :downloadables-last-updated (get url)) 0)] ; TODO remove deref
    (if (or (< downloadable-update-cooldown (- now last-updated))
            (:force source))
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
                   (merge
                     (->> old
                          (remove (comp #{download-source-name} :download-source-name second))
                          (into {}))
                     downloadables-by-url)))
          downloadables-by-url))
      (log/info "Too soon to check downloads from" url))))

(defmethod task-handler ::update-downloadables
  [source]
  (update-download-source source))


(defmethod event-handler ::clear-map-and-mod-details
  [{:keys [map-resource mod-resource]}]
  (let [map-key (resource/details-cache-key map-resource)
        mod-key (resource/details-cache-key mod-resource)]
    (swap! *state
      (fn [state]
        (cond-> state
                map-key
                (update :map-details cache/miss map-key nil)
                mod-key
                (update :mod-details cache/miss mod-key nil))))
    (add-task! *state {::task-type ::reconcile-engines})))


(defmethod event-handler ::import-source-change
  [{:fx/keys [event]}]
  (swap! *state assoc :import-source-name (:import-source-name event)))


(defmethod event-handler ::assoc
  [{:fx/keys [event] :as e}]
  (swap! *state assoc (:key e) (or (:value e) event)))

(defmethod event-handler ::assoc-in
  [{:fx/keys [event] :keys [path value] :or {value event}}]
  (swap! *state assoc-in path value))

(defmethod event-handler ::dissoc
  [e]
  (log/info "Dissoc" (:key e))
  (swap! *state dissoc (:key e)))

(defmethod event-handler ::dissoc-in
  [{:keys [path]}]
  (log/info "Dissoc" path)
  (if (= 1 (count path))
    (swap! *state dissoc (first path))
    (swap! *state update-in (drop-last path) dissoc (last path))))


(defmethod event-handler ::singleplayer-engine-changed [{:fx/keys [event] :keys [engine-version]}]
  (let [engine-version (or engine-version event)]
    (swap! *state
           (fn [server]
             (-> server
                 (assoc :engine-version engine-version)
                 (assoc-in [:by-server :local :battles :singleplayer :battle-version] engine-version))))))

(defmethod event-handler ::singleplayer-mod-changed [{:fx/keys [event] :keys [mod-name]}]
  (let [mod-name (or mod-name event)]
    (swap! *state
           (fn [server]
             (-> server
                 (assoc :mod-name mod-name)
                 (assoc-in [:by-server :local :battles :singleplayer :battle-modname] mod-name))))))

(defmethod event-handler ::singleplayer-map-changed [{:fx/keys [event] :keys [map-name]}]
  (let [map-name (or map-name event)]
    (swap! *state
           (fn [server]
             (-> server
                 (assoc :map-name map-name)
                 (assoc-in [:by-server :local :battles :singleplayer :battle-map] map-name))))))


(defmethod event-handler ::scan-imports
  [{:keys [sources] :or {sources (fx.import/import-sources (:extra-import-sources @*state))}}]
  (doseq [import-source sources]
    (add-task! *state (merge
                        {::task-type ::scan-imports}
                        import-source))))

(defmethod task-handler ::scan-all-imports [task]
  (event-handler (assoc task ::task-type ::scan-imports)))


(defmethod event-handler ::download-source-change
  [{:fx/keys [event]}]
  (swap! *state assoc :download-source-name (:download-source-name event)))


(defmethod event-handler ::update-downloadables
  [opts]
  (doseq [download-source download-sources]
    (add-task! *state (merge
                        {::task-type ::update-downloadables
                         :force (:force opts)}
                        download-source))))


(defmethod event-handler ::spring-settings-refresh
  [_e]
  (future
    (log/info "Refreshing spring settings backups dir")
    (try
      (let [files (fs/list-files (fs/spring-settings-root))]
        (apply fs/update-file-cache! *state files))
      (catch Exception e
        (log/error e "Error refreshing spring settings backups dir")))))

(defmethod event-handler ::spring-settings-copy
  [{:keys [confirmed dest-dir file-cache source-dir]}]
  (future
    (try
      (cond
        (and (not confirmed)
             (not= (fs/file-exists? file-cache dest-dir)  ; cache is out of date
                   (fs/exists? dest-dir)))
        (do
          (log/warn "File cache was out of date for" dest-dir)
          (fs/update-file-cache! *state dest-dir))
        (and (fs/exists? dest-dir) (not confirmed))  ; fail safe
        (log/warn "Spring settings backup called to existing dir"
                  dest-dir "but no confirmation")
        :else
        (let [results (spring/copy-spring-settings source-dir dest-dir)]
          (swap! *state assoc-in [:spring-settings :results (fs/canonical-path source-dir)] results)))
      (catch Exception e
        (log/error e "Error copying Spring settings from" source-dir "to" dest-dir)))))


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
    (swap! *state
      (fn [state]
        (cond-> state
          true (assoc :selected-replay-file (:file event))
          (:id event) (assoc :selected-replay-id (:id event)))))))


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
        (assoc :game-type (replay/replay-game-type player-counts)))))

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


(defmethod event-handler ::main-window-on-close-request [{:keys [standalone] :as e}]
  (log/debug "Main window close request" e)
  (when standalone
    (System/exit 0)))

(defmethod event-handler ::my-channels-tab-action [e]
  (log/info e))

(defmethod event-handler ::send-message [{:keys [channel-name client-data message server-key] :as e}]
  (future
    (try
      (swap! *state update-in [:by-server server-key :message-drafts] dissoc channel-name)
      (cond
        (string/blank? channel-name)
        (log/info "Skipping message" (pr-str message) "to empty channel" (pr-str channel-name))
        (string/blank? message)
        (log/info "Skipping empty message" (pr-str message) "to" (pr-str channel-name))
        :else
        (cond
          (re-find #"^/ingame" message) (client-message client-data "GETUSERINFO")
          (re-find #"^/msg" message)
          (let [[_all user message] (re-find #"^/msg\s+([^\s]+)\s+(.+)" message)]
            @(event-handler
               (merge e
                 {:event/type ::send-message
                  :channel-name (str "@" user)
                  :message message})))
          (re-find #"^/rename" message)
          (let [[_all new-username] (re-find #"^/rename\s+([^\s]+)" message)]
            (client-message client-data (str "RENAMEACCOUNT " new-username)))
          :else
          (let [[private-message username] (re-find #"^@(.*)$" channel-name)
                unified (-> client-data :compflags (contains? "u"))]
            (if-let [[_all message] (re-find #"^/me (.*)$" message)]
              (if private-message
                (client-message client-data (str "SAYPRIVATEEX " username " " message))
                (if (and (not unified) (u/battle-channel-name? channel-name))
                  (client-message client-data (str "SAYBATTLEEX " message))
                  (client-message client-data (str "SAYEX " channel-name " " message))))
              (if private-message
                (client-message client-data (str "SAYPRIVATE " username " " message))
                (if (and (not unified) (u/battle-channel-name? channel-name))
                  (client-message client-data (str "SAYBATTLE " message))
                  (client-message client-data (str "SAY " channel-name " " message))))))))
      (catch Exception e
        (log/error e "Error sending message" message "to channel" channel-name)))))


(defmethod event-handler ::selected-item-changed-channel-tabs [{:fx/keys [^Tab event]}]
  (swap! *state assoc :selected-tab-channel (.getId event)))

(defmethod event-handler ::selected-item-changed-main-tabs [{:fx/keys [^Tab event]}]
  (swap! *state assoc :selected-tab-main (.getId event)))

(defmethod event-handler ::send-console [{:keys [client-data message server-key]}]
  (future
    (try
      (swap! *state assoc-in [:by-server server-key :console-message-draft] "")
      (when-not (string/blank? message)
        (client-message client-data message))
      (catch Exception e
        (log/error e "Error sending message" message "to server")))))


(defn multi-server-tab
  [state]
  (merge
    {:fx/type fx.battle/multi-battle-view-keys}
    state))

(defmethod event-handler ::selected-item-changed-server-tabs [{:fx/keys [^Tab event]}]
  (swap! *state assoc :selected-server-tab (.getId event)))

(defmethod event-handler ::matchmaking-list-all [{:keys [client-data]}]
  (client-message client-data "c.matchmaking.list_all_queues"))

(defmethod event-handler ::matchmaking-list-my [{:keys [client-data]}]
  (client-message client-data "c.matchmaking.list_my_queues"))

(defmethod event-handler ::matchmaking-leave-all [{:keys [client-data]}]
  (client-message client-data "c.matchmaking.leave_all_queues"))

(defmethod event-handler ::matchmaking-join [{:keys [client-data queue-id queue-name]}]
  (client-message client-data (str "c.matchmaking.join_queue " (str queue-id ":" queue-name))))

(defmethod event-handler ::matchmaking-leave [{:keys [client-data queue-id]}]
  (client-message client-data (str "c.matchmaking.leave_queue " queue-id)))

(defmethod event-handler ::matchmaking-ready [{:keys [client-data queue-id]}]
  (client-message client-data (str "c.matchmaking.ready"))
  (swap! *state assoc-in [:matchmaking-queues queue-id :ready-check] false))

(defmethod event-handler ::matchmaking-decline [{:keys [client-data queue-id]}]
  (client-message client-data (str "c.matchmaking.decline"))
  (swap! *state assoc-in [:matchmaking-queues queue-id :ready-check] false))


(def state-watch-chimers
  [[:battle-map-details-watcher battle-map-details-watcher]
   [:battle-mod-details-watcher battle-mod-details-watcher]
   ;[:replay-map-and-mod-details-watcher replay-map-and-mod-details-watcher]
   [:update-battle-status-sync-watcher update-battle-status-sync-watcher]])


(defn ipc-handler
  ([req]
   (ipc-handler *state req))
  ([state-atom req]
   (log/info "IPC handler request" req)
   (cond
     (= "/replay" (:uri req))
     (let [path (-> req :params :path)]
       (if-let [file (fs/file path)]
         (let [parsed-replay (replay/parse-replay file)]
           (log/info "Loading replay from IPC" path)
           (swap! state-atom
             (fn [state]
               (-> state
                   (assoc :show-replays true
                          :selected-replay parsed-replay
                          :selected-replay-file file)
                   (assoc-in [:parsed-replays-by-path (fs/canonical-path file)] parsed-replay)))))
         (log/warn "Unable to coerce to file" path)))
     :else
     (log/info "Nothing to do for IPC request" req))
   {:status 200
    :headers {"content-type" "text/plain"}
    :body "ok"}))

(defn start-ipc-server
  "Starts an HTTP server so that replays and battles can be loaded into running instance."
  []
  (if (u/is-port-open? u/ipc-port)
    (do
      (log/info "Starting IPC server on port" u/ipc-port)
      (let [server (aleph-http/start-server
                     (-> (partial ipc-handler *state)
                         wrap-keyword-params
                         wrap-params)
                     {:socket-address
                      (InetSocketAddress.
                        (InetAddress/getByName nil)
                        u/ipc-port)})]
        (swap! *state assoc :ipc-server server)))
    (log/warn "IPC port unavailable" u/ipc-port)))


(defn- init
  "Things to do on program init, or in dev after a recompile."
  ([state-atom]
   (init state-atom nil))
  ([state-atom {:keys [skip-tasks]}]
   (try
     (let [custom-css-file (fs/file (fs/app-root) "custom-css.edn")]
       (when-not (fs/exists? custom-css-file)
         (log/info "Creating initial custom CSS file" custom-css-file)
         (spit custom-css-file skylobby.fx/default-style-data)))
     (let [custom-css-file (fs/file (fs/app-root) "custom.css")]
       (when-not (fs/exists? custom-css-file)
         (log/info "Creating initial custom CSS file" custom-css-file)
         (spit custom-css-file (slurp (::css/url skylobby.fx/default-style)))))
     (catch Exception e
       (log/error e "Error creating custom CSS file")))
   (log/info "Initializing periodic jobs")
   (let [task-chimers (->> task/task-kinds
                           (map (partial tasks-chimer-fn state-atom))
                           doall)
         state-chimers (->> state-watch-chimers
                            (map (fn [[k watcher-fn]]
                                   (state-change-chimer-fn state-atom k watcher-fn)))
                            doall)
         truncate-messages-chimer (truncate-messages-chimer-fn state-atom)
         check-app-update-chimer (check-app-update-chimer-fn state-atom)
         spit-app-config-chimer (spit-app-config-chimer-fn state-atom)
         profile-print-chimer (profile-print-chimer-fn state-atom)]
     (add-watchers state-atom)
     (when-not skip-tasks
       (add-task! state-atom {::task-type ::reconcile-engines})
       (add-task! state-atom {::task-type ::reconcile-mods})
       (add-task! state-atom {::task-type ::reconcile-maps})
       (add-task! state-atom {::task-type ::refresh-replays})
       (add-task! state-atom {::task-type ::update-rapid})
       (event-handler {:event/type ::update-downloadables})
       (event-handler {:event/type ::scan-imports}))
     (log/info "Finished periodic jobs init")
     (start-ipc-server)
     {:chimers
      (concat
        task-chimers
        state-chimers
        [truncate-messages-chimer
         check-app-update-chimer
         spit-app-config-chimer
         profile-print-chimer])})))

(defn standalone-replay-init [state-atom]
  (let [task-chimers (->> task/task-kinds
                          (map (partial tasks-chimer-fn state-atom))
                          doall)
        state-chimers (->> state-watch-chimers
                           (map (fn [[k watcher-fn]]
                                  (state-change-chimer-fn state-atom k watcher-fn)))
                           doall)]
    (add-watchers state-atom)
    (add-task! state-atom {::task-type ::reconcile-engines})
    (add-task! state-atom {::task-type ::reconcile-mods})
    (add-task! state-atom {::task-type ::reconcile-maps})
    (add-task! state-atom {::task-type ::update-rapid})
    (event-handler {:event/type ::update-downloadables})
    (event-handler {:event/type ::scan-imports})
    (log/info "Finished standalone replay init")
    {:chimers
     (concat
       task-chimers
       state-chimers)}))


(defn auto-connect-servers [state-atom]
  (let [{:keys [logins servers]} @state-atom]
    (doseq [[server-url :as server] (filter (comp :auto-connect second) servers)]
      (when-let [{:keys [password username] :as login} (get logins server-url)]
        (when (and password username)
          (let [server-key (u/server-key {:server-url server-url
                                          :username username})]
            (event-handler
              (merge
                {:event/type ::connect
                 :server server
                 :server-key server-key}
                login))))))))


(defn init-async [state-atom]
  (future
    (log/info "Sleeping to let JavaFX start")
    (async/<!! (async/timeout wait-before-init-tasks-ms))
    (init state-atom)))
