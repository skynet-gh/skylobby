(ns spring-lobby.main
  (:require
    [clj-http.client :as clj-http]
    clojure.core.async
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    [spring-lobby.ui-main :as ui-main]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:gen-class))


(set! *warn-on-reflection* true)


(def cli-options [])


(defn -main [& args]
  (log/info "Main")
  (let [{:keys [arguments]} (cli/parse-opts args cli-options)
        first-arg-as-file (some-> arguments first fs/file)
        first-arg-filename (fs/filename first-arg-as-file)
        opening-replay? (and (not (string/blank? first-arg-filename))
                             (string/ends-with? first-arg-filename ".sdfz")
                             (fs/exists? first-arg-as-file))]
    (try
      (if (and opening-replay? (not (u/is-port-open? u/ipc-port)))
        (do
          (log/info "Sending IPC to existing skylobby instance on port" u/ipc-port)
          (clj-http/post
            (str "http://localhost:" u/ipc-port "/replay")
            {:query-params {:path (fs/canonical-path first-arg-as-file)}})
          (System/exit 0))
        (apply ui-main/-main args))
      (catch Throwable t
        (let [st (with-out-str (.printStackTrace t))]
          (println st)
          (spit "skylobby-fatal-error.txt" st))
        (log/error t "Fatal error")
        (System/exit -1)))))
