(ns user
  (:require
    [cemerick.pomegranate :as pomegranate]
    [cemerick.pomegranate.aether :refer [maven-central]]
    [chime.core :as chime]
    [cljfx.api :as fx]
    [clojure.datafy :refer [datafy]]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [clojure.tools.namespace.repl :refer [disable-unload! disable-reload! refresh]]
    [hawk.core :as hawk]
    java-time
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
(def ^:dynamic hawk nil)
(def ^:dynamic tasks-chimer nil)
(def ^:dynamic file-events-chimer nil)


(defn rerender []
  (try
    (println "Requiring spring-lobby ns")
    (require 'spring-lobby)
    (alter-var-root (find-var 'spring-lobby/*state) (constantly *state))
    (future
      (try
        (let [watch-fn (var-get (find-var 'spring-lobby/add-watchers))]
          (watch-fn *state))
        (catch Exception e
          (println "error adding watchers" e))))
    (alter-var-root (find-var 'spring-lobby/*state) (constantly *state))
    #_
    (future
      (when hawk
        (try
          (hawk/stop! hawk)
          (catch Exception e
            (println "error stopping hawk" e))))
      (try
        (let [hawk-fn (var-get (find-var 'spring-lobby/add-hawk))]
          (alter-var-root #'hawk (fn [& _] (hawk-fn *state))))
        (catch Exception e
          (println "error starting hawk" e))))
    (future
      (when tasks-chimer
        (try
          (tasks-chimer)
          (catch Exception e
            (println "error stopping tasks" e))))
      (try
        (let [chimer-fn (var-get (find-var 'spring-lobby/tasks-chimer-fn))]
          (alter-var-root #'tasks-chimer (fn [& _] (chimer-fn *state))))
        (catch Exception e
          (println "error starting tasks" e))))
    (future
      (when file-events-chimer
        (try
          (file-events-chimer)
          (catch Exception e
            (println "error stopping file events" e))))
      (try
        (let [chimer-fn (var-get (find-var 'spring-lobby/file-events-chimer-fn))]
          (alter-var-root #'file-events-chimer (fn [& _] (chimer-fn *state))))
        (catch Exception e
          (println "error starting file events" e))))
    (if renderer
      (do
        (println "Re-rendering")
        (try
          (renderer)
          (catch Exception e
            (println "error rendering" e))))
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
          ;hawk-fn (var-get (find-var 'spring-lobby/add-hawk))
          tasks-chimer-fn (var-get (find-var 'spring-lobby/tasks-chimer-fn))
          file-events-chimer-fn (var-get (find-var 'spring-lobby/file-events-chimer-fn))
          r (fx/create-renderer
              :middleware (fx/wrap-map-desc
                            (fn [state]
                              {:fx/type view
                               :state state}))
              :opts {:fx.opt/map-event-handler event-handler})]
      (watch-fn *state)
      ;(alter-var-root #'hawk (fn [& _] (hawk-fn *state)))
      (alter-var-root #'tasks-chimer (fn [& _] (tasks-chimer-fn *state)))
      (alter-var-root #'file-events-chimer(fn [& _] (file-events-chimer-fn *state)))
      (alter-var-root #'renderer (constantly r)))
    (fx/mount-renderer *state renderer)
    (catch Exception e
      (println e))))


(defn add-dependencies [coordinates]
  (pomegranate/add-dependencies
    :coordinates coordinates
    :repositories (merge maven-central
                         {"clojars" "https://clojars.org/repo"})))
