(ns skylobby.tui
  (:require
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.tools.cli :as cli]
    [skylobby.cli.util :as cu]
    skylobby.core
    [skylobby.fs :as fs]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(def cli-options
  [
   ["-h" "--help" "Print help and exit"]
   [nil "--version" "Print version and exit"]])

(defn usage [options-summary]
  (->> [""
        u/app-name
        ""
        (str "Usage: " u/app-name " [options] action")
        ""
        "skylobby text user interface mode. Reads EDN commands from stdin, writes similarly to stdout."
        ""
        "Options:"
        options-summary]))

(defn -main [& args]
  (let [{:keys [arguments errors options summary]} (cli/parse-opts args cli-options :in-order true)
        command (first arguments)
        version (u/version)]
    (u/log-only-to-file (fs/canonical-path (fs/config-file (str u/app-name ".log"))))
    (alter-var-root #'skylobby.util/app-version (fn [& _] version))
    (cond
      errors
      (apply cu/print-and-exit -1
        "Error parsing arguments:\n"
        errors)
      (or (= "help" command)
          (:help options))
      (cu/print-and-exit 0 (usage summary))
      (or (= "version" command)
          (:version options))
      (cu/print-and-exit 0 (str u/app-name " " version))
      :else
      (let [
            before-state (u/curr-millis)
            _ (log/info "Loading initial state")
            initial-state (skylobby.core/initial-state)
            state (merge
                    initial-state
                    (when (contains? options :spring-root)
                      (let [f (fs/file (:spring-root options))]
                        {
                         :ipc-server-enabled false
                         :spring-isolation-dir f
                         ::spring-root-arg f})))]
        (log/info "Loaded initial state in" (- (u/curr-millis) before-state) "ms")
        (reset! skylobby.core/*state state)
        (skylobby.core/init skylobby.core/*state)
        (async/thread
          (with-open [^java.io.BufferedReader reader (io/reader System/in)]
            (loop []
              (if-let [line (.readLine reader)]
                (do
                  (log/info "<" line)
                  (when (= "test" line)
                    (println (pr-str {:state-keys (sort (keys state))})))
                  (when (= "servers" line)
                    (println (pr-str (select-keys state [:servers :logins]))))
                  (recur))
                (log/info "stdin stream closed"))))
          (System/exit 0))))))
