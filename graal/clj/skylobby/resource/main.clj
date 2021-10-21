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
  ;(System/load "/home/skynet/git/skynet/skylobby/7zip/Linux-amd64/lib7-Zip-JBinding.so")
  ;(System/loadLibrary "7-Zip-JBinding")
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
          (let [{:keys [downloadables-by-url]} (config/slurp-edn "downloadables.edn")]
            (if-let [spring-name (string/join " " (rest arguments))]
              (let [target (fs/file ".")
                    _ (println "Looking for" spring-name)
                    _ (println (count downloadables-by-url) "downloads")
                    matches (->> downloadables-by-url
                                 (filter
                                   (comp (some-fn
                                           (partial resource/could-be-this-engine? spring-name)
                                           (partial resource/could-be-this-mod? spring-name)
                                           (partial resource/could-be-this-map? spring-name))
                                         second)))]
                (if (seq matches)
                  (do
                    (println "Matching resources:")
                    (pprint matches)
                    (let [download (second (first matches))
                          state-atom (atom {})
                          url (:download-url download)
                          download-to (resource/resource-dest target download)]
                      (println "Downloading" url "to" download-to)
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
                          (http/download-file state-atom url download-to)
                          (when (= :spring-lobby/engine (:resource-type download))
                            (log/info "Extracting engine")
                            (let [extract-to (fs/file target "engine" (fs/filename download-to))]
                              (fs/extract-7z-fast download-to extract-to)))
                          (catch Exception e
                            (log/error e "Error downloading"))
                          (catch Throwable e
                            (log/error e "Critical error downloading"))
                          (finally
                            (.close chimer)
                            (System/exit 0))))))
                  (println "No matching resources found")))
              (println "Usage: get <spring-name>"))))
        :else
        (println "Unknown start option")))))
