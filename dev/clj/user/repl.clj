(ns repl
  (:require
    [cider.nrepl]
    [clojure.string :as string]
    [io.aviso.repl]
    [nrepl.cmdline]
    [reply.main]))


(def middleware
  ['cider.nrepl/cider-middleware
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
   'cider.nrepl/wrap-refresh
   'cider.nrepl/wrap-stacktrace
   'cider.nrepl/wrap-test
   'cider.nrepl/wrap-trace
   'cider.nrepl/wrap-out
   'cider.nrepl/wrap-undef
   'cider.nrepl/wrap-version])

(defn -main [& _args]
  (future
    (nrepl.cmdline/-main
      "--middleware"
      (str "[" (string/join "," middleware) "]")
      ;"--interactive"
      "--color"))
  (loop []
    (when
      (try
        (println "Sleeping and trying REPLy")
        (Thread/sleep 500)
        (reply.main/launch-nrepl
          {:attach (slurp ".nrepl-port")
           :caught io.aviso.repl/pretty-pst
           :color true})
        false
        (catch Exception _e
          (println "Error connecting REPLy")
          true))
      (recur)))
  (System/exit 0))
