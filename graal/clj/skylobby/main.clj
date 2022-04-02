(ns skylobby.main
  (:require
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    skylobby.core
    [skylobby.cli :as cli-demo]
    [skylobby.fs :as fs]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:gen-class))


(set! *warn-on-reflection* true)


(def cli-options
  [
   [nil "--help" "Print help and exit"]
   [nil "--version" "Print version and exit"]
   [nil "--spring-root SPRING_ROOT" "Set the spring-root config to the given directory"]])


(defn -main [& args]
  (let [version (or (u/manifest-version) "UNKNOWN")]
    (log/info "skylobby" version)
    (alter-var-root #'skylobby.util/app-version (fn [& _] version)))
  (let [{:keys [arguments errors options summary]} (cli/parse-opts args cli-options :in-order true)
        command (first arguments)]
    (cond 
      errors
      (do
        (println "Error parsing arguments:\n\n"
                 (string/join \newline errors))
        (System/exit -1))
      (or (= "help" command)
          (:help options))
      (println summary)
      (or (= "version" command)
          (:version options))
      (println (str u/app-name " " "todo version"))
      (= "cli" command)
      (apply cli-demo/-main (rest arguments))
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
