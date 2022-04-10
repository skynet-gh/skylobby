(ns skylobby.core
  (:require
    [chime.core :as chime]
    [clojure.core.async :as async]
    [clojure.core.cache :as cache]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as string]
    java-time
    [org.httpkit.server :as http-kit]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.params :refer [wrap-params]]
    [skylobby.auto-resources :as auto-resources]
    [skylobby.battle-sync :as battle-sync]
    [skylobby.cli.util :as cu]
    [skylobby.fs :as fs]
    [skylobby.server :as server]
    [skylobby.sql :as sql]
    [skylobby.task :as task]
    [skylobby.task.handler :as task-handlers]
    [skylobby.util :as u]
    [skylobby.watch :as watch]
    [taoensso.nippy :as nippy]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (java.io File)
    (java.net URL)
    (java.time Duration)))


(set! *warn-on-reflection* true)


(def ^:dynamic *state (atom {}))


; https://github.com/ptaoussanis/nippy#custom-types-v21
(defn register-nippy []
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
    (URL. (.readUTF data-input))))


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
            (let [data (->> config-file slurp (edn/read-string {:readers u/custom-readers}))]
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


(def config-keys
  [:auto-get-resources
   :auto-get-replay-resources
   :auto-launch
   :auto-rejoin-battle
   :auto-refresh-replays
   :battle-as-tab
   :battle-layout
   :battle-password
   :battle-players-color-type
   :battle-players-display-type
   :battle-port
   :battle-resource-details
   :battle-title
   :battles-layout
   :battles-table-images
   :bot-name
   :bot-version
   :chat-auto-complete
   :chat-auto-scroll
   :chat-color-username
   :chat-font-size
   :chat-highlight-username
   :chat-highlight-words
   :client-id-override
   :client-id-type
   :console-auto-scroll
   :console-ignore-message-types
   :css
   :debug-spring
   :direct-connect-chat-commands
   :direct-connect-ip
   :direct-connect-password
   :direct-connect-port
   :direct-connect-protocol
   :direct-connect-username
   :disable-tasks
   :disable-tasks-while-in-game
   :divider-positions
   :engine-overrides
   :extra-import-sources
   :extra-replay-sources
   :filter-replay
   :filter-replay-type
   :filter-replay-max-players
   :filter-replay-min-players
   :filter-users
   :focus-chat-on-message
   :friend-users
   :hide-barmanager-messages
   :hide-empty-battles
   :hide-joinas-spec
   :hide-locked-battles
   :hide-passworded-battles
   :hide-spads-messages
   :hide-vote-messages
   :highlight-tabs-with-new-battle-messages
   :highlight-tabs-with-new-chat-messages
   :ignore-users
   :increment-ids
   :interleave-ally-player-ids
   :ipc-server-enabled
   :ipc-server-port
   :join-battle-as-player
   :leave-battle-on-close-window
   :logins
   :minimap-size
   :music-dir
   :music-stopped
   :music-volume
   :mute
   :mute-ring
   :my-channels
   :password
   :players-table-columns
   :pop-out-battle
   :preferred-color
   :preferred-factions
   :prevent-non-host-rings
   :rapid-repo
   :rapid-spring-root
   :ready-on-unspec
   :refresh-replays-after-game
   :replay-source-enabled
   :replays-window-dedupe
   :replays-window-details
   :ring-on-auto-unspec
   :ring-sound-file
   :ring-volume
   :scenarios-engine-version
   :scenarios-spring-root
   :server
   :servers
   :show-accolades
   :show-battle-preview
   :show-closed-battles
   :show-hidden-modoptions
   :show-spring-picker
   :show-team-skills
   :show-vote-log
   :spring-isolation-dir
   :spring-settings
   :uikeys
   :unready-after-game
   :use-default-ring-sound
   :use-git-mod-version
   :user-agent-override
   :username
   :windows-as-tabs
   :window-states])


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
    [:invalid-replay-paths :online-bar-replays :replays-tags :replays-watched]))


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
   #_ ; disable rapid data load, use sql now
   {:select-fn select-rapid
    :filename "rapid.edn"
    :nippy true}
   {:select-fn select-replays
    :filename "replays.edn"}])


