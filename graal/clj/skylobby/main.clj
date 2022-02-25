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
  [
   [nil "--port PORT" "Port to use for web ui AND ipc for file associations like replays"
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 % 65535) "Must be an integer from 0 through 65535"]]])


(defn -main [& args]
  (let [version (or (u/manifest-version) "UNKNOWN")]
    (log/info "skylobby" version)
    (alter-var-root #'skylobby.util/app-version (fn [& _] version)))
  (let [{:keys [errors options]} (cli/parse-opts args cli-options)]
    (if errors
      (do
        (println "Error parsing arguments:\n\n"
                 (string/join \newline errors))
        (System/exit -1))
      (do
        (when-let [port (:port options)]
          (alter-var-root #'u/ipc-port (constantly port)))
        (skylobby/init skylobby/*state)))))
