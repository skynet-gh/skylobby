(ns spring-lobby
  (:require
    [cheshire.core :as json]
    [chime.core :as chime]
    [clj-http.client :as clj-http]
    [cljfx.api :as fx]
    [cljfx.css :as css]
    [clojure.core.async :as async]
    [clojure.core.cache :as cache]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.set]
    [clojure.string :as string]
    crypto.random
    hashp.core
    java-time
    [manifold.deferred :as deferred]
    [manifold.stream :as s]
    [me.raynes.fs :as raynes-fs]
    [org.httpkit.server :as http-kit]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.params :refer [wrap-params]]
    [skylobby.battle :as battle]
    [skylobby.color :as color]
    [skylobby.discord :as discord]
    [skylobby.fs :as fs]
    [skylobby.fs.sdfz :as replay]
    [skylobby.fs.smf :as fs.smf]
    skylobby.fx
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.color :as fx.color]
    [skylobby.fx.download :as fx.download :refer [download-sources-by-name]]
    [skylobby.fx.engine-sync :as fx.engine-sync]
    [skylobby.fx.event.battle :as fx.event.battle]
    [skylobby.fx.event.chat :as fx.event.chat]
    [skylobby.fx.event.direct :as fx.event.direct]
    [skylobby.fx.event.minimap :as fx.event.minimap]
    [skylobby.fx.import :as fx.import]
    [skylobby.git :as git]
    [skylobby.http :as http]
    [skylobby.rapid :as rapid]
    [skylobby.resource :as resource]
    [skylobby.server :as server]
    [skylobby.spring.script :as spring-script]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [spring-lobby.client :as client]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.client.message :as message]
    [spring-lobby.client.util :as cu]
    [spring-lobby.spring :as spring]
    [taoensso.nippy :as nippy]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte]
    [version-clj.core :as version])
  (:import
    (java.awt Desktop Desktop$Action)
    (java.io File)
    (java.lang ProcessBuilder)
    (java.net URL)
    (java.util List)
    (java.util.regex Matcher)
    (javafx.application Platform)
    (javafx.event Event)
    (javafx.scene Parent)
    (javafx.scene.canvas Canvas)
    (javafx.scene.control ColorPicker ScrollBar Tab)
    (javafx.scene.input KeyCode KeyCodeCombination KeyCombination KeyEvent MouseEvent ScrollEvent)
    (javafx.scene.media Media MediaPlayer)
    (javafx.scene Node)
    (javafx.stage DirectoryChooser FileChooser)
    (manifold.stream SplicedStream)
    (org.fxmisc.flowless VirtualizedScrollPane))
  (:gen-class))


(set! *warn-on-reflection* true)


(declare download-http-resource)


(def wait-init-tasks-ms 20000)


(def map-browse-image-size 162)
(def map-browse-box-height 224)


(def maps-batch-size 5)
(def minimap-batch-size 3)
(def mods-batch-size 5)
(def replays-batch-size 10)


; https://github.com/clojure/clojure/blob/28efe345d5e995dc152a0286fb0be81443a0d9ac/src/clj/clojure/instant.clj#L274-L279
(defn- read-file-tag [cs]
  (io/file cs))
(defn- read-url-tag [spec]
  (URL. spec))

; https://github.com/clojure/clojure/blob/0754746f476c4ddf6a6b699d9547830b2fdad17c/src/clj/clojure/core.clj#L7755-L7761
(def custom-readers
  {'spring-lobby/java.io.File #'spring-lobby/read-file-tag
   'spring-lobby/java.net.URL #'spring-lobby/read-url-tag})