(defn initial-state []
  (register-nippy)
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
     :servers u/default-servers
     :show-battle-preview true
     :show-spring-picker true
     :spring-isolation-dir (fs/default-spring-root)
     :use-default-ring-sound true
     :windows-as-tabs true}
    (apply
      merge
      (doall
        (map slurp-config-edn state-to-edn)))
    {:tasks-by-kind {}
     :current-tasks (->> task/task-kinds (map (juxt identity (constantly nil))) (into {}))
     ;:minimap-type (first fx.battle/minimap-types)
     ;:replay-minimap-type (first fx.battle/minimap-types)
     :map-details (cache/lru-cache-factory (sorted-map) :threshold 8)
     :mod-details (cache/lru-cache-factory (sorted-map) :threshold 8)
     :replay-details (cache/lru-cache-factory (sorted-map) :threshold 4)
     :chat-auto-scroll true
     :console-auto-scroll true
     ;:use-db-for-downloadables false
     ;:use-db-for-importables false
     :use-db-for-rapid true
     :use-db-for-replays true}))


(defmulti task-handler :spring-lobby/task-type)

(def ^:dynamic handle-task task-handler) ; for overriding in dev


(defn add-task-handlers []
  (defmethod task-handler :default [task]
    (when task
      (log/warn "Unknown task type" task)))
  (task-handlers/add-handlers task-handler *state))


(defn remove-task [task tasks]
  (disj (set tasks) task))

(defn pop-task-fn [task-kind]
  (fn [{:keys [tasks-by-kind] :as state}]
    (if (empty? (get tasks-by-kind task-kind))
      state
      (let [task (-> tasks-by-kind (get task-kind) shuffle first)]
        (-> state
            (update-in [:tasks-by-kind task-kind] (partial remove-task task))
            (assoc-in [:current-tasks task-kind] task))))))

(defn finish-task-fn [task-kind]
  (fn [state]
    (-> state
        (assoc-in [:current-tasks task-kind] nil)
        (update :task-threads dissoc task-kind))))

(defn handle-task!
  ([state-atom task-kind]
   (when (first (get-in @state-atom [:tasks-by-kind task-kind])) ; short circuit if no task of this kind
     (let [after (swap! state-atom (pop-task-fn task-kind))
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
             (swap! state-atom (finish-task-fn task-kind)))))
       task))))

(defn- my-client-status [{:keys [username users]}]
  (-> users (get username) :client-status))

(defn- tasks-chimer-handler [state-atom task-kind]
  (fn [_chimestamp]
    (let [{:keys [by-server disable-tasks disable-tasks-while-in-game]} @state-atom
          in-any-game (some (comp :ingame my-client-status second) by-server)]
      (if (or disable-tasks (and disable-tasks-while-in-game in-any-game))
        (log/debug "Skipping task handler while in game")
        (handle-task! state-atom task-kind)))))

(defn- tasks-chimer-fn
  ([state-atom task-kind]
   (log/info "Starting tasks chimer for" task-kind)
   (let [chimer
         (chime/chime-at
           (chime/periodic-seq
             (java-time/plus (java-time/instant) (Duration/ofMillis 10000))
             (Duration/ofMillis 1000))
           (tasks-chimer-handler state-atom task-kind)
           {:error-handler
            (fn [e]
              (log/error e "Error handling task of kind" task-kind)
              true)})]
     (fn [] (.close chimer)))))

(defn- add-task [tasks task]
  (set (conj tasks task)))

(defn add-task! [state-atom task]
  (if task
    (let [task-kind (task/task-kind task)]
      (log/info "Adding task" (pr-str task) "to" task-kind)
      (swap! state-atom update-in [:tasks-by-kind task-kind] add-task task))
    (log/warn "Attempt to add nil task" task)))


(defn- state-change-chimer-fn
  "Creates a chimer that runs a state watcher fn periodically."
  ([state-atom k watcher-fn]
   (state-change-chimer-fn state-atom k watcher-fn 3000))
  ([state-atom k watcher-fn duration]
   (let [old-state-atom (atom @state-atom)
         chimer
         (chime/chime-at
           (chime/periodic-seq
             (java-time/plus (java-time/instant) (Duration/ofMillis 15000))
             (Duration/ofMillis (or duration 3000)))
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
            (java-time/plus (java-time/instant) (Duration/ofMillis 60000))
            (Duration/ofMillis 60000))
          (fn [_chimestamp]
            (if-let [m (not-empty @stats-accumulator)]
              (log/info (str "Profiler stats:\n" (tufte/format-grouped-pstats m)))
              (log/warn "No profiler stats to print")))
          {:error-handler
           (fn [e]
             (log/error e "Error in profiler print")
             true)})]
    (fn [] (.close chimer))))


