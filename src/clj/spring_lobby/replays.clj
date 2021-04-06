(ns spring-lobby.replays
  (:require
    [cljfx.api :as fx]
    [spring-lobby]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (javafx.application Platform))
  (:gen-class))


(defn initial-state []
  (merge
    {}
    (apply
      merge
      (doall
        (map (comp spring-lobby/slurp-config-edn :filename) spring-lobby/state-to-edn)))
    (spring-lobby/slurp-config-edn "parsed-replays.edn")
    {:tasks (spring-lobby/initial-tasks)
     :show-replays true}))

(defn replays-on-close-request
  [e]
  (log/debug "Replays window close request" e)
  (System/exit 0)
  (.consume e))

(defn root-view
  [{{:keys [standalone] :as state} :state}]
  {:fx/type fx/ext-many
   :desc
   [
    (merge
      {:fx/type spring-lobby/replays-window
       :on-close-request (when standalone replays-on-close-request)
       :settings-button true
       :title (str "skyreplays " (u/app-version))}
      (select-keys state spring-lobby/replays-window-keys))
    (merge
      {:fx/type spring-lobby/settings-window}
      (select-keys state spring-lobby/settings-window-keys))]})



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
      (reset! spring-lobby/*state state))
    (log/info "Creating renderer")
    (let [r (fx/create-renderer
              :middleware (fx/wrap-map-desc
                            (fn [state]
                              {:fx/type root-view
                               :state state}))
              :opts {:fx.opt/map-event-handler spring-lobby/event-handler})]
      (log/info "Mounting renderer")
      (fx/mount-renderer spring-lobby/*state r))
    (log/info "Initializing periodic jobs, async")
    (future (spring-lobby/init spring-lobby/*state))
    (log/info "Main finished in" (- (u/curr-millis) before) "ms")))
