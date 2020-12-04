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


(pjstadig.humane-test-output/activate!)


; application state, copy this to spring-lobby nses on refresh

(def *state (atom nil))

; prevent duplicate refreshes

(def refreshing (atom false))


; renderer var, create in init

(def ^:dynamic renderer nil)


(defn rerender []
  (try
    (println "Requiring spring-lobby ns")
    (require 'spring-lobby)
    (alter-var-root (find-var 'spring-lobby/*state) (constantly *state))
    (let [watch-fn (var-get (find-var 'spring-lobby/add-watchers))]
      (watch-fn *state))
    (if renderer
      (do
        (println "Re-rendering")
        (renderer))
      (println "No renderer"))
    (catch Exception e
      (println e))))

(defn refresh-rerender []
  (println "Refreshing")
  (future
    (try
      (binding [*ns* *ns*]
        (println (refresh :after 'user/rerender)))
      (catch Exception e
        (println e))
      (finally
        (reset! refreshing false)))))

(defn refresh-on-file-change [context event]
  (when-let [file (:file event)]
    (let [f (io/file file)]
      (when (and (.exists f) (not (.isDirectory f)))
        (if @refreshing
          (println "Duplicate file event, skipping refresh")
          (try
            (reset! refreshing true)
            (refresh-rerender)
            (catch Exception e
              (println e)))))))
  context)


(defn view [state]
  (require 'spring-lobby)
  (let [actual-view (var-get (find-var 'spring-lobby/root-view))]
    (actual-view state)))

(defn event-handler [e]
  (require 'spring-lobby)
  (let [actual-handler (var-get (find-var 'spring-lobby/event-handler))]
    (actual-handler e)))


(defn init []
  (try
    [datafy pprint]
    (hawk/watch! [{:paths ["src/clj"]
                   :handler refresh-on-file-change}])
    (require 'spring-lobby)
    (alter-var-root #'*state (constantly (var-get (find-var 'spring-lobby/*state))))
    ; just use spring-lobby/*state for initial state, on refresh copy user/*state var back
    (let [watch-fn (var-get (find-var 'spring-lobby/add-watchers))
          r (fx/create-renderer
              :middleware (fx/wrap-map-desc
                            (fn [state]
                              {:fx/type view
                               :state state}))
              :opts {:fx.opt/map-event-handler event-handler})]
      (watch-fn *state)
      (alter-var-root #'renderer (constantly r)))
    (fx/mount-renderer *state renderer)
    (catch Exception e
      (println e))))
