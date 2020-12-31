(ns user
  (:require
    [cemerick.pomegranate :as pomegranate]
    [cemerick.pomegranate.aether :refer [maven-central]]
    [chime.core :as chime]
    [clj-http.client :as http]
    [cljfx.api :as fx]
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

; state from init to clean up (chimers)

(def init-state (atom nil))

; prevent duplicate refreshes

(def refreshing (atom false))

; renderer var, create in init

(def ^:dynamic renderer nil)


(def ^:dynamic old-view nil)
(def ^:dynamic old-handler nil)
(def ^:dynamic old-client-handler nil)


(defn stop-chimer [chimer]
  (when chimer
    (try
      (println "Stopping chimer" chimer)
      (chimer)
      (catch Exception e
        (println "error stopping chimer" chimer e)))))

(defn rerender []
  (try
    (println "Stopping old chimers")
    (let [{:keys [chimers]} @init-state]
      (doseq [chimer chimers]
        (stop-chimer chimer)))
    (println "Requiring spring-lobby ns")
    (require 'spring-lobby)
    (alter-var-root (find-var 'spring-lobby/*state) (constantly *state))
    (require 'spring-lobby.client)
    (alter-var-root (find-var 'spring-lobby.client/handler) (constantly old-client-handler))
    (if renderer
      (do
        (println "Re-rendering")
        (try
          (renderer)
          (catch Exception e
            (println "error rendering" e)
            (throw e))))
      (println "No renderer"))
    (try
      (let [init-fn (var-get (find-var 'spring-lobby/init))]
        (reset! init-state (init-fn *state)))
      (catch Exception e
        (println "init error" e)))
    (catch Exception e
      (println e))))

(defn refresh-rerender []
  (println "Refreshing")
  (future
    (try
      (binding [*ns* *ns*]
        (let [res (refresh :after 'user/rerender)]
          (if (instance? Exception res)
            (io.aviso.repl/pretty-pst res)
            (println "Refresh finished:" res))))
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
  (try
    (require 'spring-lobby)
    (let [new-view (var-get (find-var 'spring-lobby/root-view))]
      (when (not (identical? old-view new-view))
        (alter-var-root #'old-view (constantly new-view))))
    (catch Exception _e
      (println "compile error, using old view")))
  (try
    (old-view state)
    (catch Exception _e
      (println "exception in old view, probably unbound fn, fix asap"))))

(defn event-handler [event]
  (try
    (require 'spring-lobby)
    (let [new-handler (var-get (find-var 'spring-lobby/event-handler))]
      (when (not (identical? old-handler new-handler))
        (alter-var-root #'old-handler (constantly new-handler))))
    (catch Exception _e
      (println "compile error, using old event handler")))
  (try
    (old-handler event)
    (catch Exception e
      (println "exception in old event handler" e))))


(defn client-handler [client state message]
  (try
    (require 'spring-lobby.client.handler)
    (let [new-handler (var-get (find-var 'spring-lobby.client.handler/handle))]
      (when (not (identical? old-client-handler new-handler))
        (alter-var-root #'old-client-handler (constantly new-handler))))
    (catch Exception _e
      (println "compile error, using old client handler")))
  (try
    (old-client-handler client state message)
    (catch Exception _e
      (println "exception in old client, probably unbound fn, fix asap"))))


(defn init []
  (try
    [datafy pprint chime/chime-at string/split edn/read-string http/get]
    (hawk/watch! [{:paths ["src/clj"]
                   :handler refresh-on-file-change}])
    (require 'spring-lobby)
    (require 'spring-lobby.fs)
    (let [init-7z-fn (var-get (find-var 'spring-lobby.fs/init-7z!))]
      (future
        (try
          (println "Initializing 7zip")
          (init-7z-fn)
          (println "Finished initializing 7zip")
          (catch Exception e
            (println e)))))
    (alter-var-root #'*state (constantly (var-get (find-var 'spring-lobby/*state))))
    (let [initial-state-fn (var-get (find-var 'spring-lobby/initial-state))]
      (reset! *state (initial-state-fn)))
    ; just use spring-lobby/*state for initial state, on refresh copy user/*state var back
    (alter-var-root #'old-view (constantly (var-get (find-var 'spring-lobby/root-view))))
    (alter-var-root #'old-handler (constantly (var-get (find-var 'spring-lobby/event-handler))))
    (require 'spring-lobby.client)
    (alter-var-root #'old-client-handler (constantly (var-get (find-var 'spring-lobby.client/handler))))
    (alter-var-root (find-var 'spring-lobby.client/handler) (constantly client-handler))
    (let [init-fn (var-get (find-var 'spring-lobby/init))
          r (fx/create-renderer
              :middleware (fx/wrap-map-desc
                            (fn [state]
                              {:fx/type view
                               :state state}))
              :opts {:fx.opt/map-event-handler event-handler})]
      (reset! init-state (init-fn *state))
      (alter-var-root #'renderer (constantly r)))
    (fx/mount-renderer *state renderer)
    (catch Exception e
      (println e)
      (throw e))))


(defn add-dependencies [coordinates]
  (pomegranate/add-dependencies
    :coordinates coordinates
    :repositories (merge maven-central
                         {"clojars" "https://clojars.org/repo"})))
