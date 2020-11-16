(ns user
  (:require
    [cljfx.api :as fx]
    [clojure.datafy :refer [datafy]]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.tools.namespace.repl :refer [disable-unload! disable-reload! refresh]]
    [hawk.core :as hawk]
    [pjstadig.humane-test-output]))


(disable-unload!)
(disable-reload!)


(def state (atom nil))
(def ^:dynamic renderer nil)
(def refreshing (atom false))


(pjstadig.humane-test-output/activate!)


; https://github.com/cljfx/cljfx/blob/master/examples/e27_selection_models.clj#L20


; store things through ns refreshes


(defn mount []
  (require 'spring-lobby)
  (let [view (var-get (find-var 'spring-lobby/root-view))
        event-handler (var-get (find-var 'spring-lobby/event-handler))
        r (fx/create-renderer
            :middleware (fx/wrap-map-desc
                          (fn [state]
                            {:fx/type view
                             :state state}))
            :opts {:fx.opt/map-event-handler event-handler})
        state (var-get (find-var 'spring-lobby/*state))]
    (fx/mount-renderer state r)
    (alter-var-root #'renderer (constantly r))))

(defn unmount []
  (println "looking to unmount")
  (when renderer
    (println "unmounting")
    (fx/unmount-renderer (var-get (find-var 'spring-lobby/*state)) renderer)))


(defn load-and-mount []
  (try
    (println "loading")
    (let [saved-state @state
          state-atom (var-get (find-var 'spring-lobby/*state))]
      (reset! state-atom saved-state)
      (when-let [client-deferred (:client-deferred saved-state)]
        (require 'spring-lobby)
        (let [connected-loop-fn (var-get (find-var 'spring-lobby/connected-loop))]
          (connected-loop-fn state-atom client-deferred))))
    (mount)
    (catch Exception e
      (println e))
    (finally
      (reset! refreshing false))))

(def old-refresh refresh)

(defn unmount-store-refresh-load-mount []
  (require 'spring-lobby)
  (when (try (var-get (find-var 'spring-lobby/*state))
             (catch Exception e
               (println e "Error refreshing")))
    (println "storing")
    (let [old-state @(var-get (find-var 'spring-lobby/*state))]
      (reset! state old-state)
      (when-let [f (:connected-loop old-state)]
        (future-cancel f))
      (when-let [f (:print-loop old-state)]
        (future-cancel f))
      (when-let [f (:print-loop old-state)]
        (future-cancel f)))
    (unmount)
    (println "refreshing")
    (future
      (try
        (binding [*ns* *ns*]
          (let [res (old-refresh :after 'user/load-and-mount)]
            (println res)))
        (catch Exception e
          (println e))
        (finally
          (reset! refreshing false))))))

; replace for editor integration
(alter-var-root #'clojure.tools.namespace.repl/refresh (constantly unmount-store-refresh-load-mount))


(defn refresh-on-file-change [context event]
  (when-let [file (:file event)]
    (let [f (io/file file)]
      (when (and (.exists f) (not (.isDirectory f)))
        (if @refreshing
          (println "Already refreshing, duplicate file event")
          (do
            (reset! refreshing true)
            (unmount-store-refresh-load-mount))))))
  context)


(hawk/watch! [{:paths ["src/clj"]
               :handler refresh-on-file-change}])


; doesn't work
;(refresh :after 'user/mount)

;(require 'spring-lobby)
(mount)
