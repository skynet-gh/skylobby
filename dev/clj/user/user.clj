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


(def ^:dynamic state nil)
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
        state-atom (var-get (find-var 'spring-lobby/*state))]
    (alter-var-root #'state (constantly state-atom))
    (let [watch-fn (var-get (find-var 'spring-lobby/add-watchers))]
      (watch-fn state-atom))
    (fx/mount-renderer state-atom r)
    (alter-var-root #'renderer (constantly r))))

(defn unmount []
  (when renderer
    (try
      (fx/unmount-renderer (var-get (find-var 'spring-lobby/*state)) renderer)
      (catch Exception e
        (println "Error unmounting: " (.getMessage e))))))


(defn load-and-mount []
  (try
    (require 'spring-lobby)
    (alter-var-root (find-var 'spring-lobby/*state) (constantly state))
    (when-let [client-deferred (:client-deferred @state)]
      (let [connected-loop-fn (var-get (find-var 'spring-lobby/connected-loop))]
        (connected-loop-fn state client-deferred)))
    (mount)
    (catch Exception e
      (println e))
    (finally
      (reset! refreshing false))))

(def old-refresh refresh)

(defn unmount-store-refresh-load-mount []
  (try
    (let [old-state @state]
      (when-let [f (:connected-loop old-state)]
        (future-cancel f))
      (when-let [f (:print-loop old-state)]
        (future-cancel f))
      (when-let [f (:ping-loop old-state)]
        (future-cancel f)))
    (unmount)
    (finally
      (future
        (binding [*ns* *ns*]
          (println (old-refresh :after 'user/load-and-mount))
          (reset! refreshing false))))))

; replace for editor integration
(alter-var-root #'clojure.tools.namespace.repl/refresh (constantly unmount-store-refresh-load-mount))


(defn refresh-on-file-change [context event]
  (when-let [file (:file event)]
    (let [f (io/file file)]
      (when (and (.exists f) (not (.isDirectory f)))
        (if @refreshing
          (println "Already refreshing, duplicate file event")
          (try
            (reset! refreshing true)
            (unmount-store-refresh-load-mount)
            (catch Exception e
              (println e)))))))
  context)


(hawk/watch! [{:paths ["src/clj"]
               :handler refresh-on-file-change}])


; doesn't work
;(refresh :after 'user/mount)

;(require 'spring-lobby)
(mount)
