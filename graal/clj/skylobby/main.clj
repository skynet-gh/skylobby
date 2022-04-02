(ns skylobby.main
  (:require
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    skylobby.core
    [skylobby.cli :as cli-demo]
    [skylobby.cli.util :as cu]
    [skylobby.fs :as fs]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:gen-class))


(set! *warn-on-reflection* true)


(def cli-options
  [
   ["-h" "--help" "Print help and exit"]
   [nil "--version" "Print version and exit"]
   [nil "--spring-root SPRING_ROOT" "Set the spring-root config to the given directory"]])


(defn usage [options-summary]
  (->> [""
        u/app-name
        ""
        (str "Usage: " u/app-name " [options] action")
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  cli       Demo CLI interface"
        "  <none>    Start client service with web UI"
        ""]
       (string/join \newline)))


(defn -main [& args]
  (let [{:keys [arguments errors options summary]} (cli/parse-opts args cli-options :in-order true)
        command (first arguments)
        version (u/version)]
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
      (= "cli" command)
      (apply cli-demo/-main (rest arguments))
      (seq arguments)
      (cu/print-and-exit -1 "Unknown action: " (pr-str arguments))
      :else
      (let [
            before-state (u/curr-millis)
            _ (log/info "Loading initial state")
            initial-state (skylobby.core/initial-state)
            state (merge
                    initial-state
                    (when (contains? options :spring-root)
                      (let [f (fs/file (:spring-root options))]
                        {:spring-isolation-dir f
                         ::spring-root-arg f})))]
        (log/info "Loaded initial state in" (- (u/curr-millis) before-state) "ms")
        (reset! skylobby.core/*state state)
        (skylobby.core/init skylobby.core/*state)))))