; https://stackoverflow.com/a/23592006
(defmethod print-method File [f ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.io.File " (pr-str (fs/canonical-path f)))))
(defmethod print-method URL [url ^java.io.Writer w]
  (.write w (str "#spring-lobby/java.net.URL " (pr-str (str url)))))


; https://github.com/ptaoussanis/nippy#custom-types-v21
(nippy/extend-freeze File :skylobby/file
  [^File x data-output]
  (.writeUTF data-output (fs/canonical-path x)))
(nippy/extend-thaw :skylobby/file
  [data-input]
  (io/file (.readUTF data-input)))

(nippy/extend-freeze URL :skylobby/url
  [^File x data-output]
  (.writeUTF data-output (str x)))
(nippy/extend-thaw :skylobby/url
  [data-input]
  (URL. (.readUTF data-input)))


(defn nippy-filename [edn-filename]
  (when edn-filename
    (string/replace edn-filename #"\.edn$" ".bin")))

(defn slurp-config-edn
  "Returns data loaded from a .edn file in this application's root directory."
  [{:keys [filename nippy]}]
  (try
    (let [nippy-file (fs/config-file (nippy-filename filename))]
      (if (and nippy (fs/exists? nippy-file))
        (do
          (log/info "Slurping config nippy from" nippy-file)
          (nippy/thaw-from-file nippy-file))
        (let [config-file (fs/config-file filename)]
          (log/info "Slurping config edn from" config-file)
          (when (fs/exists? config-file)
            (let [data (->> config-file slurp (edn/read-string {:readers custom-readers}))]
              (if (map? data)
                (do
                  (try
                    (log/info "Backing up config file that we could parse")
                    (fs/copy config-file (fs/config-file (str filename ".known-good")))
                    (catch Exception e
                      (log/error e "Error backing up config file")))
                  data)
                (do
                  (log/warn "Config file data from" filename "is not a map")
                  {})))))))
    (catch Exception e
      (log/warn e "Exception loading app edn file" filename)
      (try
        (log/info "Copying bad config file for debug")
        (fs/copy (fs/config-file filename) (fs/config-file (str filename ".debug")))
        (catch Exception e
          (log/warn e "Exception copying bad edn file" filename)))
      {})))


(defn- initial-file-events []
  (clojure.lang.PersistentQueue/EMPTY))


(def config-keys
  [:auto-get-resources :auto-get-replay-resources :auto-launch :auto-rejoin-battle :auto-refresh-replays :battle-as-tab :battle-layout :battle-password :battle-players-color-type :battle-players-display-type :battle-port :battle-resource-details :battle-title :battles-layout :battles-table-images :bot-name :bot-version :chat-auto-complete :chat-auto-scroll :chat-color-username :chat-font-size :chat-highlight-username :chat-highlight-words :client-id-override :client-id-type
   :console-auto-scroll :console-ignore-message-types :css :debug-spring :direct-connect-chat-commands :direct-connect-ip :direct-connect-password :direct-connect-port :direct-connect-protocol :direct-connect-username :disable-tasks :disable-tasks-while-in-game :divider-positions :engine-overrides :extra-import-sources
   :extra-replay-sources :filter-replay
   :filter-replay-type :filter-replay-max-players :filter-replay-min-players :filter-users :focus-chat-on-message
   :friend-users :hide-barmanager-messages :hide-empty-battles :hide-joinas-spec :hide-locked-battles :hide-passworded-battles :hide-spads-messages :hide-vote-messages :highlight-tabs-with-new-battle-messages :highlight-tabs-with-new-chat-messages :ignore-users :increment-ids :interleave-ally-player-ids :ipc-server-enabled :ipc-server-port :join-battle-as-player :leave-battle-on-close-window :logins :minimap-size
   :music-dir :music-stopped :music-volume :mute :mute-ring :my-channels :password :players-table-columns :pop-out-battle :preferred-color :preferred-factions :prevent-non-host-rings :rapid-repo :rapid-spring-root :ready-on-unspec :refresh-replays-after-game :replay-source-enabled
   :replays-window-dedupe :replays-window-details :ring-on-auto-unspec :ring-sound-file :ring-volume :scenarios-engine-version :scenarios-spring-root :server :servers :show-accolades :show-battle-preview :show-closed-battles :show-hidden-modoptions :show-spring-picker :show-team-skills :show-vote-log :spring-isolation-dir
   :spring-settings :uikeys :unready-after-game :use-default-ring-sound :use-git-mod-version :user-agent-override :username :windows-as-tabs :window-states])


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

(defn select-rapid [state]
  (select-keys state
    [:rapid-by-spring-root]))

(defn- select-replays [state]
  (select-keys state
    [:online-bar-replays :replays-tags :replays-watched]))

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
   {:select-fn select-rapid
    :filename "rapid.edn"
    :nippy true}
   {:select-fn select-replays
    :filename "replays.edn"}])

(def parsed-replays-config
  {:select-fn #(select-keys % [:invalid-replay-paths :parsed-replays-by-path])
   :filename "parsed-replays.edn"
   :nippy true})


(defn initial-state []
  (merge
    {:auto-get-resources true
     :auto-get-replay-resources true
     :auto-rejoin-battle true
     :battle-as-tab true
     :battle-layout "horizontal"
     :battles-layout "horizontal"
     :battle-players-color-type "player"
     :battle-resource-details true
     :chat-auto-complete false
     :chat-color-username true
     :chat-highlight-username true
     :disable-tasks-while-in-game true
     :hide-barmanager-messages true
     :highlight-tabs-with-new-battle-messages true
     :highlight-tabs-with-new-chat-messages true
     :increment-ids true
     :interleave-ally-player-ids true
     :is-java (u/is-java? (u/process-command))
     :ipc-server-enabled false
     :ipc-server-port u/default-ipc-port
     :leave-battle-on-close-window true
     :players-table-columns {:skill true
                             :ally true
                             :team true
                             :color true
                             :status true
                             :spectator true
                             :faction true
                             :country true
                             :bonus true}
     :ready-on-unspec true
     :refresh-replays-after-game true
     :show-battle-preview true
     :show-spring-picker true
     :spring-isolation-dir (fs/default-spring-root)
     :servers u/default-servers
     :use-default-ring-sound true
     :windows-as-tabs true}
    (apply
      merge
      (doall
        (map slurp-config-edn state-to-edn)))
    (slurp-config-edn parsed-replays-config)
    {:tasks-by-kind {}
     :current-tasks (->> task/task-kinds (map (juxt identity (constantly nil))) (into {}))
     :file-events (initial-file-events)
     :minimap-type (first fx.battle/minimap-types)
     :replay-minimap-type (first fx.battle/minimap-types)
     :map-details (cache/lru-cache-factory (sorted-map) :threshold 8)
     :mod-details (cache/lru-cache-factory (sorted-map) :threshold 8)
     :replay-details (cache/lru-cache-factory (sorted-map) :threshold 4)
     :chat-auto-scroll true
     :console-auto-scroll true}))


(defn cache-factory-with-threshold [factory threshold]
  (fn [data]
    (factory data :threshold threshold)))


(def ^:dynamic *state (atom {}))
(def ^:dynamic *ui-state
  (atom (fx/create-context {} (cache-factory-with-threshold cache/lru-cache-factory 2048))))

(def main-stage-atom (atom nil))


(defn add-ui-state-watcher [state-atom ui-state-atom]
  (log/info "Adding state to UI state watcher")
  (remove-watch state-atom :ui-state)
  (add-watch state-atom :ui-state
    (fn [_k _state-atom old-state new-state]
      (tufte/profile {:dynamic? true
                      :id ::state-watcher}
        (tufte/p :ui-state
          (when (not= old-state new-state)
            (swap! ui-state-atom fx/reset-context new-state)))))))


(def ^:dynamic disable-update-check false)

(def ^:dynamic main-args nil)


(defn spit-app-edn
  "Writes the given data as edn to the given file in the application directory."
  ([data filename]
   (spit-app-edn data filename nil))
  ([data filename {:keys [nippy pretty]}]
   (let [file (fs/config-file filename)]
     (fs/make-parent-dirs file))
   (if nippy
     (let [file (fs/config-file (nippy-filename filename))]
       (log/info "Saving nippy data to" file)
       (try
         (nippy/freeze-to-file file data)
         (catch Exception e
           (log/error e "Error saving nippy to" file))))
     (let [output (if pretty
                    (with-out-str (pprint (if (map? data)
                                            (into (sorted-map) data)
                                            data)))
                    (pr-str data))
           parsable (try
                      (edn/read-string {:readers custom-readers} output)
                      true
                      (catch Exception e
                        (log/error e "Config EDN for" filename "does not parse, keeping old file")))
           file (fs/config-file (if parsable
                                  filename
                                  (str filename ".bad")))]
       (log/info "Spitting edn to" file)
       (spit file output)))))


(defn- spit-state-config-to-edn [old-state new-state]
  (doseq [{:keys [select-fn filename] :as opts} state-to-edn]
    (try
      (let [old-data (select-fn old-state)
            new-data (select-fn new-state)]
        (when (not= old-data new-data)
          (try
            (spit-app-edn new-data filename opts)
            (catch Exception e
              (log/error e "Exception writing" filename)))))
      (catch Exception e
        (log/error e "Error writing config edn" filename)))))


(defn- read-map-details [{:keys [map-name map-file]}]
  (let [log-map-name (str "'" map-name "'")]
    (try
      (if map-file
        (fs/read-map-data map-file)
        (log/warn "No file found for map" log-map-name))
      (catch Exception e
        (log/error e "Error reading map data from" map-file)))))


(defn- read-mod-data
  ([f]
   (read-mod-data f nil))
  ([f {:keys [delete-invalid-sdp] :as opts}]
   (let [filename (fs/filename f)
         is-sdp (and (string? filename)
                     (string/ends-with? filename ".sdp"))]
     (try
       (let [mod-data
             (if (string/ends-with? (fs/filename f) ".sdp")
               (rapid/read-sdp-mod f opts)
               (fs/read-mod-file f opts))
             name-and-version (u/mod-name-and-version mod-data opts)]
         (merge mod-data name-and-version))
       (catch Exception e
         (log/warn e "Error reading mod details")
         (when (and delete-invalid-sdp is-sdp)
           (log/info "Deleting invalid sdp file" f)
           (raynes-fs/delete f))
         {:file f
          :error true})))))


(defn set-sdd-modinfo-version [modinfo-str mod-version]
  (string/replace
    modinfo-str
    #"version = '[^']+'"
    (Matcher/quoteReplacement
      (str "version = '" mod-version "'"))))

(defn- update-mod
  ([state-atom spring-root-path file]
   (update-mod state-atom spring-root-path file nil))
  ([state-atom spring-root-path file {:keys [use-git-mod-version] :as opts}]
   (when (fs/is-directory? file)
     (try
       (let [
             git-commit-id (try
                             (git/latest-id file)
                             (catch java.io.FileNotFoundException _e
                               (log/warn "Not a git repository at" file))
                             (catch Exception e
                               (log/error e "Error loading git commit id")))
             mod-version (if use-git-mod-version
                           (str "git:" (u/short-git-commit git-commit-id))
                           "$VERSION")]
         (log/info "Setting version in modinfo.lua to" mod-version)
         (let [
               modinfo-file (fs/file file "modinfo.lua")
               contents (slurp modinfo-file)]
           (spit modinfo-file (set-sdd-modinfo-version contents mod-version))))
       (catch Exception e
         (log/error e "Error setting modinfo version for git"))))
   (let [path (fs/canonical-path file)
         mod-data (try
                    (read-mod-data file (assoc opts :modinfo-only false))
                    (catch Exception e
                      (log/error e "Error reading mod data for" file)
                      {:file file
                       :error true}))
         mod-details (select-keys mod-data [:error :file :mod-name :mod-name-only :mod-version ::fs/source :git-commit-id])
         mod-details (assoc mod-details
                            :is-game
                            (boolean
                              (or (:engineoptions mod-data)
                                  (:modoptions mod-data))))]
     (log/info "Read mod:" (with-out-str (pprint (select-keys mod-details [:error :file :is-game :mod-name :mod-name-only :mod-version]))))
     (let [{:keys [mod-details use-git-mod-version]}
           (swap! state-atom update-in [:by-spring-root spring-root-path :mods]
                  (fn [mods]
                    (set
                      (cond->
                        (remove (comp #{path} fs/canonical-path :file) mods)
                        mod-details (conj mod-details)))))
           cached (get mod-details path)]
       (when (and cached
                  (not= (:mod-version cached)
                        (:mod-version mod-data)))
         (task/add-task! *state
           {::task-type ::mod-details
            :mod-name (:mod-name mod-data)
            :mod-file file
            :use-git-mod-version use-git-mod-version})))
     mod-data)))


(defmulti event-handler :event/type)


(defmethod event-handler ::stop-task [{:keys [task-kind ^java.lang.Thread task-thread]}]
  (if task-thread
    (future
      (try
        (.stop task-thread)
        (catch Exception e
          (log/error e "Error stopping task" task-kind))
        (catch Throwable t
          (log/error t "Critical error stopping task" task-kind))))
    (log/warn "No thread found for task kind" task-kind)))

(defn remove-task [task tasks]
  (disj (set tasks) task))

(defmethod event-handler ::cancel-task [{:keys [task]}]
  (if-let [kind (task/task-kind task)]
    (swap! *state update-in [:tasks-by-kind kind] (partial remove-task task))
    (log/warn "Unable to determine task kind to cancel for" task)))


(defn- battle-map-details-relevant-keys [state]
  (-> state
      (select-keys [:by-server :by-spring-root :map-details :servers :spring-isolation-dir])
      (update :by-server
        (fn [by-server]
          (reduce-kv
            (fn [m k v]
              (assoc m k
                (-> v
                    (select-keys [:battle :battles])
                    (update :battles select-keys [(:battle-id (:battle v))]))))
            {}
            by-server)))))

(defn- battle-mod-details-relevant-data [state]
  (-> state
      (select-keys [:by-server :by-spring-root :mod-details :servers :spring-isolation-dir])
      (update :by-server
        (fn [by-server]
          (reduce-kv
            (fn [m k v]
              (assoc m k
                (-> v
                    (select-keys [:battle :battles])
                    (update :battles select-keys [(:battle-id (:battle v))]))))
            {}
            by-server)))))

(defn- replay-map-and-mod-details-relevant-keys [state]
  (select-keys
    state
    [:by-spring-root :map-details :mod-details :online-bar-replays :parsed-replays-by-path
     :selected-replay-file :selected-replay-id :servers :spring-isolation-dir]))


(defn update-cooldown [state-atom k]
  (swap! state-atom update-in [:cooldowns k]
    (fn [state]
      (-> state
          (update :tries (fnil inc 0))
          (assoc :updated (u/curr-millis))))))

(defn check-cooldown [cooldowns k]
  (if-let [{:keys [tries updated]} (get cooldowns k)]
    (if (and (number? tries) (number? updated))
      (let [cd (< (u/curr-millis)
                  (+ updated (* 1000 (Math/pow 2 tries))))] ; exponential backoff
        (if cd
          (do
            (log/info k "is on cooldown")
            false)
          true))
      true)
    true))

(defn- fix-selected-server-relevant-keys [state]
  (select-keys
    state
    [:server :servers]))

(defn update-battle-status-sync-relevant-data [state]
  (-> state
      (select-keys [:by-server :by-spring-root :mod-details :map-details :servers :spring-isolation-dir])
      (update :by-server
        (fn [by-server]
          (reduce-kv
            (fn [m k v]
              (assoc m k
                (-> v
                    (select-keys [:client-data :battle :battles])
                    (update :battles select-keys [(:battle-id (:battle v))]))))
            {}
            by-server)))))

(defn springfiles-url [springfiles-search-result]
  (let [{:keys [mirrors]} springfiles-search-result]
    (rand-nth mirrors)))


(defn import-dest-is-source? [spring-root {:keys [resource-file] :as importable}]
  (= (fs/canonical-path resource-file)
     (fs/canonical-path (resource/resource-dest spring-root importable))))

(defn server-needs-battle-status-sync-check [server-data]
  (and (get-in server-data [:battle :battle-id])
       (let [username (:username server-data)
             sync-status (get-in server-data [:battle :users username :battle-status :sync])]
         (not= sync-status 1))))

(defn auto-resources-tasks
  [{:keys [battle-changed engine-version map-name mod-name spring-root]}
   {:keys [by-spring-root cooldowns current-tasks downloadables-by-url file-cache http-download importables-by-path rapid-by-spring-root
           springfiles-search-results tasks-by-kind]}]
  (let [
        spring-root-path (fs/canonical-path spring-root)
        {:keys [rapid-data-by-version]} (get rapid-by-spring-root spring-root-path)
        {:keys [engines maps mods]} (get by-spring-root spring-root-path)
        rapid-data (get rapid-data-by-version mod-name)
        rapid-id (:id rapid-data)
        sdp-file (rapid/sdp-file spring-root (rapid/sdp-filename (:hash rapid-data)))
        sdp-file-exists (fs/exists? sdp-file)
        all-tasks (concat (mapcat second tasks-by-kind) (vals current-tasks))
        tasks-by-type (group-by :spring-lobby/task-type all-tasks)
        rapid-task (->> all-tasks
                        (filter (comp #{::rapid-download} ::task-type))
                        (filter (comp #{rapid-id} :rapid-id))
                        first)
        update-rapid-task (->> all-tasks
                               (filter (comp #{::update-rapid ::update-rapid-packages} ::task-type))
                               first)
        importables (vals importables-by-path)
        map-importable (some->> importables
                                (filter (comp #{::map} :resource-type))
                                (filter (partial resource/could-be-this-map? map-name))
                                first)
        map-import-task (->> all-tasks
                             (filter (comp #{::import} ::task-type))
                             (filter (comp (partial resource/same-resource-file? map-importable) :importable))
                             (remove (partial import-dest-is-source? spring-root))
                             first)
        no-map (->> maps
                    (filter (comp #{map-name} :map-name))
                    first
                    not)
        downloadables (vals downloadables-by-url)
        map-downloadable (->> downloadables
                              (filter (comp #{::map} :resource-type))
                              (filter (partial resource/could-be-this-map? map-name))
                              first)
        map-download-task (->> all-tasks
                               (filter (comp #{::http-downloadable} ::task-type))
                               (filter (comp (partial resource/same-resource-filename? map-downloadable) :downloadable))
                               first)
        search-springfiles-map-task (->> all-tasks
                                         (filter (comp #{::search-springfiles} ::task-type))
                                         (filter (comp #{map-name} :springname))
                                         first)
        download-springfiles-map-task (->> all-tasks
                                           (filter (comp #{::download-springfiles ::http-downloadable} ::task-type))
                                           (filter (comp #{map-name} :springname))
                                           first)
        engine-details (spring/engine-details engines engine-version)
        engine-file (:file engine-details)
        engine-importable (some->> importables
                                   (filter (comp #{::engine} :resource-type))
                                   (filter (partial resource/could-be-this-engine? engine-version))
                                   (remove (partial import-dest-is-source? spring-root))
                                   first)
        engine-import-task (->> all-tasks
                                (filter (comp #{::import} ::task-type))
                                (filter (comp (partial resource/same-resource-file? engine-importable) :importable))
                                first)
        engine-downloadable (->> downloadables
                                 (filter (comp #{::engine} :resource-type))
                                 (filter (partial resource/could-be-this-engine? engine-version))
                                 first)
        engine-download-dest (resource/resource-dest spring-root engine-downloadable)
        engine-extract-dest (when engine-download-dest
                              (fs/file spring-root "engine" (fs/filename engine-download-dest)))
        engine-download-task (->> all-tasks
                                  (filter (comp #{::download-and-extract ::http-downloadable} ::task-type))
                                  (filter (comp (partial resource/same-resource-filename? engine-downloadable) :downloadable))
                                  first)
        engine-extract-task (->> all-tasks
                                 (filter (comp #{::extract-7z} ::task-type))
                                 (filter (comp (partial resource/same-resource-filename? engine-downloadable) fs/filename :file))
                                 first)
        mod-downloadable (->> downloadables
                              (filter (comp #{::mod} :resource-type))
                              (filter (partial resource/could-be-this-mod? mod-name))
                              first)
        mod-download-task (->> all-tasks
                               (filter (comp #{::http-downloadable} ::task-type))
                               (filter (comp (partial resource/same-resource-filename? mod-downloadable) :downloadable))
                               first)
        mod-refresh-tasks (->> all-tasks
                               (filter (comp #{::refresh-mods} ::task-type))
                               (map (comp fs/canonical-path :spring-root))
                               set)
        engine-refresh-tasks (->> all-tasks
                                  (filter (comp #{::refresh-engines} ::task-type))
                                  (map (comp fs/canonical-path :spring-root))
                                  set)
        no-mod (->> mods
                    (filter (comp #{mod-name} :mod-name))
                    first
                    not)
        springfiles-search-result (get springfiles-search-results map-name)
        springfiles-url (springfiles-url springfiles-search-result)
        download-source-tasks (->> all-tasks
                                   (filter (comp #{::update-downloadables}))
                                   (map :download-source-name)
                                   set)
        engine-download-source-name (fx.engine-sync/engine-download-source engine-version)
        tasks [(when
                 (not engine-details)
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
                        (not (fs/file-exists? file-cache engine-download-dest)))
                   (do
                     (log/info "Adding task to auto download engine" engine-downloadable)
                     {::task-type ::download-and-extract
                      :downloadable engine-downloadable
                      :spring-isolation-dir spring-root})
                   (and (not engine-importable)
                        engine-downloadable
                        (not engine-download-task)
                        (fs/file-exists? file-cache engine-download-dest)
                        (not engine-extract-task)
                        (not (fs/file-exists? file-cache engine-extract-dest)))
                   (do
                     (log/info "Adding task to extract engine archive" engine-download-dest)
                     {::task-type ::extract-7z
                      :file engine-download-dest
                      :dest engine-extract-dest})
                   (and (not engine-importable)
                        (not engine-downloadable)
                        engine-download-source-name
                        (check-cooldown cooldowns [:download-source engine-download-source-name])
                        (not (contains? download-source-tasks engine-download-source-name)))
                   (do
                     (log/info "Adding task to update download source" engine-download-source-name "looking for" engine-version)
                     (merge
                       {::task-type :spring-lobby/update-downloadables
                        :force true}
                       (get download-sources-by-name engine-download-source-name)))
                   (and (not (contains? engine-refresh-tasks spring-root-path))
                        (fs/file-exists? file-cache engine-extract-dest))
                   (do
                     (log/info "Refreshing engines to pick up" engine-extract-dest)
                     {::task-type ::refresh-engines
                      :force true
                      :priorites [engine-extract-dest]
                      :spring-root spring-root})
                   :else
                   (when engine-version
                     (log/info "Nothing to do to auto get engine" engine-version
                       (with-out-str
                         (pprint
                           {:importable engine-importable
                            :downloadable engine-downloadable
                            :download-source-name engine-download-source-name
                            :download-task engine-download-task
                            :spring-root spring-root}))))))
               (when
                 no-map
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
                   (and map-name
                        (not map-importable)
                        (not map-downloadable)
                        (not (contains? springfiles-search-results map-name))
                        (not search-springfiles-map-task))
                   (do
                     (log/info "Adding task to search springfiles for map" map-name)
                     {::task-type ::search-springfiles
                      :springname map-name
                      :resource-type ::map
                      :spring-isolation-dir spring-root})
                   (and map-name
                        (not map-importable)
                        (not map-downloadable)
                        springfiles-search-result
                        ((fnil < 0) (:tries (get http-download springfiles-url)) resource/max-tries)
                        (not download-springfiles-map-task)
                        (not (::refresh-maps tasks-by-type)))
                   (do
                     (log/info "Adding task to download map" map-name "from springfiles")
                     {::task-type ::download-springfiles
                      :resource-type ::map
                      :springname map-name
                      :search-result (get springfiles-search-results map-name)
                      :spring-isolation-dir spring-root})
                   :else
                   (when map-name
                     (log/info "Nothing to do to auto get map" map-name))))
               (when
                 no-mod
                 (cond
                   (and rapid-id
                        (not rapid-task)
                        (not update-rapid-task)
                        (not (contains? mod-refresh-tasks spring-root-path))
                        engine-file
                        sdp-file-exists)
                   (do
                     (log/info "Refreshing mods to pick up" sdp-file)
                     {::task-type ::refresh-mods
                      :delete-invalid-sdp true
                      :priorites [sdp-file]
                      :spring-root spring-root})
                   (and rapid-id
                        (not rapid-task)
                        (not update-rapid-task)
                        engine-file
                        (not sdp-file-exists)
                        (check-cooldown cooldowns [:rapid spring-root-path rapid-id]))
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
                   (and (not rapid-id)
                        (not rapid-task)
                        engine-file
                        (not update-rapid-task)
                        (not (string/blank? mod-name))
                        (check-cooldown cooldowns [:update-rapid spring-root-path]))
                   (do
                     (log/info "Adding task to update rapid looking for" mod-name)
                     {::task-type ::update-rapid
                      :engine-version engine-version
                      :mod-name mod-name
                      :spring-isolation-dir spring-root})
                   :else
                   (when mod-name
                     (log/info "Nothing to do to auto get game" mod-name
                       (with-out-str
                         (pprint
                           {:downloadable mod-downloadable
                            :download-task mod-download-task
                            :engine-file engine-file
                            :refresh-tasks mod-refresh-tasks
                            :rapid-data rapid-data
                            :sdp-file sdp-file
                            :sdp-file-exists sdp-file-exists
                            :spring-root spring-root}))))))
               (when
                 (and no-mod
                      (not rapid-id)
                      (not rapid-task)
                      (not update-rapid-task)
                      battle-changed)
                      ; ^ only do when first joining a battle
                 (log/info "Adding task to update rapid looking for" mod-name)
                 {::task-type ::update-rapid
                  :engine-version engine-version
                  :mod-name mod-name
                  :spring-isolation-dir spring-root})]]
    (filter some? tasks)))

(defn server-auto-resources [server-key _old-state new-state old-server new-server]
  (when (:auto-get-resources new-state)
    (try
      (when (server-needs-battle-status-sync-check new-server)
        (log/info "Auto getting resources for" server-key)
        (let [{:keys [
                      servers spring-isolation-dir]} new-state
              {:keys [battle battles client-data]} new-server
              server-url (:server-url client-data)
              spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                              spring-isolation-dir)
              {:keys [battle-map battle-modname battle-version]} (get battles (:battle-id battle))
              battle-changed (and (-> new-server :battle :battle-id)
                                  (not= (-> old-server :battle :battle-id) (-> new-server :battle :battle-id)))]
          (auto-resources-tasks {
                                 :battle-changed battle-changed
                                 :engine-version battle-version
                                 :map-name battle-map
                                 :mod-name battle-modname
                                 :spring-root spring-root}
                                new-state)))
      (catch Exception e
        (log/error e "Error in :auto-get-resources state watcher for server" (first new-server))))))


(defn battle-map-details-watcher [_k state-atom old-state new-state]
  (when (not= (battle-map-details-relevant-keys old-state)
              (battle-map-details-relevant-keys new-state))
    (try
      (doseq [[server-key new-server] (-> new-state :by-server seq)]
        (let [old-server (-> old-state :by-server (get server-key))
              server-url (-> new-server :client-data :server-url)
              {:keys [current-tasks servers spring-isolation-dir tasks-by-kind]} new-state
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
              all-tasks (concat (mapcat second tasks-by-kind) (vals current-tasks))]
          (when (and (or (not (resource/details? map-details))
                         (< tries resource/max-tries))
                     (or (and (not (string/blank? new-map))
                              (not (resource/details? map-details))
                              (or (not= old-battle-id new-battle-id)
                                  (not= old-map new-map)
                                  (and
                                    (empty? (filter (comp #{::map-details} ::task-type) all-tasks))
                                    map-exists)))
                         (and
                           (not (some (comp #{new-map} :map-name) old-maps))
                           map-exists)))
            (log/info "Mod details update for" server-key)
            (task/add-task! state-atom
              {::task-type ::map-details
               :map-name new-map
               :map-file (:file map-exists)
               :source :battle-map-details-watcher
               :tries tries}))))
      (catch Exception e
        (log/error e "Error in :battle-map-details state watcher")))))


(defn battle-mod-details-watcher [_k state-atom old-state new-state]
  (when (not= (battle-mod-details-relevant-data old-state)
              (battle-mod-details-relevant-data new-state))
    (try
      (doseq [[server-key new-server] (-> new-state :by-server seq)]
        (if-not (server-needs-battle-status-sync-check new-server)
          (log/debug "Server" server-key "does not need battle mod details check")
          (let [old-server (-> old-state :by-server (get server-key))
                server-url (-> new-server :client-data :server-url)
                {:keys [current-tasks servers spring-isolation-dir tasks-by-kind]} new-state
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
                mod-details (resource/cached-details (:mod-details new-state) mod-exists)
                all-tasks (concat (mapcat second tasks-by-kind) (vals current-tasks))]
            (when (or (and (and (not (string/blank? new-mod))
                                (not (resource/details? mod-details)))
                           (or (not= old-battle-id new-battle-id)
                               (not= old-mod new-mod)
                               (and
                                 (empty? (filter (comp #{::mod-details} ::task-type) all-tasks))
                                 mod-exists)))
                      (and (not (some filter-fn old-mods))
                           mod-exists))
              (log/info "Mod details update for" server-key)
              (task/add-task! state-atom
                {::task-type ::mod-details
                 :mod-name new-mod
                 :mod-file (:file mod-exists)
                 :source :battle-mod-details-watcher
                 :use-git-mod-version (:use-git-mod-version new-state)})))))
      (catch Exception e
        (log/error e "Error in :battle-mod-details state watcher")))))


(defn replay-map-and-mod-details-watcher [_k state-atom old-state new-state]
  (when (or (not= (replay-map-and-mod-details-relevant-keys old-state)
                  (replay-map-and-mod-details-relevant-keys new-state))
            (:single-replay-view new-state))
    (try
      (let [old-selected-replay-file (:selected-replay-file old-state)
            old-replay-id (:selected-replay-id old-state)
            {:keys [online-bar-replays parsed-replays-by-path replay-details selected-replay-file
                    selected-replay-id spring-isolation-dir]} new-state

            old-replay-path (fs/canonical-path old-selected-replay-file)
            new-replay-path (fs/canonical-path selected-replay-file)

            old-replay (or (get parsed-replays-by-path old-replay-path)
                           (get online-bar-replays old-replay-id))
            new-replay (or (get parsed-replays-by-path new-replay-path)
                           (get online-bar-replays selected-replay-id))

            old-mod (:replay-mod-name old-replay)
            old-map (:replay-map-name old-replay)

            new-mod (:replay-mod-name new-replay)
            new-map (:replay-map-name new-replay)

            spring-root-path (fs/canonical-path spring-isolation-dir)

            old-maps (-> old-state :by-spring-root (get spring-root-path) :maps)
            new-maps (-> new-state :by-spring-root (get spring-root-path) :maps)

            old-mods (-> old-state :by-spring-root (get spring-root-path) :mods)
            new-mods (-> new-state :by-spring-root (get spring-root-path) :mods)

            new-mod-sans-git (u/mod-name-sans-git new-mod)
            mod-name-set (set [new-mod new-mod-sans-git])
            filter-fn (comp mod-name-set u/mod-name-sans-git :mod-name)

            map-exists (->> new-maps (filter (comp #{new-map} :map-name)) first)
            mod-exists (->> new-mods (filter filter-fn) first)

            map-details (resource/cached-details (:map-details new-state) map-exists)
            mod-details (resource/cached-details (:mod-details new-state) mod-exists)

            map-changed (not= new-map (:map-name map-details))
            mod-changed (not= new-mod (:mod-name mod-details))

            replay-details (get replay-details new-replay-path)

            tasks [
                   (when (or (and (or (not= old-replay-path new-replay-path)
                                      (not= old-mod new-mod)
                                      (:single-replay-view new-state))
                                  (and (not (string/blank? new-mod))
                                       (or (not (resource/details? mod-details))
                                           mod-changed)))
                             (and
                               (or (not (some filter-fn old-mods))
                                   (:single-replay-view new-state))
                               mod-exists))
                     {::task-type ::mod-details
                      :mod-name new-mod
                      :mod-file (:file mod-exists)
                      :use-git-mod-version (:use-git-mod-version new-state)})
                   (when (or (and (or (not= old-replay-path new-replay-path)
                                      (not= old-map new-map)
                                      (:single-replay-view new-state))
                                  (and (not (string/blank? new-map))
                                       (or (not (resource/details? map-details))
                                           map-changed)))
                             (and
                               (or (not (some (comp #{new-map} :map-name) old-maps))
                                   (:single-replay-view new-state))
                               map-exists))
                     {::task-type ::map-details
                      :map-name new-map
                      :map-file (:file map-exists)})
                   (when (and (not= old-replay-path new-replay-path)
                              (not replay-details))
                     {::task-type ::replay-details
                      :replay-file selected-replay-file})]]
        (when-let [tasks (->> tasks (filter some?) seq)]
          (log/info "Adding" (count tasks) "for replay resources")
          (task/add-tasks! state-atom tasks)))
      (catch Exception e
        (log/error e "Error in :replay-map-and-mod-details state watcher")))))


(defn- fix-resource-relevant-keys [state]
  (-> state
      (select-keys [:by-server :by-spring-root :spring-isolation-dir])
      (update :by-server
        (fn [by-server]
          (-> by-server
              (select-keys [:local])
              (update :local select-keys [:battles]))))))

(defn fix-missing-resource-watcher [_k state-atom old-state new-state]
  (tufte/profile {:dynamic? true
                  :id ::state-watcher}
    (tufte/p :fix-missing-resource-watcher
      (when (not= (fix-resource-relevant-keys old-state)
                  (fix-resource-relevant-keys new-state))
        (try
          (let [old-spring-root (:spring-isolation-dir old-state)
                spring-root (:spring-isolation-dir new-state)
                spring-root-path (fs/canonical-path spring-root)
                old-spring-data (get-in old-state [:by-spring-root (fs/canonical-path old-spring-root)])
                {:keys [engines maps mods]} (get-in new-state [:by-spring-root spring-root-path])
                {:keys [battle-version battle-map battle-modname]} (get-in new-state [:by-server :local :battles :singleplayer])]
            (when (not (some (comp #{battle-map} :map-name) maps))
              (when-let [old-file (->> old-spring-data
                                       :maps
                                       (filter (comp #{battle-map} :map-name))
                                       first
                                       :file)]
                (when-let [new-map (->> maps
                                        (filter (comp #{(fs/canonical-path old-file)} fs/canonical-path :file))
                                        first)]
                  (swap! state-atom assoc-in [:by-server :local :battles :singleplayer :battle-map] (:map-name new-map)))))
            (when (not (some (comp #{battle-modname} :mod-name) mods))
              (when-let [old-file (->> old-spring-data
                                       :mods
                                       (filter (comp #{battle-modname} :mod-name))
                                       first
                                       :file)]
                (when-let [new-mod (->> mods
                                        (filter (comp #{(fs/canonical-path old-file)} fs/canonical-path :file))
                                        first)]
                  (swap! state-atom assoc-in [:by-server :local :battles :singleplayer :battle-modname] (:mod-name new-mod)))))
            (when (not (some (comp #{battle-version} :engine-version) engines))
              (when-let [old-file (->> old-spring-data
                                       :engines
                                       (filter (comp #{battle-version} :engine-version))
                                       first
                                       :file)]
                (when-let [new-engine (->> mods
                                           (filter (comp #{(fs/canonical-path old-file)} fs/canonical-path :file))
                                           first)]
                  (swap! state-atom assoc-in [:by-server :local :battles :singleplayer :battle-version] (:engine-version new-engine))))))
          (catch Exception e
            (log/error e "Error in :battle-map-details state watcher")))))))


(defn fix-spring-isolation-dir-watcher [_k state-atom old-state new-state]
  (when (not= old-state new-state)
    (try
      (let [{:keys [spring-isolation-dir]} new-state]
        (when-not (and spring-isolation-dir
                       (instance? File spring-isolation-dir))
          (let [fixed (or (fs/file spring-isolation-dir)
                          (fs/default-spring-root))]
            (log/info "Fixed spring isolation dir, was" spring-isolation-dir "now" fixed)
            (swap! state-atom assoc :spring-isolation-dir fixed))))
      (catch Exception e
        (log/error e "Error in :fix-spring-isolation-dir state watcher")))))

(defn spring-isolation-dir-changed-watcher [_k state-atom old-state new-state]
  (when (not= (:spring-isolation-dir old-state)
              (:spring-isolation-dir new-state))
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
                    task/add-multiple-tasks
                    [{::task-type ::refresh-engines
                      :force true
                      :spring-root spring-isolation-dir}
                     {::task-type ::refresh-mods
                      :spring-root spring-isolation-dir}
                     {::task-type ::refresh-maps
                      :spring-root spring-isolation-dir}
                     {::task-type ::scan-imports
                      :sources (fx.import/import-sources extra-import-sources)}
                     {::task-type ::update-rapid
                      :spring-isolation-dir spring-isolation-dir}
                     {::task-type ::refresh-replays}]))))))
      (catch Exception e
        (log/error e "Error in :spring-isolation-dir-changed state watcher")))))


(defn auto-get-resources-watcher [_k state-atom old-state new-state]
  (when (and (:auto-get-resources new-state)
             (some (comp server-needs-battle-status-sync-check second) (:by-server new-state)))
    (try
      (when-let [tasks (->> new-state
                            :by-server
                            (remove (comp keyword? first))
                            (mapcat
                              (fn [[server-key new-server]]
                                (let [old-server (-> old-state :by-server (get server-key))]
                                  (server-auto-resources server-key old-state new-state old-server new-server))))
                            (filter some?)
                            seq)]
        (log/info "Adding" (count tasks) "to auto get resources")
        (task/add-tasks! state-atom tasks))
      (catch Exception e
        (log/error e "Error in :auto-get-resources state watcher")))))


(defn selected-replay-needs-auto-get
  [selected-replay {:keys [by-spring-root spring-isolation-dir]}]
  (let [{:keys [replay-engine-version replay-map-name replay-mod-name]} selected-replay
        {:keys [engines maps mods]} (get by-spring-root (fs/canonical-path spring-isolation-dir))]
    (and selected-replay
         (or (not (some (comp #{replay-engine-version} :engine-version) engines))
             (not (some (comp #{replay-map-name} :map-name) maps))
             (not (some (comp #{replay-mod-name} :mod-name) mods))))))

(defn auto-get-replay-resources-watcher [_k state-atom _old-state new-state]
  (let [{:keys [parsed-replays-by-path selected-replay-file spring-isolation-dir]} new-state
        {:keys [replay-engine-version replay-map-name replay-mod-name] :as selected-replay} (get parsed-replays-by-path (fs/canonical-path selected-replay-file))]
    (when (and (:auto-get-replay-resources new-state)
               selected-replay
               (selected-replay-needs-auto-get selected-replay new-state))
      (try
        (let [
              tasks (auto-resources-tasks {:engine-version replay-engine-version
                                           :map-name replay-map-name
                                           :mod-name replay-mod-name
                                           :spring-root spring-isolation-dir}
                                          new-state)]
          (when (seq tasks)
            (log/info "Adding" (count tasks) "to auto get replay resources")
            (task/add-tasks! state-atom tasks)))
        (catch Exception e
          (log/error e "Error in :auto-get-replay-resources state watcher"))))))


(defn- fix-selected-replay-relevant-keys [state]
  (select-keys
    state
    [:parsed-replays-by-path :selected-replay-file :selected-replay-id]))

(defn fix-selected-replay-watcher [_k state-atom old-state new-state]
  (tufte/profile {:dynamic? true
                  :id ::state-watcher}
    (tufte/p :fix-selected-replay-watcher
      (when (not= (fix-selected-replay-relevant-keys old-state)
                  (fix-selected-replay-relevant-keys new-state))
        (try
          (let [{:keys [parsed-replays-by-path selected-replay-file selected-replay-id]} new-state]
            (when (and selected-replay-id (not selected-replay-file))
              (when-let [local (->> parsed-replays-by-path
                                    vals
                                    (filter (comp #{selected-replay-id} :replay-id))
                                    first
                                    :file)]
                (log/info "Fixing selected replay from" selected-replay-id "to" local)
                (swap! state-atom assoc :selected-replay-id nil :selected-replay-file local))))
          (catch Exception e
            (log/error e "Error in :fix-selected-replay state watcher")))))))

(defn fix-selected-server-watcher [_k state-atom old-state new-state]
  (tufte/profile {:dynamic? true
                  :id ::state-watcher}
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


(defn update-battle-status-sync-watcher [_k state-atom old-state new-state]
  (when #_(or (not= (update-battle-status-sync-relevant-data old-state)
                    (update-battle-status-sync-relevant-data new-state)))
        (some (comp server-needs-battle-status-sync-check second) (-> new-state :by-server seq))
    (try
      (log/info "Checking servers for battle sync status updates")
      (doseq [[server-key new-server] (concat
                                        (->> new-state :by-server u/valid-servers)
                                        (->> new-state u/complex-servers))]
        (if (and (= (:servers old-state)
                    (:servers new-state))
                 (not (server-needs-battle-status-sync-check new-server)))
          (log/debug "Server" server-key "does not need battle sync status check")
          (let [
                _ (log/info "Checking battle sync status for" server-key)
                old-server (get-in old-state [:by-server server-key])
                server-url (get-in new-server [:client-data :server-url])
                {:keys [servers spring-isolation-dir]} new-state
                spring-root (or (get-in servers [server-url :spring-isolation-dir])
                                spring-isolation-dir)
                spring-root-path (fs/canonical-path spring-root)

                old-spring (get-in old-state [:by-spring-root spring-root-path])
                new-spring (get-in new-state [:by-spring-root spring-root-path])

                old-sync (resource/sync-status old-server old-spring (:mod-details old-state) (:map-details old-state))
                new-sync (resource/sync-status new-server new-spring (:mod-details new-state) (:map-details new-state))

                new-sync-number (handler/sync-number new-sync)
                battle (:battle new-server)
                client-data (:client-data new-server)
                my-username (or (:username client-data)
                                (:username new-server))
                {:keys [battle-status team-color]} (get-in battle [:users my-username])
                old-sync-number (get-in battle [:users my-username :battle-status :sync])
                battle-id (:battle-id battle)
                battle-changed (not= battle-id
                                     (-> old-server :battle :battle-id))]
            (when (and battle-id
                       (or (not= old-sync new-sync)
                           (not= old-sync-number new-sync-number)
                           battle-changed))
              (if battle-changed
                (log/info "Setting battle sync status for" server-key "in battle" battle-id "to" new-sync "(" new-sync-number ")")
                (log/info "Updating battle sync status for" server-key "in battle" battle-id "from" old-sync
                          "(" old-sync-number ") to" new-sync "(" new-sync-number ")"))
              (if (#{:direct-client :direct-host} (u/server-type server-key))
                (fx.event.battle/update-player-or-bot-state
                  state-atom
                  server-key
                  {:username my-username}
                  {:battle-status {:sync new-sync-number}})
                (let [new-battle-status (assoc battle-status :sync new-sync-number)]
                  (message/send-message *state client-data
                    (str "MYBATTLESTATUS " (cu/encode-battle-status new-battle-status) " " (or team-color 0)))))))))
      (catch Exception e
        (log/error e "Error in :update-battle-status-sync state watcher")))))

(defn- filter-replays-relevant-keys [state]
  (select-keys
    state
    [:filter-replay :filter-replay-max-players :filter-replay-min-players :filter-replay-min-skill
     :filter-replay-source :filter-replay-type :online-bar-replays :parsed-replays-by-path
     :replays-filter-specs :replays-tags :replays-window-dedupe]))

(def filter-replays-channel (async/chan (async/sliding-buffer 1)))

(def process-filter-replays
  (future
    (loop []
      (when-let [state (async/<!! filter-replays-channel)]
        (try
          (let [before (u/curr-millis)
                {:keys [filter-replay filter-replay-max-players filter-replay-min-players
                        filter-replay-min-skill filter-replay-source filter-replay-type
                        online-bar-replays parsed-replays-by-path replays-filter-specs replays-tags
                        replays-window-dedupe]} state
                _ (log/info "Filtering replays with" filter-replay)
                local-filenames (->> parsed-replays-by-path
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
                                   (<= filter-replay-min-players (:replay-player-count replay))
                                   true)))
                             (filter
                               (fn [replay]
                                 (if filter-replay-max-players
                                   (<= (:replay-player-count replay) filter-replay-max-players)
                                   true)))
                             (filter
                               (fn [replay]
                                 (if filter-replay-min-skill
                                   (if-let [avg (:replay-average-skill replay)]
                                     (<= filter-replay-min-skill avg)
                                     false)
                                   true)))
                             (filter
                               (fn [replay]
                                 (if (empty? filter-terms)
                                   true
                                   (let [players (concat
                                                   (mapcat identity (:replay-allyteam-player-names replay))
                                                   (when replays-filter-specs
                                                     (:replay-spec-names replay)))
                                         players-sanitized (map u/sanitize-filter players)]
                                     (every?
                                       (some-fn
                                         (partial includes-term? (:replay-id replay))
                                         (partial includes-term? (some-> replay :file fs/filename))
                                         (partial includes-term? (get replays-tags (:replay-id replay)))
                                         (partial includes-term? (:replay-engine-version replay))
                                         (partial includes-term? (:replay-mod-name replay))
                                         (partial includes-term? (:replay-map-name replay))
                                         (fn [term] (some #(includes-term? % term) players))
                                         (fn [term] (some #(includes-term? % term) players-sanitized)))
                                       filter-terms)))))
                             doall)
                replays (if replays-window-dedupe
                          (:replays
                            (reduce ; dedupe by id
                              (fn [agg curr]
                                (let [id (:replay-id curr)]
                                  (cond
                                    (not id)
                                    (update agg :replays conj curr)
                                    (contains? (:seen-ids agg) id) agg
                                    :else
                                    (-> agg
                                        (update :replays conj curr)
                                        (update :seen-ids conj id)))))
                              {:replays []
                               :seen-ids #{}}
                              replays))
                          replays)]
            (swap! *state assoc :filtered-replays (doall replays))
            (log/info "Filtered replays in" (- (u/curr-millis) before) "ms"))
          (catch Exception e
            (log/error e "Error in :filter-replays state watcher")))
       (recur)))))

(defn filter-replays-watcher [_k _state-atom old-state new-state]
  (when
    (or (not (:filtered-replays new-state))
        (and (not= old-state new-state)
             (not= (filter-replays-relevant-keys old-state)
                   (filter-replays-relevant-keys new-state))))
    (async/>!! filter-replays-channel new-state)))


(defn- add-watchers
  "Adds all *state watchers."
  [state-atom]
  (remove-watch state-atom :fix-missing-resource)
  (remove-watch state-atom :filter-replays)
  (remove-watch state-atom :fix-selected-replay)
  (remove-watch state-atom :fix-selected-server)
  (add-watch state-atom :fix-missing-resource fix-missing-resource-watcher)
  (add-watch state-atom :filter-replays filter-replays-watcher)
  (add-watch state-atom :fix-selected-replay fix-selected-replay-watcher)
  (add-watch state-atom :fix-selected-server fix-selected-server-watcher))


(defmulti task-handler ::task-type)

(def ^:dynamic handle-task task-handler) ; for overriding in dev


(defmethod task-handler :default [task]
  (when task
    (log/warn "Unknown task type" task)))


(defn handle-task!
  ([state-atom task-kind]
   (when (first (get-in @state-atom [:tasks-by-kind task-kind])) ; short circuit if no task of this kind
     (let [[_before after] (swap-vals! state-atom
                             (fn [{:keys [tasks-by-kind] :as state}]
                               (if (empty? (get tasks-by-kind task-kind))
                                 state
                                 (let [task (-> tasks-by-kind (get task-kind) shuffle first)]
                                   (-> state
                                       (update-in [:tasks-by-kind task-kind] (partial remove-task task))
                                       (assoc-in [:current-tasks task-kind] task))))))
           task (-> after :current-tasks (get task-kind))]
       (try
         (let [thread (Thread. (fn [] (handle-task task)))]
           (swap! *state assoc-in [:task-threads task-kind] thread)
           (.start thread)
           (.join thread))
         (catch Exception e
           (log/error e "Error running task"))
         (catch Throwable t
           (log/error t "Critical error running task"))
         (finally
           (when task
             (swap! state-atom
               (fn [state]
                 (-> state
                     (assoc-in [:current-tasks task-kind] nil)
                     (update :task-threads dissoc task-kind)))))))
       task))))


(defn- my-client-status [{:keys [username users]}]
  (-> users (get username) :client-status))


(defn- tasks-chimer-fn
  ([state-atom task-kind]
   (log/info "Starting tasks chimer for" task-kind)
   (let [chimer
         (chime/chime-at
           (chime/periodic-seq
             (java-time/plus (java-time/instant) (java-time/duration 10 :seconds))
             (java-time/duration 1 :seconds))
           (fn [_chimestamp]
             (let [{:keys [by-server disable-tasks disable-tasks-while-in-game]} @state-atom
                   in-any-game (some (comp :ingame my-client-status second) by-server)]
               (if (or disable-tasks (and disable-tasks-while-in-game in-any-game))
                 (log/debug "Skipping task handler while in game")
                 (handle-task! state-atom task-kind))))
           {:error-handler
            (fn [e]
              (log/error e "Error handling task of kind" task-kind)
              true)})]
     (fn [] (.close chimer)))))


(defn- old-valid-replay-fn [all-paths-set]
  (fn [[path replay]]
    (and
      (contains? all-paths-set path) ; remove missing files
      (-> replay :header :game-id) ; re-parse if no game id
      (not (-> replay :file-size zero?)) ; remove empty files
      (not (-> replay :game-type #{:invalid}))))) ; remove invalid

(defn- valid-replay-fn [all-paths-set]
  (fn [[path replay]]
    (and
      (contains? all-paths-set path) ; remove missing files
      (:replay-id replay) ; re-parse if no replay id
      (not (-> replay :file-size zero?)) ; remove empty files
      (not (-> replay :game-type #{:invalid}))))) ; remove invalid

(defn migrate-replay [replay]
  (merge
    (select-keys replay [:file :filename :file-size :source-name])
    (replay/replay-metadata replay)))

(defn- refresh-replays
  [state-atom]
  (log/info "Refreshing replays")
  (let [before (u/curr-millis)
        {:keys [parsed-replays-by-path replay-sources-enabled] :as state} @state-atom
        all-files (mapcat
                    (fn [{:keys [file recursive replay-source-name]}]
                      (let [files (fs/replay-files file {:recursive recursive})]
                        (log/info "Found" (count files) "replay files from" replay-source-name "at" file)
                        (map
                          (juxt (constantly replay-source-name) identity)
                          files)))
                    (filter
                      (fn [{:keys [file]}]
                        (let [path (fs/canonical-path file)]
                          (or (not (contains? replay-sources-enabled path))
                              (get replay-sources-enabled path))))
                      (fs/replay-sources state)))
        all-paths (set (map (comp fs/canonical-path second) all-files))
        old-valid-replay? (old-valid-replay-fn all-paths)
        valid-replay? (valid-replay-fn all-paths)
        existing-valid-paths (->> parsed-replays-by-path
                                  (filter valid-replay?)
                                  keys
                                  set)
        todo (->> all-files
                  (remove (comp existing-valid-paths fs/canonical-path second))
                  shuffle)
        this-round (take replays-batch-size todo)
        parsed-replays (->> this-round
                            (map
                              (fn [[source f]]
                                [(fs/canonical-path f)
                                 (merge
                                   (replay/parse-replay f)
                                   {:source-name source})]))
                            doall)]
    (log/info "Parsed" (count this-round) "of" (count todo) "new replays in" (- (u/curr-millis) before) "ms")
    (let [
          new-state (swap! state-atom
                      (fn [state]
                        (let [old-replays (:parsed-replays-by-path state)
                              replays-by-path (if (map? old-replays) old-replays {})
                              all-replays (into {} (concat replays-by-path parsed-replays))
                              valid-replays (->> all-replays
                                                 (filter valid-replay?)
                                                 (into {}))
                              migratable-replays (->> all-replays
                                                      (remove valid-replay?)
                                                      (filter old-valid-replay?))
                              _ (log/info "Migrating" (count migratable-replays) "replays")
                              migrated-replays (->> migratable-replays
                                                    (map (fn [[path replay]] [path (migrate-replay replay)]))
                                                    (into {}))
                              _ (log/info "Migrated" (count migratable-replays) "replays")
                              replays-by-path (merge valid-replays migrated-replays)
                              valid-replay-paths (set (concat (keys replays-by-path)))
                              invalid-replay-paths (->> all-replays
                                                        (remove (some-fn old-valid-replay? valid-replay?))
                                                        keys
                                                        (concat (:invalid-replay-paths state))
                                                        (remove valid-replay-paths)
                                                        set)]
                          (assoc state
                                 :parsed-replays-by-path replays-by-path
                                 :invalid-replay-paths invalid-replay-paths))))
          invalid-replay-paths (set (:invalid-replay-paths new-state))
          valid-next-round (remove
                             (comp invalid-replay-paths fs/canonical-path second)
                             todo)]
      (if (seq valid-next-round)
        (task/add-task! state-atom
          {::task-type ::refresh-replays
           :todo (count todo)})
        (do
          (log/info "No valid replays left to parse")
          (spit-app-edn
            ((:select-fn parsed-replays-config) new-state)
            (:filename parsed-replays-config)
            parsed-replays-config)
          (task/add-task! state-atom {::task-type ::refresh-replay-resources}))))))

(defn- refresh-replay-resources
  [state-atom]
  (log/info "Refresh replay resources")
  (let [before (u/curr-millis)
        {:keys [downloadables-by-url importables-by-path parsed-replays-by-path]} @state-atom
        parsed-replays (vals parsed-replays-by-path)
        engine-versions (->> parsed-replays
                             (map :replay-engine-version)
                             (filter some?)
                             set)
        mod-names (->> parsed-replays
                       (map :replay-mod-name)
                       set)
        map-names (->> parsed-replays
                       (map :replay-map-name)
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

(defn- refresh-engines
  "Reads engine details and updates missing engines in :engines in state."
  ([]
   (refresh-engines *state))
  ([state-atom]
   (refresh-engines state-atom nil))
  ([state-atom spring-root]
   (refresh-engines state-atom spring-root nil))
  ([state-atom spring-root opts]
   (log/info "Refreshing engines in" spring-root)
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
         to-add (if (:force opts) ; refresh existing engines too
                  engine-dirs
                  (remove (comp known-canonical-paths fs/canonical-path) engine-dirs))
         canonical-path-set (set (map fs/canonical-path engine-dirs))
         missing-files (set
                         (concat
                           (->> known-canonical-paths
                                (remove (comp fs/exists? io/file)))
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
                  (set
                    (conj
                      (remove (comp #{(fs/canonical-path engine-dir)} fs/canonical-path :file) engines)
                      engine-data))))))
     (log/debug "Removing" (count to-remove) "engines")
     (swap! state-atom update-in [:by-spring-root spring-root-path :engines]
            (fn [engines]
              (->> engines
                   (filter (comp fs/canonical-path :file))
                   (filter fs/is-current-engine-data?)
                   (remove (comp to-remove fs/canonical-path :file))
                   set)))
     {:to-add-count (count to-add)
      :to-remove-count (count to-remove)})))


(defn- refresh-mods
  "Reads mod details and updates missing mods in :mods in state."
  ([state-atom]
   (refresh-mods state-atom nil))
  ([state-atom spring-root]
   (refresh-mods state-atom spring-root nil))
  ([state-atom spring-root {:keys [delete-invalid-sdp priorities]}]
   (log/info "Refreshing mods in" spring-root)
   (let [before (u/curr-millis)
         {:keys [rapid-by-spring-root spring-isolation-dir use-git-mod-version] :as state} @state-atom
         spring-root (or spring-root spring-isolation-dir)
         _ (log/info "Updating mods in" spring-root)
         spring-root-path (fs/canonical-path spring-root)
         {:keys [rapid-data-by-version]} (get rapid-by-spring-root spring-root-path)
         mods (-> state :by-spring-root (get spring-root-path) :mods)
         error-files (->> mods (filter :error) (map :file))
         {:keys [directory]} (group-by ::fs/source mods)
         known-paths (set (map (comp fs/canonical-path :file) mods))
         mod-files (fs/mod-files spring-root)
         sdp-files (rapid/sdp-files spring-root)
         _ (log/info "Found" (count mod-files) "files and"
                     (count sdp-files) "rapid archives to scan for mods")
         to-add-file (set (remove (comp known-paths fs/canonical-path) mod-files))
         to-add-rapid (remove (comp known-paths fs/canonical-path) sdp-files)
         todo (shuffle (concat to-add-file to-add-rapid))
         _ (log/info "Found" (count to-add-file) "mod files and" (count to-add-rapid)
                     "rapid files to scan for mods in" (- (u/curr-millis) before) "ms")
         battle-mods (->> state
                          :by-server
                          (map second)
                          (map
                            (fn [{:keys [battle battles]}]
                              (let [{:keys [battle-id]} battle]
                                (get-in battles [battle-id :battle-modname]))))
                          (filter some?))
         battle-rapid-data (map (partial get rapid-data-by-version) battle-mods)
         battle-rapid-hashes (->> battle-rapid-data
                                  (map :hash)
                                  (filter some?)
                                  set)
         priorities (->> todo
                         (filter
                           (some-fn
                             (comp
                               (fn [resource]
                                 (some
                                   #(resource/could-be-this-mod? % resource)
                                   battle-mods))
                               (fn [f]
                                 {:resource-filename (fs/filename f)}))
                             (fn [f]
                               (when-let [filename (fs/filename f)]
                                 (when (string/ends-with? filename ".sdp")
                                   (let [id (first (string/split filename #"\."))]
                                     (contains? battle-rapid-hashes id)))))))
                         (concat priorities))
         _ (log/info "Prioritizing mods in battles" (pr-str priorities))
         this-round (concat priorities
                      (if (<= (count todo) mods-batch-size) ; add errors if there's not a full round left
                        (do
                          (log/info "Adding mod directories and mods with errors to scan")
                          (concat todo
                                  (map :file directory) ; re-scan directories just before errors
                                  error-files))
                        (take mods-batch-size todo)))
         all-paths (set (filter some? (map (comp fs/canonical-path :file) mods)))
         missing-files (set
                         (concat
                           (->> all-paths
                                (remove (comp fs/exists? io/file)))
                           (->> all-paths
                                (remove (comp (partial fs/descendant? spring-root) io/file)))))
         next-round
         (->> (drop mods-batch-size todo)
              (remove (comp all-paths fs/canonical-path))
              seq)]
     (apply fs/update-file-cache! state-atom all-paths)
     (when (seq this-round)
       (log/info "Adding" (count this-round) "mods this iteration:" (with-out-str (pprint this-round))))
     (doseq [file this-round]
       (log/info "Reading mod from" file)
       (update-mod *state spring-root-path file {:delete-invalid-sdp delete-invalid-sdp :use-git-mod-version use-git-mod-version}))
     (log/info "Removing mods with no name, and" (count missing-files) "mods with missing files")
     (swap! state-atom
            (fn [state]
              (-> state
                  (update-in [:by-spring-root spring-root-path :mods]
                    (fn [mods]
                      (->> mods
                           (remove
                             (fn [{:keys [error mod-name]}]
                               (and (not error)
                                    (string/blank? mod-name))))
                           (filter (comp (partial instance? File) :file))
                           (remove (comp missing-files fs/canonical-path :file))
                           set)))
                  (dissoc :update-mods))))
     (if next-round
       (do
         (log/info "Scheduling mod load since there are" (count next-round) "mods left to load")
         (task/add-task! state-atom
           {::task-type ::refresh-mods
            :spring-root spring-root
            :todo (count next-round)}))
       (log/info "Finished mod refresh"))
     {:to-add-file-count (count to-add-file)
      :to-add-rapid-count (count to-add-rapid)})))


(defn- update-cached-minimaps
  ([maps]
   (update-cached-minimaps maps nil))
  ([maps {:keys [priorities] :as opts}]
   (let [to-update
         (->> maps
              (filter :map-name)
              (filter
                (fn [map-details]
                  (let [
                        heightmap-file (-> map-details :map-name (fs/minimap-image-cache-file {:minimap-type "heightmap"}))
                        metalmap-file (-> map-details :map-name (fs/minimap-image-cache-file {:minimap-type "metalmap"}))
                        minimap-file (-> map-details :map-name (fs/minimap-image-cache-file {:minimap-type "minimap"}))]
                    (or (:force opts)
                        (not (fs/exists? heightmap-file))
                        (not (fs/exists? metalmap-file))
                        (not (fs/exists? minimap-file))))))
              (sort-by :map-name)
              (concat priorities))
         this-round (take minimap-batch-size to-update)
         next-round (drop minimap-batch-size to-update)]
     (log/info (count to-update) "maps do not have cached minimap image files")
     (doseq [map-details this-round]
       (if-let [map-file (:file map-details)]
         (do
           (log/info "Caching minimap for" map-file)
           (let [{:keys [map-name smf]} (fs/read-map-data map-file {:map-images true})
                 {:keys [minimap-height minimap-width]} smf]
             (when-let [minimap-image (:minimap-image smf)]
               (let [minimap-image-scaled (fs.smf/scale-minimap-image minimap-width minimap-height minimap-image)]
                 (fs/write-image-png minimap-image-scaled (fs/minimap-image-cache-file map-name {:minimap-type "minimap"}))))
             (when-let [metalmap-image (:metalmap-image smf)]
               (let [metalmap-image-scaled (fs.smf/scale-minimap-image minimap-width minimap-height metalmap-image)]
                 (fs/write-image-png metalmap-image-scaled (fs/minimap-image-cache-file map-name {:minimap-type "metalmap"}))))
             (when-let [heightmap-image (:heightmap-image smf)]
               (let [heightmap-image-scaled (fs.smf/scale-minimap-image minimap-width minimap-height heightmap-image)]
                 (fs/write-image-png heightmap-image-scaled (fs/minimap-image-cache-file map-name {:minimap-type "heightmap"}))))
             (swap! *state
               (fn [state]
                 (-> state
                     (assoc-in [:cached-minimap-updated (fs/canonical-path map-file)] (u/curr-millis))
                     (assoc-in [:cached-minimap-updated map-name] (u/curr-millis)))))))
         (log/error "Map is missing file" (:map-name map-details))))
     (when (seq next-round)
       (task/add-task! *state
         {::task-type ::update-cached-minimaps
          :priorites priorities
          :todo (count next-round)})))))


(defn- refresh-maps
  "Reads map details and caches for maps missing from :maps in state."
  ([state-atom]
   (refresh-maps state-atom nil))
  ([state-atom spring-root]
   (refresh-maps state-atom spring-root nil))
  ([state-atom spring-root {:keys [priorities]}]
   (log/info "Refreshing maps in" spring-root)
   (let [before (u/curr-millis)
         {:keys [spring-isolation-dir] :as state} @state-atom
         spring-root (or spring-root spring-isolation-dir)
         _ (log/info "Updating maps in" spring-root)
         spring-root-path (fs/canonical-path spring-root)
         maps (-> state :by-spring-root (get spring-root-path) :maps)
         map-files (fs/map-files spring-root)
         known-files (->> maps (map :file) set)
         error-files (->> maps (filter :error) (map :file))
         known-paths (->> known-files (map fs/canonical-path) set)
         todo (->> map-files
                   (remove
                     (fn [file]
                       (contains? known-paths (fs/canonical-path file))))
                   shuffle)
         _ (log/info "Found" (count todo) "maps to load in" (- (u/curr-millis) before) "ms")
         battle-maps (->> state
                          :by-server
                          (map second)
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
                               {:resource-filename (fs/filename f)})))
                         (concat priorities))
         _ (log/info "Prioritizing maps in battles" (pr-str priorities))
         this-round (concat priorities
                      (if (<= (count todo) maps-batch-size) ; add errors if there's not a full round left
                        (do
                          (log/info "Adding map directories and maps with errors to scan")
                          (concat todo
                                  (filter fs/is-directory? map-files) ; re-scan directories just before errors
                                  error-files))
                        (take maps-batch-size todo)))
         next-round (drop maps-batch-size todo)
         all-paths (set (filter some? (map (comp fs/canonical-path :file) maps)))
         next-round (->> next-round
                         (remove (comp all-paths fs/canonical-path))
                         seq)
         next-round-count (count next-round)
         missing-paths (set
                         (concat
                           (->> known-files
                                (remove fs/exists?)
                                (map fs/canonical-path))
                           (->> known-files
                                (remove (partial fs/descendant? spring-root))
                                (map fs/canonical-path))))]
     (apply fs/update-file-cache! state-atom map-files)
     (fs/make-dirs fs/maps-cache-root)
     (when (seq this-round)
       (log/info "Adding" (count this-round) "maps this iteration:" (with-out-str (pprint this-round))))
     (let [map-file-to-name (into {}
                              (for [map-file this-round]
                                (do
                                  (log/info "Reading" map-file)
                                  (let [map-data (fs/read-map-data map-file)
                                        relevant-map-data (select-keys map-data [:error :file :map-name])]
                                    (swap! state-atom update-in [:by-spring-root spring-root-path :maps]
                                           (fn [maps]
                                             (let [valid-maps (->> maps
                                                                   (remove (comp #{(fs/canonical-path map-file)} fs/canonical-path :file)))]
                                               (set
                                                 (cond-> valid-maps
                                                   map-data
                                                   (conj relevant-map-data))))))
                                    [map-file (:map-name map-data)]))))
           priority-maps (map (fn [map-file]
                                {:map-name (get map-file-to-name map-file)
                                 :file map-file})
                              priorities)]
       (log/debug "Removing maps with no name, and" (count missing-paths) "maps with missing files")
       (swap! state-atom update-in [:by-spring-root spring-root-path :maps]
              (fn [maps]
                (->> maps
                     (filter (comp fs/canonical-path :file))
                     (remove
                       (fn [{:keys [error map-name]}]
                         (and (not error)
                              (string/blank? map-name))))
                     (remove (comp missing-paths fs/canonical-path :file))
                     (filter (comp (partial instance? File) :file))
                     set)))
       (if next-round
         (do
           (log/info "Scheduling map load since there are" next-round-count "maps left to load")
           (when (seq priority-maps)
             (task/add-task! state-atom {::task-type ::update-cached-minimaps
                                         :maps priority-maps}))
           (task/add-task! state-atom
             {::task-type ::refresh-maps
              :spring-root spring-root
              :todo next-round-count}))
         (do
           (log/info "Finished map refresh")
           (task/add-task! state-atom {::task-type ::update-cached-minimaps
                                       :priorities priority-maps})))
       {:todo-count next-round-count}))))


(defmethod event-handler ::randomize-client-id
  [_e]
  (swap! *state assoc :client-id-override (u/random-client-id)))


(defmethod event-handler ::stop-music
  [{:keys [^MediaPlayer media-player]}]
  (when media-player
    (.dispose media-player))
  (swap! *state
    (fn [state]
      (-> state
          (dissoc :media-player :music-now-playing :music-paused)
          (assoc :music-stopped true)))))

(defn music-files [music-dir]
  (when (fs/exists? music-dir)
    (->> (fs/list-files music-dir)
         (remove (comp #(string/starts-with? % ".") fs/filename))
         (filter
           (comp
             (fn [filename]
               (some
                 (partial string/ends-with? filename)
                 [".aif" ".aiff" ".fxm" ".flv" ".m3u8" ".mp3" ".mp4" ".m4a" ".m4v" ".wav"]))
             fs/filename))
         (sort-by fs/filename)
         (into []))))

(defn music-player
  [{:keys [^File music-file music-volume]}]
  (if music-file
    (let [media-url (-> music-file .toURI .toURL)
          media (try
                  (Media. (str media-url))
                  (catch Exception e
                    (log/error e "Error playing media" music-file)))
          media-player (MediaPlayer. media)
          audio-equalizer (.getAudioEqualizer media-player)]
      (.setAutoPlay media-player true)
      (.setVolume media-player (or music-volume 1.0))
      (.setOnEndOfMedia media-player
        (fn []
          (event-handler
            (merge
              {:event/type ::next-music}
              (select-keys @*state [:media-player :music-now-playing :music-queue :music-volume])))))
      (.setEnabled audio-equalizer true)
      media-player)
    (log/info "No music to play")))

(defmethod event-handler ::start-music
  [{:keys [music-queue music-volume]}]
  (let [music-file (first music-queue)
        media-player (music-player
                       {:music-file music-file
                        :music-volume music-volume})]
    (swap! *state assoc
           :media-player media-player
           :music-now-playing music-file
           :music-paused false
           :music-stopped false)))

(defn next-value
  "Returns the value in the given list immediately following the given value, wrapping around if
  needed."
  ([l v]
   (next-value l v nil))
  ([l v {:keys [direction] :or {direction inc}}]
   (when (seq l)
     (get
       l
       (mod
         (direction (.indexOf ^List l v))
         (count l))))))

(defmethod event-handler ::prev-music
  [{:keys [^MediaPlayer media-player music-now-playing music-queue music-volume]}]
  (when media-player
    (.dispose media-player))
  (let [music-file (next-value music-queue music-now-playing {:direction dec})
        media-player (music-player
                       {:music-file music-file
                        :music-volume music-volume})]
    (swap! *state assoc
           :music-paused false
           :media-player media-player
           :music-now-playing music-file)))

(defmethod event-handler ::next-music
  [{:keys [^MediaPlayer media-player music-now-playing music-queue music-volume]}]
  (when media-player
    (.dispose media-player))
  (let [music-file (next-value music-queue music-now-playing)
        media-player (music-player
                       {:music-file music-file
                        :music-volume music-volume})]
    (swap! *state assoc
           :music-paused false
           :media-player media-player
           :music-now-playing music-file)))

(defmethod event-handler ::toggle-music-play
  [{:keys [^MediaPlayer media-player music-paused]}]
  (when media-player
    (if music-paused
      (.play media-player)
      (.pause media-player)))
  (swap! *state assoc
         :music-paused (not music-paused)
         :music-stopped false))

(defmethod event-handler ::on-change-music-volume
  [{:fx/keys [event] :keys [^MediaPlayer media-player]}]
  (let [volume event]
    (when media-player
      (.setVolume media-player volume))
    (swap! *state assoc :music-volume volume)))

(defmethod event-handler ::on-change-git-version
  [{:fx/keys [event]}]
  (swap! *state
    (fn [state]
      (-> state
          (assoc :use-git-mod-version (boolean event))
          (task/add-task-state {::task-type ::refresh-mods})))))


(defn- fix-battle-ready-chimer-fn [state-atom]
  (log/info "Starting fix battle ready chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 1 :minutes))
            (java-time/duration 5 :seconds))
          (fn [_chimestamp]
            (log/debug "Fixing battle ready if needed")
            (let [state @state-atom]
              (doseq [[_server-key server-data] (u/valid-servers (:by-server state))]
                (when-let [battle (:battle server-data)]
                  (when (boolean? (:desired-ready battle))
                    (let [desired-ready (boolean (:desired-ready battle))
                          username (:username server-data)]
                      (when-let [me (-> server-data :battle :users (get username))]
                        (let [{:keys [battle-status team-color]} me]
                          (when (and (:mode battle-status)
                                     (not= (:ready battle-status) desired-ready))
                            (message/send-message *state (:client-data server-data)
                              (str "MYBATTLESTATUS " (cu/encode-battle-status (assoc battle-status :ready desired-ready)) " " (or team-color 0))))))))))))
          {:error-handler
           (fn [e]
             (log/error e "Error updating matchmaking")
             true)})]
    (fn [] (.close chimer))))

(defn- update-matchmaking-chimer-fn [state-atom]
  (log/info "Starting update matchmaking chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 120 :seconds))
            (java-time/duration 60 :seconds))
          (fn [_chimestamp]
            (log/debug "Updating matchmaking")
            (let [state @state-atom]
              (doseq [[server-key server-data] (u/valid-servers (:by-server state))]
                (if (u/matchmaking? server-data)
                  (let [client-data (:client-data server-data)]
                    (message/send-message state-atom client-data "c.matchmaking.list_all_queues")
                    (doseq [[queue-id _queue-data] (:matchmaking-queues server-data)]
                      (message/send-message state-atom client-data (str "c.matchmaking.get_queue_info\t" queue-id))))
                  (log/info "Matchmaking not enabled for server" server-key)))))
          {:error-handler
           (fn [e]
             (log/error e "Error updating matchmaking")
             true)})]
    (fn [] (.close chimer))))

(defn update-music-queue
  [state-atom]
  (log/debug "Updating music queue")
  (let [{:keys [media-player music-dir music-now-playing music-stopped music-volume]} @state-atom]
    (if music-dir
      (let [music-files (music-files music-dir)]
        (if (or media-player music-stopped)
          (swap! state-atom assoc :music-queue music-files)
          (let [music-file (or (next-value music-files music-now-playing)
                               (first music-files))
                media-player (music-player
                               {:music-file music-file
                                :music-volume music-volume})]
            (swap! state-atom assoc
                   :media-player media-player
                   :music-now-playing music-file
                   :music-queue music-files))))
      (log/debug "No music dir" music-dir))))

(defmethod task-handler ::update-music-queue [_]
  (update-music-queue *state))

(defn- update-music-queue-chimer-fn [state-atom]
  (log/info "Starting update music queue chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant))
            (java-time/duration 30 :seconds))
          (fn [_chimestamp]
            (update-music-queue state-atom))
          {:error-handler
           (fn [e]
             (log/error e "Error updating music queue")
             true)})]
    (fn [] (.close chimer))))

(defn- update-now-chimer-fn [state-atom]
  (log/info "Starting update now chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 30 :seconds))
            (java-time/duration 30 :seconds))
          (fn [_chimestamp]
            (log/debug "Updating now")
            (swap! state-atom assoc :now (u/curr-millis)))
          {:error-handler
           (fn [e]
             (log/error e "Error updating now")
             true)})]
    (fn [] (.close chimer))))

(defn resend-no-response-messages-chimer-fn [state-atom]
  (log/info "Starting chimer to resend messages that did not receive the expected response in time")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 30 :seconds))
            (java-time/duration 3 :seconds))
          (fn [_chimestamp]
            (log/debug "Resending messages that did not receive the expected response in time")
            (let [now (u/curr-millis)
                  [{:keys [by-server]} _new-state]
                  (swap-vals! state-atom update :by-server
                    (fn [by-server]
                      (reduce-kv
                        (fn [m k v]
                          (assoc m k
                            (update v :expecting-responses
                              (fn [expecting-responses]
                                (reduce-kv
                                  (fn [m k v]
                                    (if (and (:sent-millis v)
                                             (< now (+ (:sent-millis v) 3000)))
                                      (assoc m k v)
                                      m))
                                  {}
                                  expecting-responses)))))
                        {}
                        by-server)))]
              (doseq [[_server-key server-data] by-server]
                (doseq [[expected-response sent] (:expecting-responses server-data)]
                  (let [{:keys [sent-message sent-millis]} sent]
                    (if (and sent-message sent-millis)
                      (when (< (+ sent-millis 3000) now)
                        (log/info "Resending message that did not receive response" expected-response ":" (pr-str sent-message))
                        (message/send-message state-atom (:client-data server-data) sent-message))
                      (log/warn "Issue with expecting response:" expected-response sent)))))))
          {:error-handler
           (fn [e]
             (log/error e "Error updating now")
             true)})]
    (fn [] (.close chimer))))

(defn- update-replays-chimer-fn [state-atom]
  (log/info "Starting update replays chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 5 :minutes))
            (java-time/duration 5 :minutes))
          (fn [_chimestamp]
            (log/debug "Updating now")
            (let [{:keys [auto-refresh-replays]} @state-atom]
              (if auto-refresh-replays
                (task/add-task! state-atom {::task-type ::refresh-replays})
                (log/info "Auto replay refresh disabled"))))
          {:error-handler
           (fn [e]
             (log/error e "Error updating now")
             true)})]
    (fn [] (.close chimer))))

(defn- write-chat-logs-chimer-fn [state-atom]
  (log/info "Starting write chat logs chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 1 :minutes))
            (java-time/duration 1 :minutes))
          (fn [_chimestamp]
            (log/debug "Writing chat logs")
            (let [
                  chat-logs-dir (fs/file (fs/app-root) "chat-logs")
                  _ (fs/make-dirs chat-logs-dir)
                  [{:keys [by-server]}] (swap-vals! state-atom update :by-server
                                          (fn [by-server]
                                            (reduce-kv
                                              (fn [m k v]
                                                (assoc m k
                                                  (update v :channels
                                                    (fn [channels]
                                                      (reduce-kv
                                                        (fn [m k v]
                                                          (assoc m k
                                                            (update v :messages
                                                              (fn [messages]
                                                                (map
                                                                  #(assoc % :logged true)
                                                                  messages)))))
                                                        {}
                                                        channels)))))
                                              {}
                                              by-server)))]
              (doseq [[server-key server-data] by-server]
                (doseq [[channel-key channel-data] (:channels server-data)]
                  (let [to-log (reverse (remove :logged (:messages channel-data)))
                        filename (str
                                   (string/replace (str server-key "-" channel-key) #"[^a-zA-Z0-9\\.\\-\\@\[\]\\_]" "__")
                                   ".txt")
                        log-file (io/file chat-logs-dir filename)]
                    (when (seq to-log)
                      (log/info "Logging" (count to-log) "messages from" channel-key "on" server-key "to" log-file)
                      (spit log-file
                            (str
                              (string/join "\n"
                                (map
                                  (fn [{:keys [message-type text timestamp username]}]
                                    (str "[" (u/format-datetime timestamp) "] "
                                      (case message-type
                                        :ex (str "* " username " " text)
                                        :join (str username " has joined")
                                        :leave (str username " has left")
                                        ; else
                                        (str username ": " text))))
                                  to-log))
                              "\n")
                            :append true)))))))
          {:error-handler
           (fn [e]
             (log/error e "Error writing chat logs")
             true)})]
    (fn [] (.close chimer))))

(defn- update-window-and-divider-positions-chimer-fn [state-atom]
  (log/info "Starting window and divider positions chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 1 :minutes))
            (java-time/duration 1 :minutes))
          (fn [_chimestamp]
            (log/debug "Updating window and divider positions")
            (let [divider-positions @skylobby.fx/divider-positions
                  window-states @skylobby.fx/window-states]
              (swap! state-atom
                (fn [state]
                  (-> state
                      (update :divider-positions merge divider-positions)
                      (update :window-states u/deep-merge window-states))))))
          {:error-handler
           (fn [e]
             (log/error e "Error updating window and divider positions")
             true)})]
    (fn [] (.close chimer))))


(def app-update-url "https://api.github.com/repos/skynet-gh/skylobby/releases")
(def app-update-browseurl "https://github.com/skynet-gh/skylobby/releases")


(defn restart-process [old-jar new-jar]
  (when new-jar
    (log/info "Adding shutdown hook to run new jar")
    ; https://stackoverflow.com/a/5747843
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread.
        (fn []
          ; https://stackoverflow.com/a/48992863
          (let [
                process-command (u/process-command)
                program-folder (if (u/is-java? process-command)
                                  (fs/parent-file old-jar)
                                  (fs/file (fs/parent-file (fs/file process-command)) "app"))
                jar-to (if old-jar old-jar (fs/file program-folder "skylobby.jar"))
                elevate-file (fs/file program-folder "Elevate.exe")
                copy-bat-file (fs/file (fs/download-dir) "copy_jar.bat")
                copy-and-start-bat-file (fs/file (fs/download-dir) "copy_and_start.bat")
                new-process-command (if (u/is-java? process-command)
                                      (str "\"" (u/process-command) "\" -server " (string/join " " (u/vm-args)) " -jar \"" (fs/canonical-path jar-to) "\"")
                                      (str "\"" process-command "\""))]
            (spit copy-bat-file
              (str "copy \"" (fs/canonical-path new-jar) "\" \"" (fs/canonical-path jar-to) "\""))
            (spit copy-and-start-bat-file
              (str (if (fs/exists? elevate-file)
                     (str "\"" (fs/canonical-path elevate-file) "\" -wait \"" (fs/canonical-path copy-bat-file) "\"" \newline)
                     (str "\"" (fs/canonical-path copy-bat-file) "\"" \newline))
                   new-process-command))
            (let [^List cmd [(fs/canonical-path copy-and-start-bat-file)]]
              (log/info "Running" (pr-str cmd))
              (let [proc (ProcessBuilder. cmd)]
                (.inheritIO proc)
                (.start proc)))))))
    (log/info "Exiting for update")
    (System/exit 0)))

(defmethod task-handler ::download-app-update-and-restart [{:keys [downloadable]}]
  (let [jar-file (u/jar-file)
        dest (fs/file (fs/download-dir) (http/filename (:download-url downloadable)))]
    (cond
      (not dest) (log/error "Could not determine download dest" {:downloadable downloadable :jar-file jar-file})
      :else
      (do
        (log/info "Downloading app update" (:download-url downloadable) "to" dest)
        @(download-http-resource
           {:downloadable downloadable
            :dest dest})
        (if (fs/exists? dest)
          (restart-process jar-file dest)
          (log/error "Downloaded update file does not exist"))))))

(defn- check-app-update [state-atom]
  (let [versions
        (->> (clj-http/get app-update-url {:as :auto})
             :body
             (remove :prerelease)
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

(defmethod task-handler ::check-app-update [_]
  (check-app-update *state))

(defn- check-app-update-chimer-fn [state-atom]
  (log/info "Starting app update check chimer")
  (let [chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 1 :minutes))
            (java-time/duration 1 :hours))
          (fn [_chimestamp]
            (if disable-update-check
              (log/info "App update check disabled, skipping")
              (check-app-update state-atom)))
          {:error-handler
           (fn [e]
             (log/error e "Error checking for app update")
             true)})]
    (fn [] (.close chimer))))


(defn- spit-app-config-chimer-fn [state-atom]
  (log/info "Starting app config spit chimer")
  (let [old-state-atom (atom @state-atom)
        chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 1 :minutes))
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
  ([state-atom k watcher-fn]
   (state-change-chimer-fn state-atom k watcher-fn 3))
  ([state-atom k watcher-fn duration]
   (let [old-state-atom (atom @state-atom)
         chimer
         (chime/chime-at
           (chime/periodic-seq
             (java-time/plus (java-time/instant) (java-time/duration 15 :seconds))
             (java-time/duration (or duration 3) :seconds))
           (fn [_chimestamp]
             (let [old-state @old-state-atom
                   new-state @state-atom]
               (tufte/profile {:dynamic? true
                               :id ::chimer}
                 (tufte/p k
                   (watcher-fn k state-atom old-state new-state)))
               (reset! old-state-atom new-state)))
           {:error-handler
            (fn [e]
              (log/error e "Error in" k "state change chimer")
              true)})]
     (fn [] (.close chimer)))))


; https://github.com/ptaoussanis/tufte/blob/master/examples/clj/src/example/server.clj
(defn- profile-print-chimer-fn [_state-atom]
  (log/info "Starting profile print chimer")
  (let [stats-accumulator (tufte/add-accumulating-handler! {:ns-pattern "*"})
        chimer
        (chime/chime-at
          (chime/periodic-seq
            (java-time/plus (java-time/instant) (java-time/duration 1 :minutes))
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


(defn refresh-engines-all-spring-roots []
  (let [spring-roots (fs/spring-roots @*state)]
    (log/info "Refreshing engines in" (pr-str spring-roots))
    (doseq [spring-root spring-roots]
      (refresh-engines *state spring-root))))

(defmethod task-handler ::refresh-engines [{:keys [spring-root] :as opts}]
  (if spring-root
    (refresh-engines *state spring-root opts)
    (refresh-engines-all-spring-roots)))


(defn refresh-mods-all-spring-roots []
  (let [spring-roots (fs/spring-roots @*state)]
    (log/info "Refreshing mods in" (pr-str spring-roots))
    (doseq [spring-root spring-roots]
      (refresh-mods *state spring-root))))

(defmethod task-handler ::refresh-mods [{:keys [spring-root] :as opts}]
  (if spring-root
    (refresh-mods *state spring-root opts)
    (refresh-mods-all-spring-roots)))

(defn refresh-maps-all-spring-roots []
  (let [spring-roots (fs/spring-roots @*state)]
    (log/info "Refreshing maps in" (pr-str spring-roots))
    (doseq [spring-root spring-roots]
      (refresh-maps *state spring-root))))

(defmethod task-handler ::refresh-maps [{:keys [spring-root] :as opts}]
  (if spring-root
    (refresh-maps *state spring-root opts)
    (refresh-maps-all-spring-roots)))


(defmethod task-handler ::update-file-cache [{:keys [file]}]
  (fs/update-file-cache! *state file))


(defn update-battle-sync-statuses [state-atom]
  (let [state @state-atom]
    (doseq [[server-key server-data] (concat
                                       (-> state :by-server u/valid-servers)
                                       (-> state u/complex-servers))]
      (let [server-url (-> server-data :client-data :server-url)
            {:keys [servers spring-isolation-dir]} state
            spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                            spring-isolation-dir)
            spring-root-path (fs/canonical-path spring-root)

            spring (-> state :by-spring-root (get spring-root-path))

            new-sync-status (resource/sync-status server-data spring (:mod-details state) (:map-details state))

            new-sync-number (handler/sync-number new-sync-status)
            {:keys [battle client-data username]} server-data
            {:keys [battle-status team-color]} (-> battle :users (get username))
            old-sync-number (-> battle :users (get username) :battle-status :sync)]
        (when (and (:battle-id battle)
                   (not= old-sync-number new-sync-number))
          (log/info "Updating battle sync status for" server-key "from" old-sync-number
                    "to" new-sync-number)
          (if (#{:direct-client :direct-host} (u/server-type server-key))
            (fx.event.battle/update-player-or-bot-state
              state-atom
              server-key
              {:username username}
              {:battle-status {:sync new-sync-number}})
            (let [new-battle-status (assoc battle-status :sync new-sync-number)]
              (message/send-message state-atom client-data
                (str "MYBATTLESTATUS " (cu/encode-battle-status new-battle-status) " " (or team-color 0))))))))))


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
          (let [map-details (read-map-details map-data)
                map-details (if (or (not map-details) (:error map-details))
                              error-data
                              map-details)]
            (log/info "Got map details for" map-name map-file (keys map-details))
            (swap! *state update :map-details cache/miss cache-key map-details)))
        (do
          (log/info "Map not found, setting empty details for" map-name)
          (swap! *state update :map-details cache/miss cache-key {:tries new-tries})))
      (catch Throwable t
        (log/error t "Error updating map details")
        (swap! *state update :map-details cache/miss cache-key error-data)
        (throw t)))
    (update-battle-sync-statuses *state)))


(defmethod task-handler ::mod-details
  [{:keys [mod-name mod-file use-git-mod-version]}]
  (let [error-data {:mod-name mod-name
                    :file mod-file
                    :error true
                    :tries 1} ; TODO inc
        cache-key (fs/canonical-path mod-file)]
    (try
      (if mod-file
        (do
          (log/info "Updating mod details for" mod-name)
          (let [mod-details (or (read-mod-data mod-file {:use-git-mod-version use-git-mod-version}) error-data)]
            (log/info "Got mod details for" mod-name mod-file (keys mod-details))
            (swap! *state update :mod-details cache/miss cache-key mod-details)
            (when (:error mod-details)
              (task/add-task! *state
                {::task-type ::refresh-mods}))))
        (do
          (log/info "Battle mod not found, setting empty details for" mod-name)
          (swap! *state update :mod-details cache/miss cache-key {})))
      (catch Throwable t
        (log/error t "Error updating mod details")
        (swap! *state update :mod-details cache/miss cache-key error-data)
        (throw t)))
    (update-battle-sync-statuses *state)))


(defmethod task-handler ::replay-details [{:keys [replay-file]}]
  (let [cache-key (fs/canonical-path replay-file)]
    (try
      (if (and replay-file (fs/exists? replay-file))
        (do
          (log/info "Updating replay details for" replay-file)
          (let [replay-details (replay/parse-replay replay-file {:details true :parse-stream true})]
            (log/info "Got replay details for" replay-file (keys replay-details))
            (swap! *state update :replay-details cache/miss cache-key replay-details)))
        (do
          (log/info "Replay not found, setting empty details for" replay-file)
          (swap! *state update :replay-details cache/miss cache-key {:error true})))
      (catch Throwable t
        (log/error t "Error updating replay details")
        (swap! *state update :replay-details cache/miss cache-key {:error true})
        (throw t)))))


(defmethod task-handler ::update-cached-minimaps
  [{:keys [maps priorities]}]
  (let [todo-maps (or maps
                      (let [{:keys [by-spring-root]} @*state]
                        (mapcat :maps (vals by-spring-root))))]
    (log/info "Found" (count todo-maps) "maps to update cached minimaps for")
    (update-cached-minimaps todo-maps {:priorities priorities})))

(defmethod task-handler ::refresh-replays [_]
  (refresh-replays *state))

(defmethod task-handler ::refresh-replay-resources [_]
  (refresh-replay-resources *state))


(defn parse-battle-status-message [battle-status-message]
  (if (string/includes? battle-status-message "Battle lobby is empty")
    []
    (let [lines (string/split battle-status-message #"\n")
          [_blank _titles counts & rem-lines] lines
          table-lines (take-while #(not (string/starts-with? % "=====================")) rem-lines)
          counts (when counts
                   (map count (string/split counts #"\s+")))]
      (->> table-lines
           (map
             (fn [line]
               (->> counts
                    (reduce
                      (fn [{:keys [line parts]} n]
                        (let [n (min (+ 2 n) (count line))]
                          {:line (subs line n)
                           :parts (conj parts (subs line 0 n))}))
                      {:line line
                       :parts []})
                    :parts
                    (map string/trim)
                    (zipmap [:username :ally :id :clan :ready :rank :skill :user-id]))))
           (map
             (fn [{:keys [ally id] :as user}]
               (let [id (some-> id u/to-number int dec)
                     ally (some-> ally u/to-number int dec)]
                 (assoc user :battle-status {:id id
                                             :ally ally
                                             :mode (boolean (and id ally))}
                        :skilluncertainty 0))))))))

(defmethod event-handler ::select-battle [{:fx/keys [event] :keys [server-key]}]
  (let [battle-id (:battle-id event)
        wait-time 1000
        state (swap! *state update-in [:by-server server-key]
                (fn [{:keys [users] :as server-data}]
                  (let [
                        {:keys [host-username]} (get-in server-data [:battles battle-id])]
                    (cond-> (assoc server-data :selected-battle battle-id)
                            (get-in users [host-username :client-status :bot])
                            (assoc-in [:channels (u/user-channel-name host-username) :capture-until] (+ (u/curr-millis) wait-time))))))
        {:keys [users] :as server-data} (get-in state [:by-server server-key])
        {:keys [host-username]} (get-in server-data [:battles battle-id])
        is-bot (get-in users [host-username :client-status :bot])
        channel-name (u/user-channel-name host-username)]
    (future
      (try
        (when (and is-bot (:show-battle-preview state))
          (log/info "Capturing chat from" channel-name)
          @(event-handler
             {:event/type ::send-message
              :channel-name channel-name
              :message (str "!status battle")
              :no-clear-draft true
              :no-history true
              :server-key server-key})
          (async/<!! (async/timeout wait-time))
          (let [path [:by-server server-key :channels channel-name :capture]
                [old-state _new-state] (swap-vals! *state assoc-in path nil)
                captured (get-in old-state path)
                parsed (when captured (parse-battle-status-message captured))]
            (log/info "Captured chat from" channel-name ":" captured)
            (swap! *state assoc-in [:by-server server-key :battles battle-id :user-details]
              (into {} (map (juxt :username identity) parsed)))))
        (catch Exception e
          (log/error e "Error parsing battle status response"))))))


(defmethod event-handler ::select-scenario [{:fx/keys [event]}]
  (swap! *state assoc :selected-scenario (:scenario event)))


(defmethod event-handler ::on-mouse-clicked-battles-row
  [{:fx/keys [^MouseEvent event] :as e}]
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
            (assoc-in [:selected-tab-main server-key] "chat")
            (assoc-in [:selected-tab-channel server-key] channel-name))))))

(defmethod event-handler ::on-mouse-clicked-users-row
  [{:fx/keys [^MouseEvent event] :keys [username] :as e}]
  (future
    (when (< 1 (.getClickCount event))
      (when username
        (event-handler (merge e {:event/type ::join-direct-message}))))))


(defmethod event-handler ::join-channel
  [{:keys [channel-name server-key]}]
  (let [{:keys [by-server]} (swap! *state
                              (fn [state]
                                (-> state
                                    (assoc-in [:by-server server-key :join-channel-name] "")
                                    (assoc-in [:my-channels server-key channel-name] {}))))
        client-data (get-in by-server [server-key :client-data])]
    (future
      (try
        (message/send-message *state client-data (str "JOIN " channel-name))
        (catch Exception e
          (log/error e "Error joining channel" channel-name))))))

(defmethod event-handler ::leave-channel
  [{:keys [channel-name server-key] :fx/keys [^Event event]}]
  (.consume event)
  (let [{:keys [by-server]} (swap! *state
                              (fn [state]
                                (-> state
                                    (update-in [:by-server server-key :my-channels] dissoc channel-name)
                                    (update-in [:my-channels server-key] dissoc channel-name))))
        client-data (get-in by-server [server-key :client-data])]
    (future
      (try
        (when-not (string/starts-with? channel-name "@")
          (message/send-message *state client-data (str "LEAVE " channel-name)))
        (catch Exception e
          (log/error e "Error leaving channel" channel-name))))))


(defmethod event-handler ::friend-request [{:keys [client-data username]}]
  (message/send-message *state client-data (str "FRIENDREQUEST userName=" username)))

(defmethod event-handler ::unfriend [{:keys [client-data username]}]
  (message/send-message *state client-data (str "UNFRIEND userName=" username))
  (let [server-key (u/server-key client-data)]
    (swap! *state update-in [:by-server server-key :friends] dissoc username)))

(defmethod event-handler ::accept-friend-request [{:keys [client-data username]}]
  (message/send-message *state client-data (str "ACCEPTFRIENDREQUEST userName=" username))
  (let [server-key (u/server-key client-data)]
    (swap! *state update-in [:by-server server-key :friend-requests] dissoc username)))

(defmethod event-handler ::decline-friend-request [{:keys [client-data username]}]
  (message/send-message *state client-data (str "DECLINEFRIENDREQUEST userName=" username))
  (let [server-key (u/server-key client-data)]
    (swap! *state update-in [:by-server server-key :friend-requests] dissoc username)))


(defn- update-disconnected!
  ([state-atom server-key]
   (update-disconnected! state-atom server-key nil))
  ([state-atom server-key {:keys [error-message user-requested]}]
   (log/info "Disconnecting from" (pr-str server-key))
   (let [[old-state _new-state] (swap-vals! state-atom
                                  (fn [state]
                                    (let [normal-logout (get-in state [:normal-logout server-key])]
                                      (cond-> (-> state
                                                  (update :by-server dissoc server-key)
                                                  (update :needs-focus dissoc server-key)
                                                  (update :needs-focus dissoc server-key))
                                              (and (not normal-logout)
                                                   (not user-requested))
                                              (update-in [:login-error server-key] str "\n" (or error-message "Connection lost"))
                                              user-requested
                                              (assoc-in [:normal-logout server-key] true)))))
         {:keys [client-data ping-loop print-loop]} (-> old-state :by-server (get server-key))]
     (if client-data
       (client/disconnect *state client-data)
       (do
         (log/trace (ex-info "stacktrace" {:server-key server-key}) "No client to disconnect!")
         (log/info "No client to disconnect for" server-key)))
     (if ping-loop
       (future-cancel ping-loop)
       (do
         (log/trace (ex-info "stacktrace" {:server-key server-key}) "No ping loop to cancel!")
         (log/info "No ping loop to disconnect for" server-key)))
     (if print-loop
       (future-cancel print-loop)
       (do
         (log/trace (ex-info "stacktrace" {:server-key server-key}) "No print loop to cancel!")
         (log/info "No print loop to disconnect for" server-key))))
   nil))

(defmethod event-handler ::print-state [_e]
  (pprint *state))


(defmethod event-handler ::disconnect [{:keys [server-key]}]
  (if (map? server-key)
    (let [[old-state _new-state] (swap-vals! *state update :by-server dissoc server-key)
          {:keys [client-close-fn server-close-fn]} (get-in old-state [:by-server server-key])]
      (when (fn? server-close-fn)
        (log/info "Closing server for" server-key)
        (server-close-fn))
      (when (fn? client-close-fn)
        (log/info "Closing client for" server-key)
        (client-close-fn)))
    (update-disconnected! *state server-key {:user-requested true})))

(defn- connect
  [state-atom {:keys [client-data server] :as state}]
  (let [{:keys [client-deferred server-key]} client-data]
    (future
      (try
        (let [^SplicedStream client @client-deferred]
          (s/on-closed client
            (fn []
              (log/info "client closed")
              (update-disconnected! *state server-key {:error-message "Connection closed"})))
          (s/on-drained client
            (fn []
              (log/info "client drained")
              (update-disconnected! *state server-key {:error-message "Connection drained"})))
          (if (s/closed? client)
            (log/warn "client was closed on create")
            (let [[server-url _server-data] server
                  client-data (assoc client-data :client client)]
              (log/info "Connecting to" server-key)
              (swap! state-atom
                (fn [state]
                  (-> state
                      (update :login-error dissoc server-url)
                      (assoc-in [:by-server server-key :client-data :client] client))))
              (client/connect state-atom (assoc state :client-data client-data)))))
        (catch Exception e
          (log/error e "Connect error")
          (swap! state-atom update-in [:login-error server-key] str "\nException: " (.getMessage e))
          (update-disconnected! *state server-key)))
      nil)))

(defmethod event-handler ::connect [{:keys [no-focus server server-key password username] :as state}]
  (future
    (try
      (let [[server-url server-opts] server
            client-data (client/client server-url server-opts)
            client-data (assoc client-data
                               :server-key server-key
                               :server-url server-url
                               :ssl (:ssl (second server))
                               :password password
                               :username username)]
        (swap! *state
               (fn [state]
                 (cond-> state
                         true
                         (update-in [:by-server server-key]
                           assoc :client-data client-data
                                 :server server)
                         true
                         (update :login-error dissoc server-key)
                         (not no-focus)
                         (assoc :selected-server-tab server-key))))
        (connect *state (assoc state :client-data client-data)))
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


(def window-to-tab
  {
   :show-downloader "http"
   :show-importer "import"
   :show-rapid-downloader "rapid"
   :show-replays "replays"
   :show-scenarios-window "scenarios"
   :show-settings-window "settings"
   :show-spring-picker "spring"
   :show-tasks-window "tasks"
   :show-direct-connect "direct"})

(def tab-to-window-key
  (clojure.set/map-invert window-to-tab))

(defmethod event-handler ::toggle-window
  [{:fx/keys [event] :keys [windows-as-tabs] :as e}]
  (let [k (:key e)
        v (boolean (or (:value e) event))
        inv (not v)]
    (if windows-as-tabs
      (swap! *state
        (fn [state]
          (let [tab (get window-to-tab k)]
            (cond-> (assoc state k v)
              tab
              (assoc :selected-server-tab tab)))))
      (do
        (swap! *state assoc k inv)
        (future
          (async/<!! (async/timeout 10))
          (swap! *state assoc k v))))))


(defmethod event-handler ::on-change-server
  [{:fx/keys [event] :as e}]
  (swap! *state
    (fn [state]
      (let [server-url (first event)
            {:keys [username password]} (-> state :logins (get server-url))]
        (-> state
            (assoc :server event)
            (assoc :username username)
            (assoc :password password)))))
  (event-handler (assoc e :event/type ::edit-server)))


(defmethod event-handler ::register [{:keys [email password server username]}]
  (future
    (try
      (let [[server-url server-opts] server
            {:keys [client-deferred]} (client/client server-url server-opts)
            client @client-deferred
            client-data {:client client
                         :client-deferred client-deferred
                         :server-url server-url}]
        (swap! *state dissoc :password-confirm)
        (message/send-message *state client-data
          (str "REGISTER " username " " (u/base64-md5 password) " " email))
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

(defmethod event-handler ::confirm-agreement
  [{:keys [client-data server-key verification-code]}]
  (future
    (try
      (message/send-message *state client-data (str "CONFIRMAGREEMENT " verification-code))
      (swap! *state update-in [:by-server server-key] dissoc :agreement :verification-code)
      (catch Exception e
        (log/error e "Error confirming agreement")))))


(defmethod event-handler ::request-reset-password [{:keys [email server]}]
  (future
    (try
      (let [[server-url server-opts] server
            {:keys [client-deferred]} (client/client server-url server-opts)
            client @client-deferred
            client-data {:client client
                         :client-deferred client-deferred
                         :server-url server-url}]
        (message/send-message *state client-data
          (str "RESETPASSWORDREQUEST " email))
        (loop []
          (when-let [d (s/take! client)]
            (when-let [m @d]
              (log/info (str "(request reset password " server-url ") <" "'" m "'"))
              (swap! *state assoc :request-reset-password-response m)
              (when-not (Thread/interrupted)
                (recur)))))
        (s/close! client))
      (catch Exception e
        (log/error e "Error resetting password with" server)))))

(defmethod event-handler ::reset-password [{:keys [email server verification-code]}]
  (future
    (try
      (let [[server-url server-opts] server
            {:keys [client-deferred]} (client/client server-url server-opts)
            client @client-deferred
            client-data {:client client
                         :client-deferred client-deferred
                         :server-url server-url}]
        (message/send-message *state client-data
          (str "RESETPASSWORD " email " " verification-code))
        (loop []
          (when-let [d (s/take! client)]
            (when-let [m @d]
              (log/info (str "(reset password " server-url ") <" "'" m "'"))
              (swap! *state assoc :reset-password-response m)
              (when-not (Thread/interrupted)
                (recur)))))
        (s/close! client))
      (catch Exception e
        (log/error e "Error resetting password with" server)))))


(defmethod event-handler ::edit-server
  [{:fx/keys [event]}]
  (let [[_server-url server-data] event]
    (swap! *state assoc
           :server-edit server-data
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
          (log/info "Attempting to update spring dir to" spring-isolation-dir-draft)
          (let [f (io/file spring-isolation-dir-draft)
                isolation-dir (if (fs/exists? f)
                                f
                                (fs/default-spring-root))]
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
         max-players 8
         rank 0
         engine "Spring"}}]
  (let [password (if (string/blank? battle-password) "*" battle-password)
        host-port (int (or (u/to-number host-port) 8452))]
    (message/send-message *state client-data
      (str "OPENBATTLE " battle-type " " nat-type " " password " " host-port " " max-players
           " " mod-hash " " rank " " map-hash " " engine "\t" engine-version "\t" map-name "\t" title
           "\t" mod-name))))


(defmethod event-handler ::host-battle
  [{:keys [client-data scripttags host-battle-state use-git-mod-version] :as e}]
  (let [{:keys [by-server]} (swap! *state assoc :show-host-battle-window false)
        server-key (u/server-key client-data)
        {:keys [battle]} (get by-server server-key)
        {:keys [engine-version map-name mod-name]} host-battle-state]
    (when battle
      @(event-handler (merge e {:event/type ::leave-battle}))
      (async/<!! (async/timeout 500)))
    (if-not (or (string/blank? engine-version)
                (string/blank? mod-name)
                (string/blank? map-name))
      (future
        (try
          (let [adjusted-modname (if use-git-mod-version
                                   mod-name
                                   (u/mod-name-fix-git mod-name))]
            (open-battle client-data (assoc host-battle-state :mod-name adjusted-modname)))
          (when (seq scripttags)
            (message/send-message *state client-data (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags))))
          (catch Exception e
            (log/error e "Error opening battle"))))
      (log/info "Invalid data to host battle" host-battle-state))))


(defmethod event-handler ::leave-battle [{:keys [client-data consume server-key] :fx/keys [^Event event]}]
  (when consume
    (.consume event))
  (future
    (try
      (swap! *state assoc-in [:last-battle server-key :should-rejoin] false)
      (message/send-message *state client-data "LEAVEBATTLE")
      (swap! *state update-in [:by-server server-key]
        (fn [server-data]
          (let [battle (:battle server-data)]
            (-> server-data
                (assoc-in [:old-battles (:battle-id battle)] battle)
                (dissoc :auto-unspec :battle)))))
      (catch Exception e
        (log/error e "Error leaving battle")))))


(defmethod event-handler ::join-battle
  [{:keys [battle battle-password battle-passworded client-data selected-battle] :as e}]
  (future
    (try
      (if selected-battle
        (let [server-key (u/server-key client-data)]
          (if (:battle-id battle)
            (do
              (swap! *state assoc-in [:by-server server-key :join-after-leave] {:battle-id selected-battle
                                                                                :battle-password battle-password
                                                                                :battle-passworded battle-passworded})
              (event-handler (merge e {:event/type ::leave-battle})))
            (handler/join-battle *state server-key client-data selected-battle e)))
        (log/warn "No battle to join" e))
      (catch Exception e
        (log/error e "Error joining battle")))))

(defmethod event-handler ::start-singleplayer-battle
  [_e]
  (future
    (try
      (swap! *state
        (fn [{:keys [by-spring-root spring-isolation-dir username] :as state}]
          (let [{:keys [engine-version map-name mod-name]} (get by-spring-root (fs/canonical-path spring-isolation-dir))
                username (or (when-not (string/blank? username) username)
                             "You")]
            (-> state
                (assoc :selected-server-tab "singleplayer")
                (assoc-in [:by-server :local :client-data] nil)
                (assoc-in [:by-server :local :server-key] :local)
                (assoc-in [:by-server :local :username] username)
                (assoc-in [:by-server :local :battles :singleplayer] {:battle-version engine-version
                                                                      :battle-map map-name
                                                                      :battle-modname mod-name
                                                                      :host-username username})
                (assoc-in [:by-server :local :battle]
                          {:battle-id :singleplayer
                           :scripttags {"game" {"startpostype" 0}}
                           :users {username {:battle-status (assoc cu/default-battle-status :mode true)
                                             :team-color (first color/ffa-colors-spring)}}})))))
      (catch Exception e
        (log/error e "Error starting singleplayer battle")))))

(defn- update-filter-fn [^KeyEvent event]
  (fn [x]
    (if (= KeyCode/BACK_SPACE (.getCode event))
      (apply str (drop-last x))
      (str x (.getText event)))))

(defmethod event-handler ::maps-key-pressed [{:fx/keys [event]}]
  (swap! *state update :map-input-prefix (update-filter-fn event)))

(defmethod event-handler ::host-replay-key-pressed [{:fx/keys [event]}]
  (swap! *state update :filter-host-replay (update-filter-fn event)))

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

(defmethod event-handler ::mods-key-pressed [{:fx/keys [event]}]
  (swap! *state update :mod-filter (update-filter-fn event)))

(defmethod event-handler ::desktop-browse-dir
  [{:keys [file]}]
  (future
    (try
      (let [desktop (Desktop/getDesktop)
            ^File
            file (if (fs/exists? file)
                   file
                   (fs/first-existing-parent file))
            runtime (Runtime/getRuntime)]
        (cond
          (.isSupported desktop Desktop$Action/BROWSE_FILE_DIR)
          (if (fs/is-directory? file)
            (.browseFileDirectory desktop (fs/file file "dne"))
            (.browseFileDirectory desktop file))
          (fs/wsl-or-windows?)
          (let [
                command (concat
                          ["explorer.exe"]
                          (if (fs/is-directory? file)
                            [(fs/wslpath file)]
                            ["/select," ; https://superuser.com/a/809644
                             (str "\"" (fs/wslpath file) "\"")]))
                ^"[Ljava.lang.String;" cmdarray (into-array String command)]
            (log/info "Running" (pr-str command))
            (.exec runtime cmdarray nil nil))
          (fs/linux?)
          (let [
                command ["dbus-send" (str "--bus=" (System/getenv "DBUS_SESSION_BUS_ADDRESS")) 
                         "--print-reply"
                         "--dest=org.freedesktop.FileManager1"
                         "--type=method_call" 
                         "/org/freedesktop/FileManager1"
                         "org.freedesktop.FileManager1.ShowItems"
                         (str "array:string:" (-> file .toURI .toURL str))
                         "string:"]
                ^"[Ljava.lang.String;" cmdarray (into-array String command)
                _ (log/info "Running" (pr-str command))
                process (.exec runtime cmdarray nil nil)
                out {:exit-code (.waitFor process)
                     :stdout (slurp (.getInputStream process))
                     :stderr (slurp (.getErrorStream process))}]
            (log/info "Result from dbus-send" out))
          :else
          (let [
                dir-or-parent (if (fs/is-directory? file)
                                file
                                (fs/parent-file file))
                command ["xdg-open" (fs/canonical-path dir-or-parent)]
                ; https://stackoverflow.com/a/5116553
                ^"[Ljava.lang.String;" cmdarray (into-array String command)]
            (log/info "Running" (pr-str command))
            (.exec runtime cmdarray nil nil))))
      (catch Exception e
        (log/error e "Error browsing file" file)))))

(defn browse-url [url]
  (let [desktop (Desktop/getDesktop)]
    (if (.isSupported desktop Desktop$Action/BROWSE)
      (.browse desktop (java.net.URI. url))
      (when (fs/linux?)
        (let [runtime (Runtime/getRuntime)
              command ["xdg-open" url] ; https://stackoverflow.com/a/5116553
              ^"[Ljava.lang.String;" cmdarray (into-array String command)]
          (log/info "Running" (pr-str command))
          (.exec runtime cmdarray nil nil))))))

(defmethod event-handler ::desktop-browse-url
  [{:keys [url]}]
  (future
    (try
      (browse-url url)
      (catch Exception e
        (log/error e "Error browsing url" url)))))


; https://github.com/cljfx/cljfx/blob/ec3c34e619b2408026b9f2e2ff8665bebf70bf56/examples/e33_file_chooser.clj
(defmethod event-handler ::file-chooser-dir
  [{:fx/keys [^Event event]
    :keys [as-path initial-dir path post-task]}]
  (try
    (let [^Node node (.getTarget event)
          window (.getWindow (.getScene node))
          chooser (doto (DirectoryChooser.)
                    (.setTitle "Select Spring Directory")
                    (.setInitialDirectory initial-dir))]
      (when-let [file (.showDialog chooser window)]
        (log/info "Setting spring isolation dir at" path "to" file)
        (let [v (if as-path
                  (fs/canonical-path file)
                  file)]
          (swap! *state assoc-in path v))
        (when post-task
          (task/add-task! *state post-task))))
    (catch Exception e
      (log/error e "Error showing spring directory chooser"))))

(defmethod event-handler ::file-chooser-ring-sound
  [{:fx/keys [^Event event] :keys [target] :or {target [:ring-sound-file]}}]
  (try
    (let [^Node node (.getTarget event)
          window (.getWindow (.getScene node))
          chooser (doto (FileChooser.)
                    (.setTitle "Select Ring Sound File")
                    (.setInitialDirectory (fs/app-root)))]
      (when-let [file (.showOpenDialog chooser window)]
        (log/info "Setting ring sound file at" target "to" file)
        (swap! *state assoc-in target file)))
    (catch Exception e
      (log/error e "Error showing ring sound file chooser"))))


(defmethod event-handler ::update-css
  [{:keys [css]}]
  (log/info "Registering CSS with" (count css) "keys")
  (let [registered (css/register :skylobby.fx/current css)]
    (swap! *state assoc :css registered)))

(defmethod event-handler ::load-custom-css-edn
  [{:keys [file]}]
  (if (fs/exists? file)
    (try
      (log/info "Loading CSS as EDN from" file)
      (let [css (edn/read-string (slurp file))]
        (event-handler {:css css
                        :event/type ::update-css})
        (swap! *state assoc :load-custom-css-edn-message "Success"))
      (catch Exception e
        (log/error e "Error loading custom css from edn at" file)
        (swap! *state assoc :load-custom-css-edn-message (str "Error: " (.getMessage e)))))
    (do
      (log/warn "Custom CSS file does not exist" file)
      (swap! *state assoc :load-custom-css-edn-message "Error: file does not exist"))))

(defmethod event-handler ::load-custom-css
  [{:keys [file]}]
  (if (fs/exists? file)
    (swap! *state assoc :css (slurp file))
    (log/warn "Custom CSS file does not exist" file)))


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
        (message/send-message *state client-data m))
      (catch Exception e
        (log/error e "Error changing battle map")))))

(defmethod event-handler ::suggest-battle-map
  [{:fx/keys [event] :keys [battle-status channel-name client-data map-name server-key]}]
  (future
    (try
      (cond
        (string/blank? channel-name) (log/warn "No channel to suggest battle map")
        (not (:mode battle-status)) (log/info "Cannot suggest battle map as spectator")
        :else
        (let [map-name (or map-name event)]
          @(event-handler
             {:event/type :skylobby.fx.event.chat/send
              :channel-name channel-name
              :client-data client-data
              :message (str "!map " map-name)
              :no-clear-draft true
              :server-key server-key})))
      (catch Exception e
        (log/error e "Error suggesting map")))))

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
  [{:keys [battle bot-username bot-name bot-version client-data server-key side-indices username]}]
  (swap! *state dissoc :show-add-bot)
  (future
    (try
      (let [existing-bots (keys (:bots battle))
            bot-username (available-name existing-bots bot-username)
            bot-color (u/random-color)
            status (assoc cu/default-battle-status
                          :ready true
                          :mode true
                          :sync 1
                          :id (battle/available-team-id battle)
                          :ally (battle/available-ally battle)
                          :side (if (seq side-indices)
                                  (rand-nth side-indices)
                                  0))]
        (case (u/server-type server-key)
          :spring-lobby
          (let [
                bot-status (cu/encode-battle-status status)]
            (message/send-message
              *state client-data
              (str "ADDBOT " bot-username " " bot-status " " bot-color " " bot-name "|" bot-version)))
          (event-handler
            {:event/type :skylobby.fx.event.battle/add-bot
             :bot-data {:bot-name bot-username
                        :ai-name bot-name
                        :ai-version bot-version
                        :team-color bot-color
                        :battle-status status
                        :owner username}
             :server-key server-key})))
      (catch Exception e
        (log/error e "Error adding bot")))))

(defmethod event-handler ::change-bot-name
  [{:keys [bots] :fx/keys [event]}]
  (let [bot-name event
        bot-version (-> (group-by :bot-name bots)
                        (get bot-name)
                        first
                        :bot-version)]
    (swap! *state assoc :bot-name bot-name :bot-version bot-version)))


(defmethod event-handler ::start-battle
  [{:keys [am-host am-spec battle-status channel-name client-data host-ingame server-key] :as state}]
  (let [server-key (or server-key
                       (u/server-key client-data))]
    (swap! *state assoc-in [:spring-starting server-key (-> state :battle :battle-id)] true)
    (future
      (try
        (when-not (:mode battle-status)
          (if (and (:server-url client-data)
                   (or (string/starts-with? (:server-url client-data) "bar.teifion.co.uk")
                       (string/starts-with? (:server-url client-data) "road-flag.bnr.la")))
            @(event-handler
               {:event/type ::send-message
                :channel-name channel-name
                :client-data client-data
                :message (str "!joinas spec")
                :no-clear-draft true})
            (log/info "Skipping !joinas spec for this server"))
          (async/<!! (async/timeout 1000)))
        (if (or am-host am-spec host-ingame)
          (spring/start-game *state state)
          (do
            @(event-handler
               {:event/type ::send-message
                :channel-name channel-name
                :client-data client-data
                :message (str "!cv start")
                :no-clear-draft true})
            (swap! *state assoc-in [:spring-starting server-key (-> state :battle :battle-id)] false)))
        (catch Exception e
          (log/error e "Error starting battle"))))))


(defmethod event-handler ::play-scenario
  [{:keys [difficulties mod-name scenario-options script-template script-params] :as state}]
  (future
    (try
      (let [difficulties-by-name (into {}
                                   (map (juxt :name identity) difficulties))
            {:keys [enemyhandicap playerhandicap]} (get difficulties-by-name (:difficulty script-params))
            restrictions (get script-params :restricted-units {})
            script-txt (-> script-template
                           (string/replace #"__PLAYERSIDE__" (:side script-params))
                           (string/replace #"__ENEMYHANDICAP__" (str enemyhandicap))
                           (string/replace #"__PLAYERHANDICAP__" (str playerhandicap))
                           (string/replace #"__SCENARIOOPTIONS__" (str (u/base64-encode (.getBytes (json/generate-string scenario-options)))))
                           (string/replace #"__NUMRESTRICTIONS__" (str (count restrictions)))
                           ; https://github.com/beyond-all-reason/spring/blob/7f308f9/doc/StartScriptFormat.txt#L127-L128
                           (string/replace #"__RESTRICTEDUNITS__" (->> restrictions
                                                                       (map-indexed vector)
                                                                       (mapcat
                                                                         (fn [[i [k v]]]
                                                                           [{(str "Unit" i) (name k)}
                                                                            {(str "Limit" i) v}]))
                                                                       (into {})
                                                                       spring/script-txt))
                           (string/replace #"__MAPNAME__" (get script-params :map-name))
                           (string/replace #"__BARVERSION__" mod-name)
                           (string/replace #"__PLAYERNAME__" (get script-params :player-name)))]
       (spring/start-game *state (assoc state :script-txt script-txt)))
      (catch Exception e
        (log/error e "Error starting scenario")))))


(defmethod event-handler ::minimap-mouse-pressed
  [{:fx/keys [^MouseEvent event]
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
                                        (< x ex (+ x (* 2 u/start-pos-r)))
                                        (< y ey (+ y (* 2 u/start-pos-r))))
                                  target))
                              starting-points)]
            (swap! *state assoc :drag-team {:team (:team target)
                                            :x (- ex u/start-pos-r)
                                            :y (- ey u/start-pos-r)})))
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
                              i))
              close-size (* 2 u/start-pos-r)
              target (some
                       (fn [{:keys [allyteam x y width height]}]
                         (when (and allyteam x y width height)
                           (let [xt (+ x width)]
                             (when (and
                                     (< (- xt close-size) ex (+ xt close-size))
                                     (< (- y close-size) ey (+ y close-size)))
                               allyteam))))
                       start-boxes)]
          (if target
            (log/info "Mousedown on close button for box" target)
            (log/info "Mousedown to start start box for allyteam" allyteam-id))
          (swap! *state assoc :drag-allyteam {:allyteam-id allyteam-id
                                              :startx ex
                                              :starty ey
                                              :endx ex
                                              :endy ey
                                              :target target}))
        :else
        nil)
      (catch Exception e
        (log/error e "Error pressing minimap")))))


(defmethod event-handler ::minimap-mouse-dragged
  [{:fx/keys [^MouseEvent event]}]
  (future
    (try
      (let [^Canvas canvas (.getTarget event)
            width (.getWidth canvas)
            height (.getHeight canvas)
            x (min width (max 0 (.getX event)))
            y (min height (max 0 (.getY event)))]
        (swap! *state
               (fn [state]
                 (cond
                   (:drag-team state)
                   (update state :drag-team assoc
                           :x (- x u/start-pos-r)
                           :y (- y u/start-pos-r))
                   (:drag-allyteam state)
                   (update state :drag-allyteam assoc
                     :endx x
                     :endy y)
                   :else
                   state))))
      (catch Exception e
        (log/error e "Error dragging minimap")))))

(def min-box-size 0.05)

(defmethod event-handler ::minimap-mouse-released
  [{:keys [am-host client-data minimap-scale minimap-width minimap-height map-details singleplayer]
    :or {minimap-scale 1.0}
    :as e}]
  (future
    (try
      (let [[before _after] (swap-vals! *state dissoc :drag-team :drag-allyteam)]
        (when-let [{:keys [team x y]} (:drag-team before)]
          (let [{:keys [map-width map-height]} (-> map-details :smf :header)
                x (int (* (/ x (* minimap-width minimap-scale)) map-width spring/map-multiplier))
                z (int (* (/ y (* minimap-height minimap-scale)) map-height spring/map-multiplier))
                team-data {:startposx x
                           :startposy z ; for SpringLobby bug
                           :startposz z}
                scripttags {"game" {(str "team" team) team-data}}]
            (if singleplayer
              (swap! *state update-in
                     [:by-server :local :battle :scripttags "game" (str "team" team)]
                     merge team-data)
              (message/send-message *state client-data
                (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags))))))
        (when-let [{:keys [allyteam-id startx starty endx endy target]} (:drag-allyteam before)]
          (let [l (min startx endx)
                t (min starty endy)
                r (max startx endx)
                b (max starty endy)
                left (/ l (* minimap-scale minimap-width))
                top (/ t (* minimap-scale minimap-height))
                right (/ r (* minimap-scale minimap-width))
                bottom (/ b (* minimap-scale minimap-height))]
            (if (and (< min-box-size (- right left))
                     (< min-box-size (- bottom top)))
              (if singleplayer
                (swap! *state update-in [:by-server :local :battle :scripttags "game" (str "allyteam" allyteam-id)]
                       (fn [allyteam]
                         (assoc allyteam
                                :startrectleft left
                                :startrecttop top
                                :startrectright right
                                :startrectbottom bottom)))
                (if am-host
                  (message/send-message *state client-data
                    (str "ADDSTARTRECT " allyteam-id " "
                         (int (* 200 left)) " "
                         (int (* 200 top)) " "
                         (int (* 200 right)) " "
                         (int (* 200 bottom))))
                  (event-handler
                    (assoc e
                           :event/type ::send-message
                           :no-clear-draft true
                           :message
                           (str "!addBox "
                                (int (* 200 left)) " "
                                (int (* 200 top)) " "
                                (int (* 200 right)) " "
                                (int (* 200 bottom)) " "
                                (inc allyteam-id))))))
              (if target
                (do
                  (log/info "Clearing box" target)
                  (if singleplayer
                    (swap! *state update-in [:by-server :local :battle :scripttags "game"] dissoc (str "allyteam" target))
                    (if am-host
                      (message/send-message *state client-data (str "REMOVESTARTRECT " target))
                      (event-handler
                        (assoc e
                               :event/type ::send-message
                               :no-clear-draft true
                               :message (str "!clearBox " (inc (int (u/to-number target)))))))))
                (log/info "Start box too small, ignoring" left top right bottom))))))
      (catch Exception e
        (log/error e "Error releasing minimap")))))


(defn- update-copying [f copying]
  (if f
    (swap! *state update-in [:copying (fs/canonical-path f)] merge copying)
    (log/warn "Attempt to update copying for nil file")))

(defmethod event-handler ::add-task [{:keys [task]}]
  (task/add-task! *state task))

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
          ::map (refresh-maps-all-spring-roots)
          ::mod (refresh-mods-all-spring-roots)
          ::engine (refresh-engines-all-spring-roots)
          nil)))))

(defmethod task-handler ::import [e]
  (import-resource e))

(defmethod task-handler ::git-mod
  [{:keys [battle-mod-git-ref file spring-root]}]
  (when (and file battle-mod-git-ref)
    (log/info "Resetting mod at" file "to ref" battle-mod-git-ref)
    (let [canonical-path (fs/canonical-path file)]
      (swap! *state assoc-in [:gitting canonical-path] {:status true})
      (try
        (git/fetch file)
        (git/reset-hard file battle-mod-git-ref)
        (task/add-task! *state {::task-type ::refresh-mods
                                :spring-root spring-root})
        (catch Exception e
          (log/error e "Error during git reset" canonical-path "to ref" battle-mod-git-ref))
        (finally
          (swap! *state assoc-in [:gitting canonical-path] {:status false}))))))

(defmethod event-handler ::minimap-scroll
  [{:fx/keys [^ScrollEvent event] :keys [minimap-type-key]}]
  (.consume event)
  (swap! *state
         (fn [state]
           (let [minimap-type (get state minimap-type-key)
                 direction (if (pos? (.getDeltaY event))
                             dec
                             inc)
                 next-type (next-value fx.battle/minimap-types minimap-type {:direction direction})]
             (assoc state minimap-type-key next-type)))))

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


(defn- update-battle-status
  "Sends a message to update battle status for yourself or a bot of yours."
  [client-data {:keys [is-bot is-me id] :as opts} battle-status team-color]
  (let [player-name (or (:bot-name id) (:username id))]
    (if client-data
      (let [prefix (if is-bot
                     (str "UPDATEBOT " player-name)
                     "MYBATTLESTATUS")]
        (if (or is-bot is-me)
          (message/send-message *state client-data
            (str prefix
                 " "
                 (cu/encode-battle-status battle-status)
                 " "
                 (or team-color "0")))
          (log/error (ex-info "stacktrace" {}) "Call to update-battle-status for non-bot non-me player" opts battle-status team-color)))
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
  (message/send-message *state client-data (str "MYSTATUS " (cu/encode-client-status client-status))))

(defmethod event-handler ::on-change-away [{:keys [client-status] :fx/keys [event] :as e}]
  (let [away (= "Away" event)]
    (event-handler
      (assoc e
             :event/type ::update-client-status
             :client-status (assoc client-status :away away)))))

(defn- update-color [client-data id {:keys [is-me is-bot] :as opts} color-int]
  (if (or is-me is-bot)
    (update-battle-status client-data (assoc opts :id id) (:battle-status id) color-int)
    (message/send-message *state client-data
      (str "FORCETEAMCOLOR " (:username id) " " color-int))))

(defn- update-team [client-data id {:keys [is-me is-bot] :as opts} player-id]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client-data (assoc opts :id id) (assoc (:battle-status id) :id player-id) (:team-color id))
        (message/send-message *state client-data
          (str "FORCETEAMNO " (:username id) " " player-id)))
      (catch Exception e
        (log/error e "Error updating team")))))

(defn- update-ally [client-data id {:keys [is-me is-bot] :as opts} ally]
  (future
    (try
      (if (or is-me is-bot)
        (update-battle-status client-data (assoc opts :id id) (assoc (:battle-status id) :ally ally) (:team-color id))
        (message/send-message *state client-data (str "FORCEALLYNO " (:username id) " " ally)))
      (catch Exception e
        (log/error e "Error updating ally")))))

(defn- update-handicap [client-data id {:keys [is-bot] :as opts} handicap]
  (future
    (try
      (if (or is-bot (not client-data))
        (update-battle-status client-data (assoc opts :id id) (assoc (:battle-status id) :handicap handicap) (:team-color id))
        (message/send-message *state client-data (str "HANDICAP " (:username id) " " handicap)))
      (catch Exception e
        (log/error e "Error updating handicap")))))

(defn- apply-battle-status-changes
  [client-data id {:keys [is-me is-bot] :as opts} status-changes]
  (future
    (try
      (if (or (not client-data) is-me is-bot)
        (update-battle-status client-data (assoc opts :id id) (merge (:battle-status id) status-changes) (:team-color id))
        (doseq [[k v] status-changes]
          (let [msg (case k
                      :id "FORCETEAMNO"
                      :ally "FORCEALLYNO"
                      :handicap "HANDICAP")]
            (message/send-message *state client-data (str msg " " (:username id) " " v)))))
      (catch Exception e
        (log/error e "Error applying battle status changes")))))


(defn balance-teams
  ([battle-players-and-bots teams-count]
   (balance-teams battle-players-and-bots teams-count nil))
  ([battle-players-and-bots teams-count opts]
   (let [nonspec (->> battle-players-and-bots
                      (filter (comp u/to-bool :mode :battle-status))) ; remove spectators
         changes (->> nonspec
                      shuffle
                      (map-indexed
                        (fn [i id]
                          (let [ally (mod i teams-count)]
                            {:id id
                             :opts {:is-bot (boolean (:bot-name id))}
                             :status-changes {:ally ally}}))))]
     (if (:interleave opts)
       (let [by-ally (->> changes
                          (group-by (comp :ally :status-changes)))
             largest-team (reduce
                            (fnil min 0)
                            0
                            (map count (vals by-ally)))
             changes (->> by-ally
                          (map
                            (fn [[ally changes]]
                              [ally (concat changes (take (- largest-team (count changes)) (repeat nil)))]))
                              ; pad for interleave
                          (sort-by first)
                          (map second))]
         (->> changes
              (apply interleave)
              (map-indexed
                (fn [i data]
                  (assoc-in data [:status-changes :id] i)))))
       (->> changes
            (sort-by (comp :ally :status-changes))
            (map-indexed
              (fn [i data]
                (assoc-in data [:status-changes :id] i))))))))


(defn- n-teams [{:keys [am-host client-data interleave-ally-player-ids server-key] :as e} n]
  (let [new-teams (balance-teams (battle-players-and-bots e) n {:interleave interleave-ally-player-ids})]
    (if (= :local server-key)
      (let [user-changes-by-name (->> new-teams
                                      (remove (comp :is-bot :opts))
                                      (map (juxt (comp :username :id) :status-changes))
                                      (into {}))
            bot-changes-by-name (->> new-teams
                                     (filter (comp :is-bot :opts))
                                     (map (juxt (comp :bot-name :id) :status-changes))
                                     (into {}))]
        (log/info "Updating singleplayer battle")
        (swap! *state update-in [:by-server :local :battle]
          (fn [singleplayer-battle]
            (-> singleplayer-battle
                (update :users
                  (fn [users]
                    (reduce-kv
                      (fn [m k v]
                        (assoc m k (update v :battle-status merge (get user-changes-by-name k))))
                      {}
                      users)))
                (update :bots
                  (fn [bots]
                    (reduce-kv
                      (fn [m k v]
                        (assoc m k (update v :battle-status merge (get bot-changes-by-name k))))
                      {}
                      bots))))))
        (let [{:keys [battle users username]} (get-in @*state [:by-server :local])]
          (event-handler
            (assoc e
                   :event/type ::battle-fix-colors
                   :battle battle
                   :users users
                   :username username))))
      (future
        (try
          (->> new-teams
               (map
                 (fn [{:keys [id opts status-changes]}]
                   (let [is-me (= (:username e) (:username id))]
                     (apply-battle-status-changes client-data (assoc id :is-me is-me) opts status-changes))))
               doall)
          (when am-host
            (async/<!! (async/timeout 500))
            (let [{:keys [battle users username]} (get-in @*state [:by-server server-key])]
              (event-handler
                (assoc e
                       :event/type ::battle-fix-colors
                       :battle battle
                       :users users
                       :username username))))
          (catch Exception e
            (log/error e "Error updating to" n "teams")))))))

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

(defmethod event-handler ::battle-teams-5
  [e]
  (n-teams e 5))

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
        :message (str "!ring " username)
        :no-clear-draft true})))

(defmethod event-handler ::ring-specs
  [{:keys [battle-users channel-name client-data users]}]
  (future
    (when channel-name
      (try
        (doseq [[username user-data] battle-users]
          (when (and (-> user-data :battle-status :mode not)
                     (-> users (get username) :client-status :bot not))
            @(event-handler
               {:event/type ::send-message
                :channel-name channel-name
                :client-data client-data
                :message (str "!ring " username)
                :no-clear-draft true})
            (async/<!! (async/timeout 1000))))
        (catch Exception e
          (log/error e "Error ringing specs"))))))

(defmethod task-handler ::ring-specs
  [task]
  @(event-handler (assoc task :event/type ::ring-specs)))


(defn set-ignore
  ([server-key username ignore]
   (set-ignore server-key username ignore nil))
  ([server-key username ignore {:keys [channel-name]}]
   (swap! *state
     (fn [state]
       (let [channel-name (or channel-name
                              (u/visible-channel state server-key))]
         (-> state
             (assoc-in [:ignore-users server-key username] ignore)
             (update-in [:by-server server-key :channels channel-name :messages] conj {:text (str (if ignore "Ignored " "Unignored ") username)
                                                                                       :timestamp (u/curr-millis)
                                                                                       :message-type :info})))))))

(defmethod event-handler ::ignore-user
  [{:keys [channel-name server-key username]}]
  (set-ignore server-key username true {:channel-name channel-name}))

(defmethod event-handler ::unignore-user
  [{:keys [channel-name server-key username]}]
  (set-ignore server-key username false {:channel-name channel-name}))


(defmethod event-handler ::show-report-user
  [{:keys [server-key username]}]
  (swap! *state
    (fn [state]
      (-> state
          (assoc :show-report-user-window true)
          (assoc-in [:report-user server-key :message] "")
          (assoc-in [:report-user server-key :username] username)))))

(defmethod event-handler ::show-report-user
  [{:keys [battle-id server-key username]}]
  (swap! *state
    (fn [state]
      (-> state
          (assoc-in [:report-user server-key :battle-id] battle-id)
          (assoc-in [:report-user server-key :message] "")
          (assoc-in [:report-user server-key :username] username))))
  (event-handler {:event/type ::toggle :value true :key :show-report-user-window}))

; https://github.com/beyond-all-reason/teiserver/blob/master/documents/spring/extensions.md#cmoderationreport_user
(defmethod event-handler ::send-user-report
  [{:keys [battle-id client-data message username]}]
  (let [message (string/replace (str message) #"[\n\r]" "  ")]
    (message/send-message *state client-data (str "c.moderation.report_user " username "\tbattle\t" battle-id "\t" message)))
  (swap! *state dissoc :show-report-user-window))


(defmethod event-handler ::battle-startpostype-change
  [{:fx/keys [event] :keys [am-host client-data singleplayer] :as e}]
  (let [startpostype (get spring/startpostypes-by-name event)]
    (if am-host
      (if singleplayer
        (swap! *state assoc-in [:by-server :local :battle :scripttags "game" "startpostype"] startpostype)
        (message/send-message *state client-data (str "SETSCRIPTTAGS game/startpostype=" startpostype)))
      (event-handler
        (assoc e
               :event/type ::send-message
               :no-clear-draft true
               :message (str "!bSet startpostype " startpostype))))))

(defmethod event-handler ::reset-start-positions
  [{:keys [client-data server-key]}]
  (let [team-ids (take 16 (iterate inc 0))
        scripttag-keys (map (fn [i] (str "game/team" i)) team-ids)
        team-kws (map #(str "team" %) team-ids)
        dissoc-fn #(apply dissoc % team-kws)]
    (swap! *state update-in [:by-server server-key :battle :scripttags "game"] dissoc-fn)
    (message/send-message *state
      client-data
      (str "REMOVESCRIPTTAGS " (string/join " " scripttag-keys)))))

(defmethod event-handler ::clear-start-boxes
  [{:keys [allyteam-ids client-data server-key]}]
  (doseq [allyteam-id allyteam-ids]
    (let [allyteam-str (str "allyteam" allyteam-id)]
      (swap! *state update-in [:by-server server-key :battle :scripttags "game"] dissoc allyteam-str))
    (message/send-message *state client-data (str "REMOVESTARTRECT " allyteam-id))))

(defn modoption-value [modoption-type raw-value]
  (if (or (= "list" modoption-type)
          (= "string" modoption-type))
    (str raw-value)
    (u/to-number raw-value)))

(defmethod event-handler ::modoption-change
  [{:keys [am-host client-data modoption-key modoption-type option-key singleplayer] :fx/keys [event] :as e}]
  (let [value (modoption-value modoption-type event)
        option-key (or option-key "modoptions")
        modoption-key-str (name modoption-key)]
    (if singleplayer
      (swap! *state assoc-in [:by-server :local :battle :scripttags "game" option-key modoption-key-str] (str event))
      (if am-host
        (message/send-message *state client-data (str "SETSCRIPTTAGS game/" option-key "/" modoption-key-str "=" value))
        (event-handler
          (assoc e
                 :event/type ::send-message
                 :no-clear-draft true
                 :message (str "!bSet " modoption-key-str " " value)))))))

(defmethod event-handler ::show-ai-options-window
  [{:keys [bot-name bot-username bot-version server-key]}]
  (swap! *state
    (fn [state]
      (-> state
          (assoc :ai-options {:bot-name bot-name
                              :bot-username bot-username
                              :bot-version bot-version
                              :server-key server-key}))))
  (event-handler {:event/type ::toggle :value true :key :show-ai-options-window}))

(defmethod event-handler ::aioption-change
  [{:keys [bot-username modoption-key modoption-type server-key] :fx/keys [event]}]
  (let [value (modoption-value modoption-type event)]
    (swap! *state assoc-in [:by-server server-key :battle :scripttags "game" "bots" bot-username "options" (name modoption-key)] value)))

(defmethod event-handler ::save-aioptions
  [{:keys [am-host available-options bot-username channel-name client-data current-options server-key]}]
  (let [
        {:keys [by-server] :as state} (swap! *state assoc :show-ai-options-window false)
        scripttags (get-in by-server [server-key :battle :scripttags])
        available-option-keys (set (map (comp :key second) available-options))
        options (->> current-options
                     (filter (comp available-option-keys first))
                     (into {}))]
    (if-not am-host
      (let [
            json-data (json/generate-string options)]
        @(event-handler
           {:event/type :skylobby.fx.event.chat/send
            :channel-name channel-name
            :client-data client-data
            :no-clear-draft true
            :message (str "!aiProfile " bot-username " " json-data)
            :server-key server-key}))
      (if (#{:direct-host} (u/server-type server-key))
        (if-let [broadcast-fn (get-in state [:by-server server-key :server :broadcast-fn])]
          (broadcast-fn [:skylobby.direct/battle-scripttags scripttags])
          (log/warn "No broadcast-fn" server-key))
        (message/send-message *state client-data (str "SETSCRIPTTAGS " (spring-script/format-scripttags scripttags)))))))


(defmethod event-handler ::battle-ready-change
  [{:fx/keys [event] :keys [battle-status client-data team-color] :as id}]
  (swap! *state assoc-in [:by-server (u/server-key client-data) :battle :desired-ready] (boolean event))
  (future
    (try
      (update-battle-status client-data {:id id} (assoc battle-status :ready event) team-color)
      (catch Exception e
        (log/error e "Error updating battle ready")))))


(defmethod event-handler ::battle-spectate-change
  [{:keys [client-data id is-me is-bot ready-on-unspec] :fx/keys [event] :as data}]
  (let [mode (if (contains? data :value)
               (:value data)
               (not event))
        server-key (u/server-key client-data)
        battle-status (assoc (:battle-status id) :mode mode)
        desired-ready (boolean ready-on-unspec)
        battle-status (if (and (not is-bot) mode)
                        (assoc battle-status :ready desired-ready)
                        battle-status)]
    (swap! *state assoc-in [:by-server server-key :battle (if is-bot :bots :users) (:username id) :battle-status :mode] mode)
    (when (and is-me mode)
      (swap! *state assoc-in [:by-server server-key :battle :desired-ready] desired-ready))
    (future
      (try
        (if (or is-me is-bot)
          (update-battle-status client-data data battle-status (:team-color id))
          (if mode
            (message/send-message *state client-data (str "FORCESPECTATORMODE " (:username id)))
            (log/error "No method to force unspec for" (:username id))))
        (catch Exception e
          (log/error e "Error updating battle spectate"))))))

(defmethod event-handler ::on-change-spectate [{:fx/keys [event] :keys [server-key] :as e}]
  (swap! *state assoc-in [:by-server server-key :auto-unspec] false)
  (event-handler (assoc e
                        :event/type ::battle-spectate-change
                        :value (= "Playing" event))))

(defmethod event-handler ::auto-unspec [{:keys [server-key] :fx/keys [event] :as e}]
  (if event
    (do
      (swap! *state assoc-in [:by-server server-key :auto-unspec] true)
      (event-handler (assoc e
                            :event/type ::battle-spectate-change
                            :value true)))
    (swap! *state assoc-in [:by-server server-key :auto-unspec] false)))

(defmethod event-handler ::battle-side-changed
  [{:keys [client-data id indexed-mod sides] :fx/keys [event] :as data}]
  (future
    (try
      (let [side (get (clojure.set/map-invert sides) event)]
        (swap! *state assoc-in [:preferred-factions (:mod-name-only indexed-mod)] side)
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
        (let [old-id (-> id :battle-status :id u/to-number)]
          (if (not= player-id old-id)
            (do
              (log/info "Updating team for" id "from" (pr-str old-id) "to" (pr-str player-id))
              (update-team client-data id data player-id))
            (log/debug "No change for team"))))
      (catch Exception e
        (log/error e "Error updating battle team")))))

(defmethod event-handler ::battle-ally-changed
  [{:keys [client-data id] :fx/keys [event] :as data}]
  (future
    (try
      (when-let [ally (try (Integer/parseInt event) (catch Exception _e))]
        (let [old-ally (-> id :battle-status :ally u/to-number)]
          (if (not= ally old-ally)
            (do
              (log/info "Updating ally for" id "from" (pr-str old-ally) "to" (pr-str ally))
              (update-ally client-data id data ally))
            (log/debug "No change for ally"))))
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
  [{:keys [client-data id is-me] :fx/keys [^Event event] :as opts}]
  (let [^ColorPicker source (.getSource event)
        javafx-color (.getValue source)
        color-int (fx.color/javafx-color-to-spring javafx-color)]
    (when is-me
      (swap! *state assoc :preferred-color color-int))
    (update-color client-data id opts color-int)))

(defmethod event-handler ::battle-balance [{:keys [client-data channel-name]}]
  @(event-handler
     {:event/type ::send-message
      :channel-name channel-name
      :client-data client-data
      :no-clear-draft true
      :message (str "!balance")}))

(defmethod event-handler ::battle-fix-colors
  [{:keys [am-host client-data channel-name] :as e}]
  (if am-host
    (let [players-and-bots (filter
                             (comp u/to-bool :mode :battle-status) ; remove spectators
                             (battle-players-and-bots e))
          by-allyteam (group-by (comp :ally :battle-status) players-and-bots)
          teams-by-allyteam (->> by-allyteam
                                 (map
                                   (fn [[k vs]]
                                     [k (vec (sort (map (comp :id :battle-status) vs)))]))
                                 (into {}))]
      (->> players-and-bots
           (map-indexed
             (fn [_i {:keys [battle-status] :as id}]
               (let [is-bot (boolean (:bot-name id))
                     is-me (= (:username e) (:username id))
                     color (color/player-color battle-status teams-by-allyteam)
                     opts {:id id :is-me is-me :is-bot is-bot}]
                 (update-color client-data id opts color))))
           doall))
    @(event-handler
       {:event/type ::send-message
        :channel-name channel-name
        :client-data client-data
        :no-clear-draft true
        :message (str "!fixcolors")})))


(defmethod task-handler ::delete-corrupt-rapid
  [{:keys [update-rapid-task spring-root]}]
  (log/info "Attempting to delete corrupt rapid dir after error, then updating rapid again")
  (fs/delete-rapid-dir spring-root)
  (task/add-task! *state update-rapid-task))

(defmethod task-handler ::delete-corrupt-map-file
  [{:keys [indexed-map spring-root]}]
  (let [{:keys [file map-name]} indexed-map]
    (log/info "Attempting to delete corrupt map" map-name "at" file)
    (raynes-fs/delete file)
    (fs/update-file-cache! *state file)
    (task/add-task! *state
      {::task-type ::refresh-maps
       :spring-root spring-root})))

(defmethod task-handler ::delete-corrupt-mod-file
  [{:keys [indexed-mod spring-root]}]
  (let [{:keys [file mod-name]} indexed-mod]
    (log/info "Attempting to delete corrupt mod" mod-name "at" file)
    (raynes-fs/delete file)
    (fs/update-file-cache! *state file)
    (task/add-task! *state
      {::task-type ::refresh-mods
       :spring-root spring-root})))


(defmethod task-handler ::update-rapid
  [{:keys [engine-version mod-name rapid-id rapid-repo spring-isolation-dir] :as task}]
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
                              first))
        rapid-repo (or rapid-repo
                       (resource/mod-repo-name mod-name))
        rapid-id (or rapid-id
                     (str rapid-repo ":test"))]
    (if (and engine-details (:file engine-details))
      (do
        (log/info "Initializing rapid by calling download")
        (deref
          (event-handler
            {:event/type ::rapid-download
             :rapid-id rapid-id
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
                                      (:force task)))))
                              (mapcat
                                (fn [f]
                                  (try
                                    (rapid/rapid-versions f)
                                    (catch Exception e
                                      (log/error e "Error reading rapid versions in" f
                                                 "scheduling delete of rapid folder and another rapid update")
                                      (task/add-task! *state
                                        {::task-type ::delete-corrupt-rapid
                                         :spring-root spring-isolation-dir
                                         :update-rapid-task task})))))
                              (filter :version)
                              (sort-by :version version/version-compare)
                              reverse)
          _ (log/info "Found" (count rapid-versions) "rapid versions")
          rapid-data-by-hash (->> rapid-versions
                                  (remove (comp #(string/ends-with? % ":test") :id))
                                  ; prevents duplicates, uses specific version
                                  (map (juxt :hash identity))
                                  (into {}))
          rapid-data-by-id (->> rapid-versions
                                (map (juxt :id identity))
                                (into {}))
          rapid-data-by-version (->> rapid-versions
                                     (remove (comp #(string/ends-with? % ":test") :id))
                                     ; prevents duplicates, uses specific version
                                     (map (juxt :version identity))
                                     (into {}))]
      (swap! *state
        (fn [state]
          (-> state
              (update-in [:rapid-by-spring-root (fs/canonical-path spring-isolation-dir)]
                (fn [rapid]
                  (-> rapid
                      (assoc :rapid-updated (u/curr-millis))
                      (assoc :rapid-repos rapid-repos)
                      (update :rapid-data-by-hash merge rapid-data-by-hash)
                      (update :rapid-data-by-id merge rapid-data-by-id)
                      (update :rapid-data-by-version merge rapid-data-by-version)
                      (update :rapid-versions (fn [old-versions]
                                                (set
                                                  (vals
                                                    (merge
                                                      (into {}
                                                        (map (juxt :id identity) old-versions))
                                                      (into {}
                                                        (map (juxt :id identity) rapid-versions))))))))))
              (update :file-cache merge new-files))))
      (log/info "Updated rapid repo data in" (- (u/curr-millis) before) "ms"))
    (update-cooldown *state [:update-rapid (fs/canonical-path spring-isolation-dir)])
    (task/add-tasks! *state
      [
       (merge
         {::task-type ::update-rapid-packages
          :spring-isolation-dir spring-isolation-dir})
       {::task-type ::refresh-mods}])))

(defmethod task-handler ::update-rapid-packages
  [{:keys [spring-isolation-dir]}]
  (swap! *state assoc :rapid-update true)
  (let [{:keys [rapid-by-spring-root] :as state} @*state
        spring-isolation-dir (or spring-isolation-dir (:spring-isolation-dir state))
        spring-root-path (fs/canonical-path spring-isolation-dir)
        {:keys [rapid-data-by-hash]} (get rapid-by-spring-root spring-root-path)
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
    (swap! *state update-in [:rapid-by-spring-root spring-root-path]
           assoc
           :sdp-files sdp-files
           :rapid-packages packages
           :rapid-update false)
    (task/add-task! *state
      {::task-type ::refresh-mods
       :spring-root spring-isolation-dir})))


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
  [{:keys [engine-file rapid-id spring-isolation-dir] :as task}]
  (swap! *state
    (fn [state]
      (-> state
          (assoc-in [:rapid-download rapid-id] {:running true
                                                :message "Preparing to run pr-downloader"})
          (assoc-in [:rapid-failures rapid-id] false))))
  (future
    (try
      (let [^File root spring-isolation-dir
            pr-downloader-file (fs/pr-downloader-file engine-file)
            _ (fs/set-executable pr-downloader-file)
            command [(fs/canonical-path pr-downloader-file)
                     "--filesystem-writepath" (fs/wslpath root)
                     "--rapid-download" rapid-id]
            runtime (Runtime/getRuntime)
            detected-sdp-atom (atom nil)]
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
                    (when-let [[_all sdp] (re-find #" for ([0-9a-f]+)$" line)]
                      (reset! detected-sdp-atom sdp))
                    (recur))
                  (log/info "pr-downloader" rapid-id "stderr stream closed")))))
          (let [exit-code (.waitFor process)]
            (log/info "pr-downloader exited with code" exit-code)
            (when (not= 0 exit-code)
              (if-let [sdp @detected-sdp-atom]
                (let [sdp-file (rapid/sdp-file spring-isolation-dir (rapid/sdp-filename sdp))]
                  (log/info "Non-zero pr-downloader exit, deleting corrupt sdp file" sdp-file)
                  (raynes-fs/delete sdp-file))
                (log/info "No sdp file detected"))
              (log/info "Non-zero pr-downloader exit, deleting corrupt packages dir")
              (task/add-task! *state
                {::task-type ::delete-corrupt-rapid
                 :spring-root spring-isolation-dir
                 :update-rapid-task task})))))
      (catch Exception e
        (log/error e "Error downloading" rapid-id)
        (swap! *state assoc-in [:rapid-download rapid-id :message] (.getMessage e))
        (log/info "Scheduling delete of corrupt rapid dir in" spring-isolation-dir)
        (task/add-task! *state
          {::task-type ::delete-corrupt-rapid
           :spring-root spring-isolation-dir
           :update-rapid-task task}))
      (finally
        (task/add-tasks! *state
          [{::task-type ::update-rapid-packages
            :spring-isolation-dir spring-isolation-dir}
           {::task-type ::refresh-mods
            :spring-root spring-isolation-dir}])
        (swap! *state assoc-in [:rapid-download rapid-id :running] false)
        (update-cooldown *state [:rapid rapid-id])
        (apply fs/update-file-cache! *state (rapid/sdp-files spring-isolation-dir))))))

(defmethod task-handler ::rapid-download
  [task]
  @(event-handler (assoc task :event/type ::rapid-download)))


(defn- download-http-resource [{:keys [dest downloadable spring-isolation-dir]}]
  (log/info "Request to download" downloadable)
  (future
    (try
      (let [url (:download-url downloadable)
            dest (or dest (resource/resource-dest spring-isolation-dir downloadable))
            temp-dest (fs/download-file (str (hash (str url)) "-" (fs/filename dest)))]
        (log/info "Downloading to temp file" temp-dest "then moving to" dest)
        (http/download-file *state url temp-dest)
        (log/info "Moving temp download file" temp-dest "into place at" dest)
        (fs/move temp-dest dest))
      (case (:resource-type downloadable)
        ::map (refresh-maps *state spring-isolation-dir {:priorities [dest]})
        ::mod (refresh-mods *state spring-isolation-dir {:priorities [dest]})
        ::engine (refresh-engines *state spring-isolation-dir {:priorities [dest]})
        nil)
      (catch Exception e
        (log/error e "Error downloading")))))

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
    (task/add-task! *state {::task-type ::refresh-replays})))

(defn search-springfiles
  "Search springfiles.com for the given resource name, returns a string mirror url for the resource,
  or nil if not found."
  [{:keys [category springname]}]
  (log/info "Searching springfiles for" springname)
  (let [result (->> (clj-http/get "https://springfiles.springrts.com/json.php"
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
  [{:keys [download-if-found springname] :or {download-if-found true} :as e}]
  (if-not (string/blank? springname)
    (let [search-result (search-springfiles e)]
      (log/info "Found details for" springname "on springfiles" search-result)
      (swap! *state assoc-in [:springfiles-search-results springname] search-result)
      (when (and search-result download-if-found)
        (task/add-task! *state
          (assoc e
                 ::task-type ::download-springfiles
                 :search-result search-result)))
      search-result)
    (log/warn "No springname to search springfiles" e)))

(defmethod task-handler ::download-springfiles
  [{:keys [resource-type search-result springname spring-isolation-dir url]}]
  (if-let [{:keys [filename] :as search-result} (or search-result
                                                    (task-handler {::task-type ::search-springfiles
                                                                   :springname springname}))]
    (let [url (or url
                  (springfiles-url search-result))]
      (task/add-task! *state
        {::task-type ::http-downloadable
         :downloadable {:download-url url
                        :resource-filename filename
                        :resource-type resource-type}
         :springname springname
         :spring-isolation-dir spring-isolation-dir}))
    (log/info "No mirror to download" springname "on springfiles")))


(defmethod event-handler ::extract-7z
  [{:keys [file dest]}]
  (let [path (fs/canonical-path file)]
    (swap! *state assoc-in [:extracting path] true)
    (fs/update-file-cache! *state file dest)
    (future
      (try
        (if dest
          (fs/extract-7z-fast file dest)
          (fs/extract-7z-fast file))
        (refresh-engines-all-spring-roots)
        (catch Exception e
          (log/error e "Error extracting 7z" file))
        (finally
          (fs/update-file-cache! *state file dest)
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
                                        (into {}))
              all-download-source-names (set (keys download-sources-by-name))]
          (log/info "Found downloadables from" download-source-name "at" url
                    (frequencies (map :resource-type downloadables)))
          (swap! *state update :downloadables-by-url
                 (fn [old]
                   (let [invalid-download-source (remove (comp all-download-source-names :download-source-name second) old)]
                     (when (seq invalid-download-source)
                       (log/warn "Deleted" (count invalid-download-source) "downloads from invalid sources"))
                     (merge
                       (->> old
                            (remove (comp #{download-source-name} :download-source-name second))
                            (filter (comp all-download-source-names :download-source-name second))
                            (into {}))
                       downloadables-by-url))))
          (update-cooldown *state [:download-source download-source-name])
          downloadables-by-url))
      (log/info "Too soon to check downloads from" url))))

(defmethod task-handler ::update-downloadables
  [source]
  (update-download-source source))


(defmethod event-handler ::clear-map-and-mod-details
  [{:keys [map-resource mod-resource spring-root]}]
  (let [map-key (resource/details-cache-key map-resource)
        mod-key (resource/details-cache-key mod-resource)
        {:keys [use-git-mod-version]}
        (swap! *state
          (fn [state]
            (cond-> state
                    map-key
                    (update :map-details cache/miss map-key nil)
                    mod-key
                    (update :mod-details cache/miss mod-key nil))))]
    (task/add-tasks! *state
      (concat
        [{::task-type ::refresh-engines
          :force true
          :spring-root spring-root}
         {::task-type ::refresh-mods
          :spring-root spring-root
          :priorities [(:file mod-resource)]}
         {::task-type ::refresh-maps
          :spring-root spring-root
          :priorities [(:file map-resource)]}]
        (when-let [mod-file (:file mod-resource)]
          [{::task-type ::mod-details
            :mod-name (:mod-name mod-resource)
            :mod-file mod-file
            :use-git-mod-version use-git-mod-version}])
        (when-let [map-file (:file map-resource)]
          [{::task-type ::map-details
            :map-name (:map-name map-resource)
            :map-file map-file}])))))


(defmethod event-handler ::import-source-change
  [{:fx/keys [event]}]
  (swap! *state assoc :import-source-name (:import-source-name event)))


(defmethod event-handler ::assoc
  [{:fx/keys [event] :keys [value] :or {value (if (instance? Event event) true event)} :as e}]
  (swap! *state assoc (:key e) value))

(defmethod event-handler ::assoc-in
  [{:fx/keys [event] :keys [path value] :or {value (if (instance? Event event) true event)}}]
  (swap! *state assoc-in path value))

(defmethod event-handler ::dissoc
  [e]
  (if-let [ks (:keys e)]
    (do
      (log/info "Dissoc" ks)
      (swap! *state
        (fn [state]
          (apply dissoc state ks))))
    (do
      (log/info "Dissoc" (:key e))
      (swap! *state dissoc (:key e)))))

(defn dissoc-in [m path]
  (if (empty? path)
    m
    (if (= 1 (count path))
      (dissoc m (first path))
      (update-in m (drop-last path) dissoc (last path)))))

(defmethod event-handler ::dissoc-in
  [{:keys [path]}]
  (log/info "Dissoc" path)
  (swap! *state dissoc-in path))

(defmethod event-handler ::enable-auto-scroll-if-at-bottom
  [{:fx/keys [^ScrollEvent event]}]
  (when (neg? (.getDeltaY event))
    (log/info "Scrolled to bottom of chat, enabling auto-scroll")
    (swap! *state assoc :chat-auto-scroll true)))

(defmethod event-handler ::disable-auto-scroll
  [_e]
  (log/info "Scrolled up in chat, enabling auto-scroll")
  (swap! *state assoc :chat-auto-scroll false))


(defmethod event-handler ::scan-imports
  [{:keys [sources] :or {sources (fx.import/import-sources (:extra-import-sources @*state))}}]
  (doseq [import-source sources]
    (task/add-task! *state
      (merge
        {::task-type ::scan-imports}
        import-source))))

(defmethod task-handler ::scan-all-imports [task]
  (event-handler (assoc task :event/type ::scan-imports)))


(defmethod event-handler ::download-source-change
  [{:fx/keys [event]}]
  (swap! *state assoc :download-source-name (:download-source-name event)))


(defmethod event-handler ::update-all-downloadables
  [opts]
  (doseq [download-source fx.download/download-sources]
    (task/add-task! *state
      (merge
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
        (swap! *state
          (fn [state]
            (-> state
                (assoc-in [:replays-watched (fs/canonical-path replay-file)] true)
                (assoc-in [:spring-running :replay :replay] true))))
        (spring/watch-replay
          *state
          {:engine-version engine-version
           :engines engines
           :replay-file replay-file
           :spring-isolation-dir spring-isolation-dir}))
      (catch Exception e
        (log/error e "Error watching replay" replay))
      (finally
        (swap! *state assoc-in [:spring-running :replay :replay] false)))))


(defmethod event-handler ::select-replay
  [{:fx/keys [event]}]
  (let [{:keys [replay-id file]} event]
    (swap! *state
      (fn [state]
        (cond-> (assoc state :selected-replay-file file)
                replay-id
                (assoc :selected-replay-id replay-id))))))


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
                                 :spectator 1}])))
        r
        (-> replay
            (assoc :source-name "BAR Online")
            (assoc :body {:script-data {:game (into {} (concat (:hostSettings replay) players teams spectators))}})
            (assoc :header {:unix-time (quot (inst-ms (java-time/instant (:startTime replay))) 1000)})
            (assoc :player-counts player-counts)
            (assoc :game-type (u/game-type player-counts)))]
    (merge
      r
      {:replay-map-name (-> r :Map :scriptName)})))

(defmethod task-handler ::download-bar-replays [{:keys [page]}]
  (let [new-bar-replays (->> (http/get-bar-replays {:page page})
                             (map process-bar-replay))]
    (log/info "Got" (count new-bar-replays) "BAR replays from page" page)
    (let [[before after] (swap-vals! *state
                           (fn [state]
                             (-> state
                                 (assoc :bar-replays-page ((fnil inc 0) ((fnil int 0) (u/to-number page))))
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


(def
  ^KeyCodeCombination
  quit-win-keys
  (KeyCodeCombination. KeyCode/Q (into-array [KeyCombination/CONTROL_DOWN])))
(def
  ^KeyCodeCombination
  quit-mac-keys
  (KeyCodeCombination. KeyCode/Q (into-array [KeyCombination/SHORTCUT_DOWN])))

(def
  ^KeyCodeCombination
  close-tab-win-keys
  (KeyCodeCombination. KeyCode/W (into-array [KeyCombination/SHORTCUT_DOWN])))
(def
  ^KeyCodeCombination
  close-tab-mac-keys
  (KeyCodeCombination. KeyCode/W (into-array [KeyCombination/CONTROL_DOWN])))

(defmethod event-handler ::main-window-key-pressed
  [{:fx/keys [^KeyEvent event] :as e}]
  (if (or (.match quit-win-keys event)
          (.match quit-mac-keys event))
    (event-handler (assoc e :event/type ::main-window-on-close-request))
    (when (or (.match close-tab-win-keys event)
              (.match close-tab-mac-keys event))
      (log/info "Closing current tab")
      (let [{:keys [by-server selected-server-tab selected-tab-channel selected-tab-main]} @*state
            {:keys [battle client-data] :as server-data} (get by-server selected-server-tab)]
        (if server-data
          (let [tab-in-server (get selected-tab-main selected-server-tab)]
            (cond
              (and (= "battle" tab-in-server)
                   battle)
              (do
                (log/info "Closing battle tab")
                (event-handler (assoc e
                                      :event/type ::leave-battle
                                      :client-data client-data
                                      :server-key selected-server-tab)))
              (= "chat" tab-in-server)
              (let [channel-in-battle (get selected-tab-channel selected-server-tab)]
                (log/info "Closing chat tab")
                (event-handler (assoc e
                                      :event/type ::leave-channel
                                      :channel-name channel-in-battle
                                      :client-data client-data)))
              :else
              (do
                (log/info "Closing server tab for" selected-server-tab)
                (event-handler (assoc e
                                      :event/type ::disconnect
                                      :server-key selected-server-tab)))))
          (if-let [k (get tab-to-window-key selected-server-tab)]
            (do
              (log/info "Closing tab" (pr-str selected-server-tab) "by setting" k)
              (swap! *state assoc k false))
            (log/warn "Unknown tab to close")))))))

(defmethod event-handler ::main-window-on-close-request [{:keys [standalone] :as e}]
  (log/debug "Main window close request" e)
  (if standalone
    (do
      (Platform/exit)
      (System/exit 0))
    (log/info "Ignoring main window close since in dev mode")))

(defmethod event-handler ::my-channels-tab-action [e]
  (log/info e))

(def default-history-index -1)

(defmethod event-handler ::send-message [{:keys [channel-name client-data message no-clear-draft no-history server-key] :as e}]
  (let [server-key (or server-key (u/server-key client-data))
        {:keys [by-server]} (swap! *state
                              (fn [state]
                                (cond-> (update-in state [:by-server server-key]
                                          (fn [server-data]
                                            (cond-> server-data
                                                    (not no-history)
                                                    (update-in [:channels channel-name :sent-messages] conj message)
                                                    (not no-history)
                                                    (assoc-in [:channels channel-name :history-index] default-history-index))))
                                        (not no-clear-draft)
                                        (update-in [:message-drafts server-key] dissoc channel-name))))
        client-data (or (get-in by-server [server-key :client-data])
                        client-data)]
    (future
      (try
        (cond
          (string/blank? channel-name)
          (log/info "Skipping message" (pr-str message) "to empty channel" (pr-str channel-name))
          (string/blank? message)
          (log/info "Skipping empty message" (pr-str message) "to" (pr-str channel-name))
          :else
          (cond
            (re-find #"^/ingame" message) (message/send-message *state client-data "GETUSERINFO")
            (re-find #"^/ignore" message)
            (let [[_all username] (re-find #"^/ignore\s+([^\s]+)\s*" message)]
              (set-ignore server-key username true {:channel-name channel-name}))
            (re-find #"^/unignore" message)
            (let [[_all username] (re-find #"^/unignore\s+([^\s]+)\s*" message)]
              (set-ignore server-key username false {:channel-name channel-name}))
            (or (re-find #"^/msg" message) (re-find #"^/message" message))
            (let [[_all user message] (re-find #"^/msg\s+([^\s]+)\s+(.+)" message)]
              @(event-handler
                 (merge e
                   {:event/type ::send-message
                    :channel-name (str "@" user)
                    :message message
                    :no-clear-draft true})))
            (re-find #"^/rename" message)
            (let [[_all new-username] (re-find #"^/rename\s+([^\s]+)" message)]
             (swap! *state update-in [:by-server server-key :channels channel-name :messages] conj {:text (str "Renaming to" new-username)
                                                                                                    :timestamp (u/curr-millis)
                                                                                                    :message-type :info}
              (message/send-message *state client-data (str "RENAMEACCOUNT " new-username))))
            :else
            (let [[private-message username] (re-find #"^@(.*)$" channel-name)
                  unified (-> client-data :compflags (contains? "u"))]
              (if-let [[_all message] (re-find #"^/me (.*)$" message)]
                (if private-message
                  (message/send-message *state client-data (str "SAYPRIVATEEX " username " " message))
                  (if (and (not unified) (u/battle-channel-name? channel-name))
                    (message/send-message *state client-data (str "SAYBATTLEEX " message))
                    (message/send-message *state client-data (str "SAYEX " channel-name " " message))))
                (if private-message
                  (message/send-message *state client-data (str "SAYPRIVATE " username " " message))
                  (if (and (not unified) (u/battle-channel-name? channel-name))
                    (message/send-message *state client-data (str "SAYBATTLE " message))
                    (message/send-message *state client-data (str "SAY " channel-name " " message))))))))
        (catch Exception e
          (log/error e "Error sending message" message "to channel" channel-name))))))


(defmethod event-handler ::promote-discord [{:keys [data discord-channel discord-promoted server-key]}]
  (let [now (u/curr-millis)]
    (if (or (not discord-promoted)
            (< (- now discord-promoted) discord/cooldown))
      (do
        (swap! *state
          (fn [state]
            (let [channel-name (u/visible-channel state server-key)]
              (-> state
                  (assoc-in [:discord-promoted discord-channel] now)
                  (update-in [:by-server server-key :channels channel-name :messages] conj {:text "Promoted to Discord"
                                                                                            :timestamp now
                                                                                            :message-type :info})))))
        (future
          (try
            (discord/promote-battle discord-channel data)
            (catch Exception e
              (log/error e "Error promoting battle in Discord to" discord-channel)))))
      (log/info "Too soon to promote to discord" discord-channel))))


(defmethod event-handler ::on-channel-key-pressed [{:fx/keys [^KeyEvent event] :keys [channel-name server-key]}]
  (let [code (.getCode event)]
    (when-let [dir (cond
                     (= KeyCode/UP code) inc
                     (= KeyCode/DOWN code) dec
                     :else nil)]
      (try
        (swap! *state
          (fn [state]
            (let [{:keys [history-index sent-messages]} (get-in state [:by-server server-key :channels channel-name])
                  new-history-index (max default-history-index
                                         (min (dec (count sent-messages))
                                              (dir (or history-index default-history-index))))
                  history-message (nth sent-messages new-history-index "")]
              (-> state
                  (assoc-in [:by-server server-key :channels channel-name :history-index] new-history-index)
                  (assoc-in [:message-drafts server-key channel-name] history-message)))))
        (catch Exception e
          (log/error e "Error setting chat history message"))))))

(defmethod event-handler ::on-console-key-pressed [{:fx/keys [^KeyEvent event] :keys [server-key]}]
  (let [code (.getCode event)]
    (when-let [dir (cond
                     (= KeyCode/UP code) inc
                     (= KeyCode/DOWN code) dec
                     :else nil)]
      (try
        (swap! *state update-in [:by-server server-key]
          (fn [server-data]
            (let [{:keys [console-history-index console-sent-messages]} server-data
                  new-history-index (max default-history-index
                                         (min (dec (count console-sent-messages))
                                              (dir (or console-history-index default-history-index))))
                  history-message (nth console-sent-messages new-history-index "")]
              (-> server-data
                  (assoc :console-message-draft history-message)
                  (assoc :console-history-index new-history-index)))))
        (catch Exception e
          (log/error e "Error setting console history message"))))))


(defn dissoc-if-empty [m path]
  (let [without (dissoc-in m path)
        new-path (drop-last path)]
    (if (empty? (get-in without new-path))
      (if (seq new-path)
        (dissoc-if-empty without new-path)
        {})
      without)))

(defn update-needs-focus [server-tab main-tab channel-tab needs-focus]
  (dissoc-if-empty needs-focus [server-tab main-tab (if (= "battle" main-tab) :battle channel-tab)]))

(defmethod event-handler ::selected-item-changed-channel-tabs
  [{:fx/keys [^Tab event] :keys [server-key]}]
  (let [tab (.getId event)]
    (swap! *state
      (fn [state]
        (-> state
            (assoc-in [:selected-tab-channel server-key] tab)
            (update :needs-focus (partial update-needs-focus server-key "chat" tab)))))))

(defmethod event-handler ::selected-item-changed-main-tabs
  [{:fx/keys [^Tab event] :keys [selected-tab-channel server-key]}]
  (let [tab (.getId event)]
    (swap! *state
      (fn [state]
        (-> state
            (assoc-in [:selected-tab-main server-key] tab)
            (update :needs-focus (partial update-needs-focus server-key tab selected-tab-channel)))))))

(defmethod event-handler ::send-console [{:keys [client-data message server-key]}]
  (future
    (try
      (swap! *state update-in [:by-server server-key]
        (fn [server-data]
          (-> server-data
              (assoc :console-message-draft "")
              (update :console-sent-messages conj message)
              (assoc :console-history-index default-history-index))))
      (when-not (string/blank? message)
        (message/send-message *state client-data message))
      (catch Exception e
        (log/error e "Error sending message" message "to server")))))


(defmethod event-handler ::filter-channel-scroll [{:fx/keys [^Event event]}]
  (when (= ScrollEvent/SCROLL (.getEventType event))
    (let [^ScrollEvent scroll-event event
          ^Parent source (.getSource scroll-event)
          needs-auto-scroll (when (and source (instance? VirtualizedScrollPane source))
                              (let [[_ _ ^ScrollBar ybar] (vec (.getChildrenUnmodifiable source))]
                                (< (- (.getMax ybar) (- (.getValue ybar) (.getDeltaY scroll-event))) 80)))]
      (swap! *state assoc :chat-auto-scroll needs-auto-scroll))))

(defmethod event-handler ::filter-console-scroll [{:fx/keys [^Event event]}]
  (when (= ScrollEvent/SCROLL (.getEventType event))
    (let [^ScrollEvent scroll-event event
          ^Parent source (.getSource scroll-event)
          needs-auto-scroll (when (and source (instance? VirtualizedScrollPane source))
                              (let [[_ _ ^ScrollBar ybar] (vec (.getChildrenUnmodifiable source))]
                                (< (- (.getMax ybar) (- (.getValue ybar) (.getDeltaY scroll-event))) 80)))]
      (swap! *state assoc :console-auto-scroll needs-auto-scroll))))


(defmethod event-handler ::selected-item-changed-server-tabs
  [{:fx/keys [^Tab event] :keys [selected-tab-main selected-tab-channel]}]
  (let [tab (.getId event)]
    (swap! *state
      (fn [state]
        (-> state
            (assoc :selected-server-tab tab)
            (update :needs-focus (partial update-needs-focus tab (get selected-tab-main tab) (get selected-tab-channel tab))))))))


(defmethod event-handler ::matchmaking-list-all [{:keys [client-data]}]
  (message/send-message *state client-data "c.matchmaking.list_all_queues"))

(defmethod event-handler ::matchmaking-list-my [{:keys [client-data]}]
  (message/send-message *state client-data "c.matchmaking.list_my_queues"))

(defmethod event-handler ::matchmaking-leave-all [{:keys [client-data]}]
  (message/send-message *state client-data "c.matchmaking.leave_all_queues")
  (swap! *state update-in [:by-server (u/server-key client-data) :matchmaking-queues]
    (fn [matchmaking-queues]
      (into {}
        (map
          (fn [[k v]]
            [k (assoc v :am-in false)])
          matchmaking-queues)))))

(defmethod event-handler ::matchmaking-join [{:keys [client-data queue-id]}]
  (message/send-message *state client-data (str "c.matchmaking.join_queue " queue-id)))

(defmethod event-handler ::matchmaking-leave [{:keys [client-data queue-id]}]
  (message/send-message *state client-data (str "c.matchmaking.leave_queue " queue-id))
  (message/send-message *state client-data (str "c.matchmaking.get_queue_info\t" queue-id))
  (swap! *state assoc-in [:by-server (u/server-key client-data) :matchmaking-queues queue-id :am-in] false))

(defmethod event-handler ::matchmaking-ready [{:keys [client-data queue-id]}]
  (message/send-message *state client-data (str "c.matchmaking.ready"))
  (swap! *state assoc-in [:by-server (u/server-key client-data) :matchmaking-queues queue-id :ready-check] false))

(defmethod event-handler ::matchmaking-decline [{:keys [client-data queue-id]}]
  (message/send-message *state client-data (str "c.matchmaking.decline"))
  (swap! *state assoc-in [:by-server (u/server-key client-data) :matchmaking-queues queue-id :ready-check] false))


(def state-watch-chimers
  [
   [:auto-get-resources-watcher auto-get-resources-watcher 2]
   [:auto-get-replay-resources-watcher auto-get-replay-resources-watcher 2]
   [:battle-map-details-watcher battle-map-details-watcher 2]
   [:battle-mod-details-watcher battle-mod-details-watcher 2]
   [:fix-spring-isolation-dir-watcher fix-spring-isolation-dir-watcher 10]
   [:replay-map-and-mod-details-watcher replay-map-and-mod-details-watcher]
   [:spring-isolation-dir-changed-watcher spring-isolation-dir-changed-watcher 10]
   [:update-battle-status-sync-watcher update-battle-status-sync-watcher 2]])


(defn stop-ipc-server
  [state-atom]
  (let [{:keys [ipc-server]} @state-atom]
    (when (fn? ipc-server)
      (log/info "Stopping IPC server")
      (ipc-server)
      (swap! state-atom dissoc :ipc-server))))

(defmethod task-handler ::stop-ipc-server [_]
  (stop-ipc-server *state))

(defn start-ipc-server
  "Starts an HTTP server so that replays and battles can be loaded into running instance."
  ([state-atom]
   (start-ipc-server state-atom nil))
  ([state-atom opts]
   (let [{:keys [ipc-server-port ipc-server-enabled]} @state-atom
         port (or ipc-server-port u/default-ipc-port)]
     (stop-ipc-server state-atom)
     (cond
       (and (not ipc-server-enabled)
            (not (:force opts)))
       (log/info "IPC server disabled")
       (not (u/is-port-open? port))
       (log/warn "IPC port unavailable" port)
       :else
       (try
         (log/info "Starting IPC server on port" port)
         (let [handler (server/handler *state)
               server (http-kit/run-server
                        (-> handler
                            wrap-keyword-params
                            wrap-params)
                        {:port port
                         :ip "127.0.0.1"})]
           (swap! *state assoc :ipc-server server))
         (catch Exception e
           (log/error e "Error starting IPC server")
           (swap! *state assoc :ipc-server-error e)))))))


(defmethod task-handler ::start-ipc-server [_]
  (start-ipc-server *state {:force true}))


(defn init
  "Things to do on program init, or in dev after a recompile."
  ([state-atom]
   (init state-atom nil))
  ([state-atom {:keys [skip-tasks]}]
   (try
     (let [custom-css-file (fs/file (fs/app-root) "custom-css.edn")]
       (when-not (fs/exists? custom-css-file)
         (log/info "Creating initial custom CSS file" custom-css-file)
         (spit custom-css-file (with-out-str (pprint skylobby.fx/default-style-data)))))
     (let [custom-css-file (fs/file (fs/app-root) "custom.css")]
       (when-not (fs/exists? custom-css-file)
         (log/info "Creating initial custom CSS file" custom-css-file)
         (spit custom-css-file (slurp (::css/url skylobby.fx/default-style)))))
     (catch Exception e
       (log/error e "Error creating custom CSS file")))
   (try
     (log/info "Adding event handler methods from other ns")
     (fx.event.battle/add-methods event-handler state-atom)
     (fx.event.chat/add-methods event-handler state-atom)
     (fx.event.direct/add-methods event-handler state-atom)
     (fx.event.minimap/add-methods event-handler state-atom)
     (catch Exception e
       (log/error e "Error adding event handler methods")))
   (log/info "Initializing periodic jobs")
   (let [task-chimers (->> task/task-kinds
                           (map (partial tasks-chimer-fn state-atom))
                           doall)
         state-chimers (->> state-watch-chimers
                            (map (fn [[k watcher-fn duration]]
                                   (state-change-chimer-fn state-atom k watcher-fn duration)))
                            doall)
         check-app-update-chimer (check-app-update-chimer-fn state-atom)
         profile-print-chimer (profile-print-chimer-fn state-atom)
         spit-app-config-chimer (spit-app-config-chimer-fn state-atom)
         fix-battle-ready-chimer (fix-battle-ready-chimer-fn state-atom)
         update-matchmaking-chimer (update-matchmaking-chimer-fn state-atom)
         update-music-queue-chimer (update-music-queue-chimer-fn state-atom)
         update-now-chimer (update-now-chimer-fn state-atom)
         ;resend-no-response-messages-chimer (resend-no-response-messages-chimer-fn state-atom)
         update-replays-chimer (update-replays-chimer-fn state-atom)
         update-window-and-divider-positions-chimer (update-window-and-divider-positions-chimer-fn state-atom)
         write-chat-logs-chimer (write-chat-logs-chimer-fn state-atom)]
     (add-watchers state-atom)
     (if-not skip-tasks
       (future
         (.addShutdownHook
           (Runtime/getRuntime)
           (Thread.
             (fn []
               (spit-state-config-to-edn nil @state-atom))))
         (try
           (async/<!! (async/timeout wait-init-tasks-ms))
           (async/<!! (async/timeout wait-init-tasks-ms))
           (task/add-task! state-atom {::task-type ::refresh-engines})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (task/add-task! state-atom {::task-type ::refresh-mods})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (task/add-task! state-atom {::task-type ::refresh-maps})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (task/add-task! state-atom {::task-type ::refresh-replays})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (event-handler {:event/type ::update-all-downloadables})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (event-handler {:event/type ::scan-imports})
           (catch Exception e
             (log/error e "Error adding initial tasks"))))
       (log/info "Skipped initial tasks"))
     (log/info "Finished periodic jobs init")
     (start-ipc-server state-atom)
     {:chimers
      (concat
        task-chimers
        state-chimers
        [
         check-app-update-chimer
         profile-print-chimer
         spit-app-config-chimer
         fix-battle-ready-chimer
         update-matchmaking-chimer
         update-music-queue-chimer
         update-now-chimer
         ;resend-no-response-messages-chimer
         update-replays-chimer
         update-window-and-divider-positions-chimer
         write-chat-logs-chimer])})))


(defn standalone-replay-init [state-atom]
  (let [task-chimers (->> task/task-kinds
                          (map (partial tasks-chimer-fn state-atom))
                          doall)
        state-chimers (->> state-watch-chimers
                           (map (fn [[k watcher-fn]]
                                  (state-change-chimer-fn state-atom k watcher-fn)))
                           doall)]
    (add-watchers state-atom)
    (task/add-task! state-atom {::task-type ::refresh-engines})
    (task/add-task! state-atom {::task-type ::refresh-mods})
    (task/add-task! state-atom {::task-type ::refresh-maps})
    (event-handler {:event/type ::update-all-downloadables})
    (event-handler {:event/type ::scan-imports})
    (log/info "Finished standalone replay init")
    {:chimers
     (concat
       task-chimers
       state-chimers)}))


(defn auto-connect-servers [state-atom]
  (let [{:keys [by-server logins servers]} @state-atom]
    (doseq [[server-url :as server] (filter (comp :auto-connect second) servers)]
      (when-let [{:keys [password username] :as login} (get logins server-url)]
        (when (and password username)
          (let [server-key (u/server-key {:server-url server-url
                                          :username username})]
            (if (contains? by-server server-key)
              (log/warn "Already connected to" server-key)
              (do
                @(event-handler
                   (merge
                     {:event/type ::connect
                      :no-focus true
                      :server server
                      :server-key server-key}
                     login))
                (async/<!! (async/timeout 500))))))))))


(defmethod task-handler ::auto-connect-servers [_]
  (auto-connect-servers *state))

(defmethod event-handler ::auto-connect-servers [_]
  (future
    (try
      (auto-connect-servers *state)
      (catch Exception e
        (log/error e "Error connecting to auto servers")))))

(defmethod event-handler ::check-public-ip [_]
  (swap! *state dissoc :direct-connect-public-ip)
  (future
    (try
      (let [result (clj-http/get "https://icanhazip.com/")
            ip (string/trim (str (:body result)))]
        (swap! *state assoc :direct-connect-public-ip ip))
      (catch Exception e
        (log/error e "Error getting public IP from icanhazip, falling back on api.ipify.org")
        (try
          (let [result (clj-http/get "http://api.ipify.org/")
                ip (string/trim (str (:body result)))]
            (swap! *state assoc :direct-connect-public-ip ip))
          (catch Exception e
            (log/error e "Error getting public IP from api.ipify.org")))))))


(defn init-async [state-atom]
  (future
    (try
      (init state-atom)
      (catch Exception e
        (log/error e "Error in init async")))))
