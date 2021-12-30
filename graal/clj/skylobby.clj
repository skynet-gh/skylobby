(ns skylobby
  (:require
    [chime.core :as chime]
    [clojure.core.async :as async]
    java-time
    [org.httpkit.server :as http-kit]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.params :refer [wrap-params]]
    [skylobby.server-stub :as server]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (java.time Duration)))


(set! *warn-on-reflection* true)


(def ^:dynamic *state (atom {}))


(defmulti event-handler :event/type)
(defmulti task-handler ::task-type)

(def ^:dynamic handle-task task-handler) ; for overriding in dev


(defn add-task-handlers []
  (defmethod task-handler :default [task]
    (when task
      (log/warn "Unknown task type" task))))


(defn remove-task [task tasks]
  (disj (set tasks) task))

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
             (java-time/plus (java-time/instant) (Duration/ofMillis 10000))
             (Duration/ofMillis 1000))
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

(defn add-task! [state-atom task]
  (if task
    (let [task-kind (task/task-kind task)]
      (log/info "Adding task" (pr-str task) "to" task-kind)
      (swap! state-atom update-in [:tasks-by-kind task-kind]
        (fn [tasks]
          (set (conj tasks task)))))
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
  (when-let [{:keys [ipc-server]} @*state]
    (when (fn? ipc-server)
      (ipc-server)))
  (if (u/is-port-open? u/ipc-port)
    (do
      (log/info "Starting IPC server on port" u/ipc-port)
      (let [handler (server/handler *state)
            server (http-kit/run-server
                     (-> handler
                         wrap-keyword-params
                         wrap-params)
                     {:port u/ipc-port})]
        (swap! *state assoc :ipc-server server)))
    (log/warn "IPC port unavailable" u/ipc-port)))


(def state-watch-chimers
  [])
   ;[:auto-get-resources-watcher auto-get-resources-watcher 2000]
   ;[:battle-map-details-watcher battle-map-details-watcher 2000]
   ;[:battle-mod-details-watcher battle-mod-details-watcher 2000]
   ;[:fix-spring-isolation-dir-watcher fix-spring-isolation-dir-watcher 10000]
   ;[:replay-map-and-mod-details-watcher replay-map-and-mod-details-watcher]
   ;[:spring-isolation-dir-changed-watcher spring-isolation-dir-changed-watcher 10000]
   ;[:update-battle-status-sync-watcher update-battle-status-sync-watcher 2000]])

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
           (event-handler {:event/type ::update-all-downloadables})
           (async/<!! (async/timeout wait-init-tasks-ms))
           (event-handler {:event/type ::scan-imports})
           (catch Exception e
             (log/error e "Error adding initial tasks"))))
       (log/info "Skipped initial tasks"))
     (log/info "Finished periodic jobs init")
     (start-ipc-server)
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
