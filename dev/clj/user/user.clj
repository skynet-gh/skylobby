(ns user
  (:require
    [cemerick.pomegranate :as pomegranate]
    [cemerick.pomegranate.aether :refer [maven-central]]
    [chime.core :as chime]
    [clj-http.client :as http]
    [cljfx.api :as fx]
    [cljfx.css :as css]
    [clojure.datafy :refer [datafy]]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [clojure.tools.namespace.repl :refer [disable-unload! disable-reload! refresh]]
    [hawk.core :as hawk]
    io.aviso.repl
    java-time
    [pjstadig.humane-test-output]))


(disable-unload!)
(disable-reload!)


(pjstadig.humane-test-output/activate!)
(io.aviso.repl/install-pretty-exceptions)


; application state, copy this to spring-lobby nses on refresh

(def *state (atom nil))
(def *ui-state (atom nil))

; state from init to clean up (chimers)

(def init-state (atom nil))

; prevent duplicate refreshes

(def refreshing (atom false))

; renderer var, create in init

(def ^:dynamic renderer nil)


(def ^:dynamic old-view nil)
(def ^:dynamic old-handler nil)
(def ^:dynamic old-task-handler nil)
(def ^:dynamic old-client-handler nil)


(defn stop-chimer [chimer]
  (when chimer
    (try
      ; (println "Stopping chimer" chimer) ; noisy
      (chimer)
      (catch Throwable e
        (println "error stopping chimer" chimer e)))))

