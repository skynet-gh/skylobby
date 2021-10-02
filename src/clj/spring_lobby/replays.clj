(ns spring-lobby.replays
  (:require
    [cljfx.api :as fx]
    [skylobby.fx.replay :as fx.replay]
    [skylobby.fx.settings :as fx.settings]
    spring-lobby
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (javafx.application Platform)
    (javafx.event Event))
  (:gen-class))


(set! *warn-on-reflection* true)


(def app-version
  (u/app-version))


(defn replays-on-close-request
  [^Event e]
  (log/debug "Replays window close request" e)
  (System/exit 0)
  (.consume e))

(defn root-view
  [{{:keys [current-tasks standalone tasks-by-kind] :as state} :state}]
  (let [all-tasks (->> tasks-by-kind
                       (mapcat second)
                       (concat (vals current-tasks))
                       (filter some?))
        tasks-by-type (group-by :spring-lobby/task-type all-tasks)]
    {:fx/type fx/ext-many
     :desc
     [
      (merge
        {:fx/type fx.replay/replays-window
         :on-close-request (when standalone replays-on-close-request)
         :settings-button true
         :tasks-by-type tasks-by-type
         :title (str "skyreplays " app-version)}
        (select-keys state fx.replay/replays-window-keys))
      (merge
        {:fx/type fx.settings/settings-window}
        (select-keys state fx.settings/settings-window-keys))]}))

(defn create-renderer []
  (log/info "Creating renderer")
  (let [r (fx/create-renderer
            :middleware (fx/wrap-map-desc
                          (fn [state]
                            {:fx/type root-view
                             :state state}))
            :opts {:fx.opt/map-event-handler spring-lobby/event-handler})]
    (log/info "Mounting renderer")
    (fx/mount-renderer spring-lobby/*state r)))

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
          state (assoc (spring-lobby/initial-state) :show-replays true :standalone true)]
      (log/info "Loaded initial state in" (- (u/curr-millis) before-state) "ms")
      (reset! spring-lobby/*state state)
      (create-renderer))
    (spring-lobby/init-async spring-lobby/*state)
    (log/info "Main finished in" (- (u/curr-millis) before) "ms")))
