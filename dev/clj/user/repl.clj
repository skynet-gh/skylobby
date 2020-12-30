(ns repl
  (:require
    [cider.nrepl]
    [clojure.string :as string]
    hashp.core
    [io.aviso.repl]
    [nrepl.cmdline]
    [reply.main]
    [taoensso.timbre :as timbre]
    [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
    user))


(def ^:private dev-log-path "repl.log")


(defn log-to-file []
  (println "Setting up log to" dev-log-path)
  (timbre/merge-config!
    {:min-level :info
     :appenders
     {:rotor (rotor/rotor-appender
               {:path dev-log-path
                :max-size 100000000
                :backlog 1
                :stacktrace-fonts {}})}}))

(defn disable-print-log []
  (timbre/merge-config!
    {:appenders
     {:println {:enabled? false}}}))


(def middleware
  [;'cider.nrepl/cider-middleware
   'cider.nrepl/wrap-clojuredocs
   'cider.nrepl/wrap-classpath
   'cider.nrepl/wrap-complete
   'cider.nrepl/wrap-debug
   'cider.nrepl/wrap-format
   'cider.nrepl/wrap-info
   'cider.nrepl/wrap-inspect
   'cider.nrepl/wrap-macroexpand
   'cider.nrepl/wrap-ns
   'cider.nrepl/wrap-spec
   'cider.nrepl/wrap-profile
   'cider.nrepl/wrap-resource
   ;'cider.nrepl/wrap-refresh TODO re-render
   'cider.nrepl/wrap-stacktrace
   'cider.nrepl/wrap-test
   'cider.nrepl/wrap-trace
   'cider.nrepl/wrap-out
   'cider.nrepl/wrap-undef
   'cider.nrepl/wrap-version])

(defn -main [& _args]
  (log-to-file)
  (disable-print-log)
  (future
    (nrepl.cmdline/-main
      "--middleware"
      (str "[" (string/join "," middleware) "]")
      ;"--interactive"
      "--color"))
  (user/init)
  (loop []
    (when
      (try
        (println "Trying to start REPLy")
        (reply.main/launch-nrepl
          {:attach (slurp ".nrepl-port")
           :caught io.aviso.repl/pretty-pst
           :color true})
        false
        (catch Exception _e
          (println "Error connecting REPLy, sleeping")
          (Thread/sleep 500)
          true))
      (recur)))
  (System/exit 0))