(defn view [state]
  (try
    (require 'skylobby.fx.root)
    (require 'spring-lobby)
    (if-let [v (find-var 'skylobby.fx.root/root-view)]
      (if-let [new-view (var-get v)]
        (when new-view
          (alter-var-root #'old-view (constantly new-view)))
        (println "no new view found"))
      (println "unable to find var"))
    (catch Throwable e
      (println e)
      (.printStackTrace e)
      (println "compile error, using old view")))
  (try
    (if old-view
      (old-view state)
      (println "no old view"))
    (catch Throwable e
      (println e)
      (.printStackTrace e)
      (println "exception in old view")
      (throw e))))

(defn event-handler [event]
  (try
    (require 'spring-lobby)
    (let [new-handler (var-get (find-var 'spring-lobby/event-handler))]
      (when new-handler
        (alter-var-root #'old-handler (constantly new-handler))))
    (catch Throwable e
      (println e "compile error, using old event handler")))
  (try
    (old-handler event)
    (catch Throwable e
      (println "exception in old event handler" e)
      (throw e))))

(defn create-renderer []
  (let [r (fx/create-renderer
            :middleware (comp
                          fx/wrap-context-desc
                          (fx/wrap-map-desc (fn [_] {:fx/type view})))
            :opts {:fx.opt/map-event-handler event-handler
                   :fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %)
                                                (fx/fn->lifecycle-with-context %))}
            :error-handler (fn [t]
                             (println "Error occurred! Unmounting renderer for safety")
                             (println t)
                             (.printStackTrace t)
                             (fx/unmount-renderer *ui-state renderer)
                             (alter-var-root #'renderer (constantly nil))))]
    (alter-var-root #'renderer (constantly r)))
  (fx/mount-renderer *ui-state renderer))

(defn client-handler [state-atom server-url message]
  (try
    (require 'spring-lobby.client)
    (require 'spring-lobby.client.handler)
    (let [new-handler (var-get (find-var 'spring-lobby.client.handler/handle))]
      (when new-handler
        (alter-var-root #'old-client-handler (constantly new-handler))))
    (catch Throwable e
      (println e "compile error, using old client handler")))
  (try
    (old-client-handler state-atom server-url message)
    (catch Throwable e
      (println e "exception in old client, probably unbound fn, fix asap")
      (throw e))))

(defn rerender []
  (try
    (println "Stopping old chimers")
    (let [{:keys [chimers]} @init-state]
      (doseq [chimer chimers]
        (stop-chimer chimer)))
    (println "Requiring spring-lobby ns")
    (require 'spring-lobby)
    (let [new-state-var (find-var 'spring-lobby/*state)
          new-state-atom (var-get new-state-var)]
      (remove-watch new-state-atom :ui-state) ; to prevent leak
      (alter-var-root new-state-var (constantly *state)))
    (alter-var-root (find-var 'spring-lobby/*ui-state) (constantly *ui-state))
    (require 'spring-lobby.client)
    (alter-var-root (find-var 'spring-lobby.client/handler) (constantly client-handler))
    (if renderer
      (do
        (println "Re-rendering")
        (try
          (renderer)
          (catch Throwable e
            (println "error rendering" e)
            (throw e))))
      (do
        (println "No renderer, creating new one")
        (create-renderer)))
    (try
      (let [init-fn (var-get (find-var 'spring-lobby/init))]
        (reset! init-state (init-fn *state {:skip-tasks true})))
      (catch Throwable e
        (println "init error" e)
        (throw e)))
    (catch Throwable e
      (println e)
      (throw e))))

(defn refresh-rerender []
  (println "Refreshing")
  (future
    (try
      (binding [*ns* *ns*]
        (let [res (refresh :after 'user/rerender)]
          (if (instance? Throwable res)
            (io.aviso.repl/pretty-pst res)
            (println "Refresh finished"))))
      (catch Throwable e
        (println e)
        (throw e))
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
            (catch Throwable e
              (println e)
              (throw e)))))))
  context)


(defn task-handler [task]
  (try
    (require 'spring-lobby)
    (let [new-handler (var-get (find-var 'spring-lobby/task-handler))]
      (when new-handler
        (alter-var-root #'old-task-handler (constantly new-handler))))
    (catch Throwable e
      (println e "compile error, using old task handler")))
  (try
    (old-task-handler task)
    (catch Throwable e
      (println e "exception in old task handler, probably unbound fn, fix asap")
      (throw e))))


(defn init []
  (try
    [datafy pprint chime/chime-at string/split edn/read-string http/get]
    (hawk/watch! [{:paths ["src/clj" "test/clj"]
                   :handler refresh-on-file-change}])
    (require 'skylobby.fx.root)
    (require 'spring-lobby)
    (require 'spring-lobby.fs)
    (require 'skylobby.fx)
    (let [init-7z-fn (var-get (find-var 'spring-lobby.fs/init-7z!))]
      (future
        (try
          (println "Initializing 7zip")
          (init-7z-fn)
          (println "Finished initializing 7zip")
          (catch Throwable e
            (println e)))))
    (alter-var-root #'*state (constantly (var-get (find-var 'spring-lobby/*state))))
    (alter-var-root #'*ui-state (constantly (var-get (find-var 'spring-lobby/*ui-state))))
    (let [initial-state-fn (var-get (find-var 'spring-lobby/initial-state))
          initial-state (initial-state-fn)]
      (reset! *state initial-state)
      (swap! *state assoc :css (css/register :skylobby.fx/current
                                 (or (:css initial-state)
                                     (var-get (find-var 'skylobby.fx/default-style-data))))))
    ; just use spring-lobby/*state for initial state, on refresh copy user/*state var back
    (alter-var-root #'old-view (constantly (var-get (find-var 'skylobby.fx.root/root-view))))
    (alter-var-root #'old-handler (constantly (var-get (find-var 'spring-lobby/event-handler))))
    (require 'spring-lobby.client)
    (require 'spring-lobby.client.handler)
    (alter-var-root #'old-client-handler (constantly (var-get (find-var 'spring-lobby.client/handler))))
    (alter-var-root (find-var 'spring-lobby.client/handler) (constantly client-handler))
    (require 'spring-lobby)
    (alter-var-root #'old-task-handler (constantly (var-get (find-var 'spring-lobby/handle-task))))
    (alter-var-root (find-var 'spring-lobby/handle-task) (constantly task-handler))
    (let [init-fn (var-get (find-var 'spring-lobby/init))]
      (reset! init-state (init-fn *state))
      (create-renderer))
    (catch Throwable e
      (println e)
      (.printStackTrace e)
      (throw e))))


(defn add-dependencies [coordinates]
  (pomegranate/add-dependencies
    :coordinates coordinates
    :repositories (merge maven-central
                         {"clojars" "https://clojars.org/repo"})))
