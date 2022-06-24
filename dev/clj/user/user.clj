(ns user
  (:require
    [cemerick.pomegranate :as pomegranate]
    [cemerick.pomegranate.aether :refer [maven-central]]
    [chime.core :as chime]
    [clj-http.client :as http]
    [cljfx.api :as fx]
    [cljfx.css :as css]
    [clojure.core.async :as async]
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

(def headless (atom false))

; prevent duplicate refreshes

(def refreshing (atom false))

; renderer var, create in init

(def ^:dynamic renderer nil)


(def ^:dynamic old-client-handler nil)


(defn stop-chimer [chimer]
  (when chimer
    (try
      ; (println "Stopping chimer" chimer) ; noisy
      (chimer)
      (catch Throwable e
        (println "error stopping chimer" chimer e)))))


(defn create-renderer []
  (let [_ (require 'spring-lobby)
        _ (require 'skylobby.fx.root)
        r (fx/create-renderer
            :middleware (comp
                          fx/wrap-context-desc
                          (fx/wrap-map-desc (fn [_] {:fx/type (var-get (find-var 'skylobby.fx.root/root-view))})))
            :opts {:fx.opt/map-event-handler (var-get (find-var 'spring-lobby/event-handler))
                   :fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %)
                                                (fx/fn->lifecycle-with-context %))}
            :error-handler (fn [t]
                             (println "Error occurred in renderer" t)
                             (.printStackTrace t)))]
    (alter-var-root #'renderer (constantly r)))
  (fx/mount-renderer (var-get (find-var 'spring-lobby/*ui-state)) renderer))

(defn client-handler [state-atom server-url message]
  (try
    (require 'skylobby.client)
    (require 'skylobby.client.handler)
    (let [new-handler (var-get (find-var 'skylobby.client.handler/handle))]
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
    (println "Requiring spring-lobby ns")
    (require 'spring-lobby)
    (let [new-state-var (find-var 'spring-lobby/*state)]
      (alter-var-root new-state-var (constantly *state)))
    (alter-var-root (find-var 'spring-lobby/*ui-state) (constantly *ui-state))
    (let [add-ui-state-watcher-fn (var-get (find-var 'spring-lobby/add-ui-state-watcher))]
      (add-ui-state-watcher-fn *state *ui-state))
    (when-not @headless
      (if-not renderer
        (create-renderer)
        (println "Renderer already exists")))
    (require 'skylobby.client)
    (alter-var-root (find-var 'skylobby.client/handler) (constantly client-handler))
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
      (let [save-window-and-divider-positions (var-get (find-var 'spring-lobby/save-window-and-divider-positions))]
        (save-window-and-divider-positions *state))
      (catch Throwable e
        (println "error saving window positions" e)))
    (when renderer
      (try
        (println "Unmounting")
        (fx/unmount-renderer *ui-state renderer)
        (alter-var-root #'renderer (constantly nil))
        (catch Throwable e
          (println "error unmounting" e)
          (throw e))))
    (println "Stopping old chimers")
    (let [{:keys [chimers]} @init-state]
      (doseq [chimer chimers]
        (stop-chimer chimer)))
    (try
      (let [filter-replays-channel (var-get (find-var 'spring-lobby/filter-replays-channel))]
        (async/close! filter-replays-channel))
      (catch Throwable e
        (println e)))
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
      (when (and (.exists f)
                 (not (.isDirectory f)))
        (if (string/starts-with? (.getName f) ".")
          (println "Ignoring hidden file change" f)
          (if @refreshing
            (println "Duplicate file event, skipping refresh")
            (try
              (println "Refreshing due to file change" f)
              (reset! refreshing true)
              (refresh-rerender)
              (catch Throwable e
                (println e)
                (throw e))))))))
  context)


(defn init
  ([]
   (init nil))
  ([{:keys [arguments]}]
   (when (= "headless" (first arguments))
     (reset! headless true))
   (try
     [datafy pprint chime/chime-at string/split edn/read-string http/get]
     (hawk/watch! [{:paths ["src/clj" "graal/clj" "test/clj"]
                    :handler refresh-on-file-change}])
     (require 'spring-lobby)
     (require 'spring-lobby.main)
     (require 'skylobby.fs)
     (when-not @headless
       (require 'skylobby.fx.root)
       (require 'skylobby.fx))
     (let [init-7z-fn (var-get (find-var 'skylobby.fs/init-7z!))]
       (future
         (try
           (println "Initializing 7zip")
           (init-7z-fn)
           (println "Finished initializing 7zip")
           (catch Throwable e
             (println e)))))
     (alter-var-root #'*state (constantly (var-get (find-var 'spring-lobby/*state))))
     (alter-var-root #'*ui-state (constantly (var-get (find-var 'spring-lobby/*ui-state))))
     (let [add-ui-state-watcher-fn (var-get (find-var 'spring-lobby/add-ui-state-watcher))
           initial-state-fn (var-get (find-var 'spring-lobby/initial-state))
           initial-state (initial-state-fn)]
       (add-ui-state-watcher-fn *state *ui-state)
       (reset! *state initial-state)
       (swap! *state assoc :css (css/register :skylobby.fx/current
                                  (or (:css initial-state)
                                      (var-get (find-var 'skylobby.fx/default-style-data))))))
     ; just use spring-lobby/*state for initial state, on refresh copy user/*state var back
     (require 'skylobby.client)
     (require 'skylobby.client.handler)
     (alter-var-root #'old-client-handler (constantly (var-get (find-var 'skylobby.client/handler))))
     (alter-var-root (find-var 'skylobby.client/handler) (constantly client-handler))
     (require 'spring-lobby)
     (let [init-fn (var-get (find-var 'spring-lobby/init))]
       (reset! init-state (init-fn *state))
       (when-not @headless
         (create-renderer)))
     (catch Throwable e
       (println e)
       (.printStackTrace e)
       (throw e)))))


(defn add-dependencies [coordinates]
  (pomegranate/add-dependencies
    :coordinates coordinates
    :repositories (merge maven-central
                         {"clojars" "https://clojars.org/repo"})))
