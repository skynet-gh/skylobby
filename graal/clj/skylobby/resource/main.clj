(ns skylobby.resource.main
  (:require
    [chime.core :as chime]
    [clojure.core.async :as async]
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    java-time
    [skylobby.config :as config]
    [skylobby.fs :as fs]
    [skylobby.http :as http]
    [skylobby.resource :as resource]
    [skylobby.spring :as spring]
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
   [nil "--spring-root SPRING_ROOT" "Set the spring-root config to the given directory"]
   ; TODO play options
   [nil "--engine ENGINE" "Engine to use"]
   [nil "--game GAME" "Game to use"]
   [nil "--map MAP" "Map to use"]])


(defn matching-download [downloadables-by-url spring-name]
  (let [
        matches (->> downloadables-by-url
                     (filter
                       (comp (some-fn
                               (partial resource/could-be-this-engine? spring-name)
                               (partial resource/could-be-this-mod? spring-name)
                               (partial resource/could-be-this-map? spring-name))
                             second)))]
    (second (first matches))))

(defn download-file [state-atom download target]
  (try
    (let [url (:download-url download)
          download-to (resource/resource-dest target download)]
      (println "Downloading" url "to" download-to)
      (http/download-file state-atom url download-to)
      (when (= :spring-lobby/engine (:resource-type download))
        (log/info "Extracting engine")
        (let [extract-to (fs/file target "engine" (fs/filename download-to))]
          (fs/extract-7z-fast download-to extract-to))))
    (catch Exception e
      (log/error e "Error downloading"))
    (catch Throwable e
      (log/error e "Critical error downloading"))))

(defn get-resource [spring-name options]
  (when-let [app-root-override (:skylobby-root options)]
    (println "setting app root override to" app-root-override)
    (alter-var-root #'fs/app-root-override (constantly app-root-override)))
  (if spring-name
    (let [_ (println "Looking for downloads")
          {:keys [downloadables-by-url]} (config/slurp-edn "downloadables.edn")]
      (println "Found" (count downloadables-by-url) "downloads")
      (let [target (fs/file ".")
            _ (println "Looking for" spring-name)
            download (matching-download downloadables-by-url spring-name)]
        (if download
          (let [_ (println "Found download" (:resource-filename download) "from" (:download-source-name download))
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
                  (System/exit 0)))))
          (println "No matching resources found"))))
    (println "Usage: get <spring-name>")))


(def progress-column "%-24.24s")
(def resources-format
  (str " Engine: " progress-column "  Game: " progress-column "  Map: " progress-column))

(defn get-resources [options]
  (when-let [app-root-override (:skylobby-root options)]
    (println "setting app root override to" app-root-override)
    (alter-var-root #'fs/app-root-override (constantly app-root-override)))
  (let [_ (println "Looking for downloads")
        {:keys [downloadables-by-url]} (config/slurp-edn "downloadables.edn")]
    (println "Found" (count downloadables-by-url) "downloads")
    (let [target (fs/file ".")
          engine (:engine options)
          mod-name (:game options)
          map-name (:map options)
          engine-download (matching-download downloadables-by-url engine)
          mod-download (matching-download downloadables-by-url mod-name)
          map-download (matching-download downloadables-by-url map-name)
          state-atom (atom {})
          chimer
          (chime/chime-at
            (chime/periodic-seq
              (java-time/plus (java-time/instant) (Duration/ofMillis 1000))
              (Duration/ofMillis 1000))
            (fn [_chimestamp]
              (try
                (let [state @state-atom
                      engine-progress (get-in state [:http-download (:download-url engine-download)])
                      mod-progress (get-in state [:http-download (:download-url mod-download)])
                      map-progress (get-in state [:http-download (:download-url map-download)])]
                  (println
                    (format resources-format
                      (u/download-progress engine-progress)
                      (u/download-progress mod-progress)
                      (u/download-progress map-progress))))
                (catch Exception e
                  (log/error e "Error printing status"))))
            {:error-handler
             (fn [e]
               (log/error e "Error printing status")
               true)})]
      (try
        (let [engine-future (future (download-file state-atom engine-download target))
              mod-future (future (download-file state-atom mod-download target))
              map-future (future (download-file state-atom map-download target))]
          (future
            (async/<!! (async/timeout 500))
            (println)
            (println (format resources-format engine mod-name map-name)))
          @engine-future
          @mod-future
          @map-future)
        (catch Throwable t
          (log/error t "Critical error"))
        (finally
          (.close chimer)
          (System/exit 0))))))


(defn play [scenario options]
  (let [engine (:engine options)
        mod-name (:game options)
        map-name (:map options)
        spring-root (fs/file (or (:spring-root options)
                                 "."))
        engine-dirs (fs/engine-dirs spring-root)
        engine-dir (->> engine-dirs
                        (filter
                          (fn [dir]
                            (resource/could-be-this-engine? engine {:resource-filename (fs/filename dir)})))
                        first)]
    (println spring-root)
    (println engine-dir)))
    ;(spring/start-game engine-dir spring-root)))


(defn -main [& args]
  (log/merge-config! {:appenders {:println {:min-level :info}}})
  (let [{:keys [arguments errors options summary]} (cli/parse-opts args cli-options)]
    (if errors
      (do
        (println "Error parsing arguments:\n\n"
                 (string/join \newline errors))
        (System/exit -1))
      (cond
        (or (= "help" (first arguments))
            (:help options))
        (println summary)
        (or (= "version" (first arguments))
            (:version options))
        (println (str u/app-name " " "todo version"))
        (= "get" (first arguments))
        (get-resource (string/join " " (rest arguments)) options)
        (= "getall" (first arguments))
        (get-resources options)
        (= "play" (first arguments))
        (play (string/join " " (rest arguments)) options)
        :else
        (println "Unknown start option")))))
