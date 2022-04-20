(ns spring-lobby.replays
  (:require
    [cljfx.api :as fx]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.replay :as fx.replay]
    [skylobby.fx.settings :as fx.settings]
    [skylobby.util :as u]
    spring-lobby
    [taoensso.timbre :as log])
  (:import
    (javafx.application Platform)
    (javafx.event Event))
  (:gen-class))


(set! *warn-on-reflection* true)


(def screen-bounds (skylobby.fx/get-screen-bounds))


(defn replays-on-close-request
  [^Event e]
  (log/debug "Replays window close request" e)
  (System/exit 0)
  (.consume e))

(defn root-view
  [_]
  {:fx/type fx/ext-many
   :desc
   [
    {:fx/type fx.replay/replays-window
     :on-close-request replays-on-close-request
     :screen-bounds screen-bounds
     :settings-button true
     :title (str "skyreplays " u/app-version)}
    {:fx/type fx.settings/settings-window
     :screen-bounds screen-bounds}]})


(defn create-renderer []
  (log/info "Creating renderer")
  (let [r (fx/create-renderer
            :middleware (comp
                          fx/wrap-context-desc
                          (fx/wrap-map-desc (fn [_] {:fx/type root-view})))
            :opts {:fx.opt/map-event-handler spring-lobby/event-handler
                   :fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %)
                                                (fx/fn->lifecycle-with-context %))})]
    (log/info "Mounting renderer")
    (fx/mount-renderer spring-lobby/*ui-state r)))

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
      (spring-lobby/add-ui-state-watcher spring-lobby/*state spring-lobby/*ui-state)
      (create-renderer))
    (spring-lobby/init-async spring-lobby/*state)
    (log/info "Main finished in" (- (u/curr-millis) before) "ms")))
