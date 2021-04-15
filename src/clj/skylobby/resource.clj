(ns skylobby.resource
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [spring-lobby.fs :as fs]
    [spring-lobby.http :as http]
    [spring-lobby.rapid :as rapid]))


(defn resource-dest
  [root {:keys [resource-filename resource-name resource-file resource-type]}]
  (let [filename (or resource-filename
                     (fs/filename resource-file))]
    (case resource-type
      :spring-lobby/engine
      (cond
        (and resource-file (fs/exists resource-file) (fs/is-directory? resource-file))
        (io/file (fs/engines-dir root) filename)
        filename (io/file (fs/download-dir) "engine" filename)
        resource-name (http/engine-download-file resource-name)
        :else nil)
      :spring-lobby/mod
      (when filename
        (if (string/ends-with? filename ".sdp")
          (rapid/sdp-file root filename)
          (fs/mod-file root filename)))
      :spring-lobby/map
      (when filename (io/file (fs/map-file root filename)))
      :spring-lobby/replay
      (when filename (io/file (fs/replays-dir root) filename))
      nil)))

(defn could-be-this-engine?
  "Returns true if this resource might be the engine with the given name, by magic, false otherwise."
  [engine-version {:keys [resource-filename resource-name]}]
  (and engine-version
       (or (= engine-version resource-name)
           (when resource-filename
             (let [lce (string/lower-case engine-version)
                   lcf (string/lower-case resource-filename)]
               (or (= engine-version resource-filename)
                   (= lce lcf)
                   (= (http/engine-archive engine-version)
                      resource-filename)
                   (= (http/bar-engine-filename engine-version) resource-filename)))))))

(defn normalize-map [map-name-or-filename]
  (some-> map-name-or-filename
          string/lower-case
          (string/replace #"\s+" "_")
          (string/replace #"-" "_")
          (string/replace #"\.sd[7z]$" "")))

(defn could-be-this-map?
  "Returns true if this resource might be the map with the given name, by magic, false otherwise."
  [map-name {:keys [resource-filename resource-name]}]
  (and map-name
       (or (= map-name resource-name)
           (when (and map-name resource-filename)
             (= (normalize-map map-name)
                (normalize-map resource-filename))))))

(defn normalize-mod [mod-name-or-filename]
  (-> mod-name-or-filename
      string/lower-case
      (string/replace #"\s+" "_")
      (string/replace #"-" "_")
      (string/replace #"\.sd[7z]$" "")))

(defn could-be-this-mod?
  "Returns true if this resource might be the mod with the given name, by magic, false otherwise."
  [mod-name {:keys [resource-filename resource-name]}]
  (and mod-name
       (or (= mod-name resource-name)
           (when (and mod-name resource-filename)
             (= (normalize-mod mod-name)
                (normalize-mod resource-filename))))))

(defn same-resource-file? [resource1 resource2]
  (and (:resource-file resource1)
       (= (:resource-file resource1)
          (:resource-file resource2))))

(defn same-resource-filename? [resource1 resource2]
  (and (:resource-filename resource1)
       (= (:resource-filename resource1)
          (:resource-filename resource2))))
