(ns skylobby.main
  (:require
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    skylobby
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:gen-class))


(set! *warn-on-reflection* true)


(def cli-options
  [])


(defn -main [& args]
  (let [version (or (u/manifest-version) "UNKNOWN")]
    (log/info "skylobby" version)
    (alter-var-root #'skylobby.util/app-version (fn [& _] version)))
  (let [{:keys [errors]} (cli/parse-opts args cli-options)]
    (if errors
      (do
        (println "Error parsing arguments:\n\n"
                 (string/join \newline errors))
        (System/exit -1))
      (skylobby/init skylobby/*state))))
