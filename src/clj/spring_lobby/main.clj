(ns spring-lobby.main
  (:require
    [clj-http.client :as clj-http]
    clojure.core.async
    [clojure.tools.cli :as cli]
    [skylobby.util :as u]
    [spring-lobby.ui-main :as ui-main]
    [spring-lobby.fs :as fs]
    [taoensso.timbre :as log])
  (:gen-class))


(set! *warn-on-reflection* true)


(def cli-options [])


(defn -main [& args]
  (u/log-to-file (fs/canonical-path (fs/config-file (str u/app-name ".log"))))
  (log/info "Main" (pr-str args))
  (let [{:keys [arguments]} (cli/parse-opts args cli-options)
        replay-file (ui-main/parse-replay-file arguments)
        opening-replay? (some? replay-file)]
    (try
      (if (and opening-replay? (not (u/is-port-open? u/ipc-port)))
        (do
          (log/info "Sending IPC to existing skylobby instance on port" u/ipc-port)
          (clj-http/post
            (str "http://localhost:" u/ipc-port "/replay")
            {:query-params {:path (fs/canonical-path replay-file)}})
          (System/exit 0))
        (apply ui-main/-main args))
      (catch Throwable t
        (let [st (with-out-str (.printStackTrace t))]
          (println st)
          (spit "skylobby-fatal-error.txt" st))
        (log/error t "Fatal error")
        (System/exit -1)))))
