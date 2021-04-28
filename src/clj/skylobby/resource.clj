(ns skylobby.resource
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [spring-lobby.fs :as fs]
    [spring-lobby.http :as http]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.util :as u]))


(def max-tries 5)


(def resource-types
  [::engine ::map ::mod ::sdp]) ; TODO split out packaging type from resource type...

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
  (boolean
    (and (:resource-file resource1)
         (= (:resource-file resource1)
            (:resource-file resource2)))))

(defn same-resource-filename? [resource1 resource2]
  (boolean
    (and (:resource-filename resource1)
         (= (:resource-filename resource1)
            (:resource-filename resource2)))))


(defn details?
  "Returns true if the given possible resource details have content, false otherwise."
  [details]
  (boolean
    (and
      details
      (seq details)
      (not (:error details)))))

(defn details-cache-key [resource]
  (fs/canonical-path (:file resource)))

(defn cached-details [cache resource]
  (when-let [k (details-cache-key resource)]
    (get cache k)))


(defn sync-status [server-data spring mod-details map-details]
  (let [battle-id (-> server-data :battle :battle-id)
        battle (-> server-data :battles (get battle-id))
        engines (:engines spring)
        engine (:battle-version battle)
        engine-details (->> engines (filter (comp #{engine} :engine-version)) first)

        mod-name (:battle-modname battle)
        mod-sans-git (u/mod-name-sans-git mod-name)
        mod-name-set (set [mod-name mod-sans-git])
        filter-fn (comp mod-name-set u/mod-name-sans-git :mod-name)
        mods (:mods spring)
        mod-index (->> mods (filter filter-fn) first)
        mod-details (-> mod-details (get (fs/canonical-path (:file mod-index))))

        map-name (:battle-map battle)
        maps (:maps spring)
        map-index (->> maps (filter (comp #{map-name} :map-name)) first)
        map-details (-> map-details (get (fs/canonical-path (:file map-index))))]
    (boolean
      (and map-index
           (details? map-details)
           mod-index
           (details? mod-details)
           (seq engine-details)))))
