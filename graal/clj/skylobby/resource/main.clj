(ns skylobby.resource.main
  (:require
    [chime.core :as chime]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    java-time
    [skylobby.config :as config]
    [skylobby.fs :as fs]
    [skylobby.http :as http]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.time Duration))
  (:gen-class))


(set! *warn-on-reflection* true)


(def cli-options
  [[nil "--help" "Print help and exit"]
   [nil "--version" "Print version and exit"]
   [nil "--skylobby-root SKYLOBBY_ROOT" "Set the config and log dir for skylobby"]
   [nil "--spring-root SPRING_ROOT" "Set the spring-root config to the given directory"]])


(defn -main [& args]
  (let [{:keys [arguments errors options] :as parsed} (cli/parse-opts args cli-options)]
    (println parsed)
    (if errors
      (do
        (println "Error parsing arguments:\n\n"
                 (string/join \newline errors))
        (System/exit -1))
      (cond
        (or (= "help" (first arguments))
            (:help options))
        (println "todo help")
        (or (= "version" (first arguments))
            (:version options))
        (println (str u/app-name " " "todo version"))
        (= "get" (first arguments))
        (do
          (when-let [app-root-override (:skylobby-root options)]
            (println "setting app root override to" app-root-override)
            (alter-var-root #'fs/app-root-override (constantly app-root-override)))
          (println (str u/app-name " " "todo version"))
          (let [{:keys [downloadables-by-url]} (config/slurp-edn "downloadables.edn")
                spring-root (if (contains? options :spring-root)
                              (fs/file (:spring-root options))
                              (fs/default-spring-root))]
            (if-let [spring-name (string/join " " (rest arguments))]
              (let [_ (println "Looking for" spring-name)
                    _ (println (count downloadables-by-url) "downloads")
                    matches (->> downloadables-by-url
                                 (filter (comp (partial resource/could-be-this-map? spring-name) second)))]
                (if (seq matches)
                  (do
                    (println "Matching resources:")
                    (pprint matches)
                    (let [download (second (first matches))
                          state-atom (atom {})
                          url (:download-url download)
                          dest (resource/resource-dest (fs/file ".") download)]
                      (println "Downloading" url "to" dest)
                      (let [chimer
                            (chime/chime-at
                              (chime/periodic-seq
                                (java-time/plus (java-time/instant) (Duration/ofMillis 1000))
                                (Duration/ofMillis 1000))
                              (fn [_chimestamp]
                                (try
                                  (let [state @state-atom
                                        progress (get-in state [:http-download url])]
                                    (println (u/download-progress progress)))
                                  (catch Exception e
                                    (log/error e "Error printing status"))))
                              {:error-handler
                               (fn [e]
                                 (log/error e "Error printing status")
                                 true)})]
                        (try
                          (http/download-file state-atom url dest)
                          (catch Exception e
                            (log/error e "Error downloading"))
                          (finally
                            (.close chimer)
                            (System/exit 0))))))
                  (println "No matching resources found")))
              (println "Usage: get <spring-name>"))))
        :else
        (println "Unknown start option")))))