(defn- add-watchers
  "Adds all *state watchers."
  [state-atom]
  (remove-watch state-atom :fix-missing-resource)
  (remove-watch state-atom :filter-replays)
  (remove-watch state-atom :fix-selected-replay)
  (remove-watch state-atom :fix-selected-server))
  ;(add-watch state-atom :fix-missing-resource fix-missing-resource-watcher)
  ;(add-watch state-atom :filter-replays filter-replays-watcher)
  ;(add-watch state-atom :fix-selected-replay fix-selected-replay-watcher)
  ;(add-watch state-atom :fix-selected-server fix-selected-server-watcher))


(defn start-ipc-server
  "Starts an HTTP server so that replays and battles can be loaded into running instance."
  []
  (let [port u/default-ipc-port]
    (when-let [{:keys [ipc-server]} @*state]
      (when (fn? ipc-server)
        (ipc-server)))
    (if (u/is-port-open? port)
      (do
        (log/info "Starting IPC server on port" port)
        (let [handler (server/handler *state)
              server (http-kit/run-server
                       (-> handler
                           wrap-keyword-params
                           wrap-params)
                       {:port port
                        :ip "127.0.0.1"})]
          (swap! *state assoc :ipc-server server)))
      (do
        (log/warn "IPC port unavailable" port)
        (cu/print-and-exit -1 (str "Server port unavailable: " port))))))


(def state-watch-chimers
  [
   [:auto-get-resources-watcher auto-resources/auto-get-resources-watcher 2000]
   [:battle-map-details-watcher watch/battle-map-details-watcher 2000]
   [:battle-mod-details-watcher watch/battle-mod-details-watcher 2000]
   ;[:fix-spring-isolation-dir-watcher fix-spring-isolation-dir-watcher 10000]
   [:replay-map-and-mod-details-watcher watch/replay-map-and-mod-details-watcher]
   ;[:spring-isolation-dir-changed-watcher spring-isolation-dir-changed-watcher 10000]
   [:update-battle-status-sync-watcher battle-sync/update-battle-status-sync-watcher 2000]])

(def wait-init-tasks-ms 20000)

(defn init
  "Things to do on program init, or in dev after a recompile."
  ([state-atom]
   (init state-atom nil))
  ([state-atom {:keys [skip-tasks]}]
   (log/info "Initializing periodic jobs")
   (add-task-handlers)
   (let [task-chimers (->> task/task-kinds
                           (map (partial tasks-chimer-fn state-atom))
                           doall)
         state-chimers (->> state-watch-chimers
                            (map (fn [[k watcher-fn duration]]
                                   (state-change-chimer-fn state-atom k watcher-fn duration)))
                            doall)
         ;check-app-update-chimer (check-app-update-chimer-fn state-atom)
         profile-print-chimer (profile-print-chimer-fn state-atom)]
         ;spit-app-config-chimer (spit-app-config-chimer-fn state-atom)
         ;fix-battle-ready-chimer (fix-battle-ready-chimer-fn state-atom)
         ;update-matchmaking-chimer (update-matchmaking-chimer-fn state-atom)
         ;update-music-queue-chimer (update-music-queue-chimer-fn state-atom)
         ;update-now-chimer (update-now-chimer-fn state-atom)
         ;update-replays-chimer (update-replays-chimer-fn state-atom)
         ;update-window-and-divider-positions-chimer (update-window-and-divider-positions-chimer-fn state-atom)
         ;write-chat-logs-chimer (write-chat-logs-chimer-fn state-atom)]
     (add-watchers state-atom)
     (if-not skip-tasks
       (future
         (try
           (async/<!! (async/timeout wait-init-tasks-ms))
           (async/<!! (async/timeout wait-init-tasks-ms))
           (add-task! state-atom {::task-type ::refresh-engines})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (add-task! state-atom {::task-type ::refresh-mods})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (add-task! state-atom {::task-type ::refresh-maps})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (add-task! state-atom {::task-type ::refresh-replays})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (add-task! state-atom {::task-type ::update-all-downloadables})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (add-task! state-atom {::task-type ::scan-all-imports})
           (catch Exception e
             (log/error e "Error adding initial tasks"))))
       (log/info "Skipped initial tasks"))
     (log/info "Finished periodic jobs init")
     (start-ipc-server)
     (sql/init-db state-atom {:force true})
     {:chimers
      (concat
        task-chimers
        state-chimers
        [
         ;check-app-update-chimer
         profile-print-chimer])})))
         ;spit-app-config-chimer
         ;fix-battle-ready-chimer
         ;update-matchmaking-chimer
         ;update-music-queue-chimer
         ;update-now-chimer
         ;update-replays-chimer
         ;update-window-and-divider-positions-chimer
         ;write-chat-logs-chimer])})))
