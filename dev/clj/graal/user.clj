(ns user
  (:require
    [chime.core :as chime]
    [clj-http.client :as http]
    [clojure.datafy :refer [datafy]]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [clojure.tools.namespace.repl :refer [disable-unload! disable-reload! refresh]]
    [hawk.core :as hawk]
    io.aviso.repl
    pjstadig.humane-test-output))


(disable-unload!)
(disable-reload!)


(pjstadig.humane-test-output/activate!)
(io.aviso.repl/install-pretty-exceptions)



(def *state (atom nil))


(def init-state (atom nil))


(def refreshing (atom false))


(def ^:dynamic old-client-handler nil)


(defn stop-chimer [chimer]
  (when chimer
    (try
      ; (println "Stopping chimer" chimer) ; noisy
      (chimer)
      (catch Throwable e
        (println "error stopping chimer" chimer e)))))

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
    (println "Requiring skylobby ns")
    (require 'skylobby)
    (require 'skylobby.main)
    (let [new-state-var (find-var 'skylobby/*state)]
      (alter-var-root new-state-var (constantly *state)))
    (require 'skylobby.client)
    (alter-var-root (find-var 'skylobby.client/handler) (constantly client-handler))
    (try
      (let [init-fn (var-get (find-var 'skylobby/init))]
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
    (println "Stopping old chimers")
    (let [{:keys [chimers]} @init-state]
      (doseq [chimer chimers]
        (stop-chimer chimer)))
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
   (try
     [datafy pprint chime/chime-at string/split edn/read-string http/get]
     (hawk/watch! [{:paths ["graal/clj"]
                    :handler refresh-on-file-change}])
     (require 'skylobby)
     (require 'skylobby.fs)
     (let [init-7z-fn (var-get (find-var 'skylobby.fs/init-7z!))]
       (future
         (try
           (println "Initializing 7zip")
           (init-7z-fn)
           (println "Finished initializing 7zip")
           (catch Throwable e
             (println e)))))
     (alter-var-root #'*state (constantly (var-get (find-var 'skylobby/*state))))
     (let [
           initial-state-fn (var-get (find-var 'skylobby/initial-state))
           initial-state (initial-state-fn)]
       (reset! *state initial-state))
     (require 'skylobby.client)
     (require 'skylobby.client.handler)
     (alter-var-root #'old-client-handler (constantly (var-get (find-var 'skylobby.client/handler))))
     (alter-var-root (find-var 'skylobby.client/handler) (constantly client-handler))
     (require 'skylobby)
     (require 'skylobby.main)
     (let [init-fn (var-get (find-var 'skylobby/init))]
       (reset! init-state (init-fn *state)))
     (catch Throwable e
       (println e)
       (.printStackTrace e)
       (throw e)))))
