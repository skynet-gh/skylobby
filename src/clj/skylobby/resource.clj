(ns skylobby.resource
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [spring-lobby.fs :as fs]
    [spring-lobby.http :as http]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.util :as u]))


(set! *warn-on-reflection* true)


(def max-tries 5)


(def resource-types
  [:spring-lobby/engine :spring-lobby/map :spring-lobby/mod :spring-lobby/sdp])
  ; TODO split out packaging type from resource type...


(defn mod-dependencies [mod-name]
  (if (and mod-name (string/starts-with? mod-name "Evolution RTS"))
    ["Evolution RTS Music Addon v2"]
    nil))

(defn mod-repo-name [mod-name]
  (or (when mod-name
        (cond
          (string/includes? mod-name "Beyond All Reason") "byar"
          :else nil))
      "i18n"))


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
                   (= (http/engine-archive engine-version "master" (fs/platform64))
                      resource-filename)
                   (= (http/bar-engine-filename engine-version) resource-filename)))))))

(defn normalize-map [map-name-or-filename]
  (some-> map-name-or-filename
          string/lower-case
          (string/replace #"\s+" "_")
          (string/replace #"[-']" "_")
          (string/replace #"\.sd[7z]$" "")))

(defn could-be-this-map?
  "Returns true if this resource might be the map with the given name, by magic, false otherwise."
  [map-name {:keys [resource-filename resource-name]}]
  (and map-name
       (or (= map-name resource-name)
           (when (and map-name resource-filename)
             (= (normalize-map map-name)
                (normalize-map resource-filename))))))

(defn remove-v-before-number [s]
  (when s
    (string/replace s #"v(\d+)" "$1")))

(defn normalize-mod [mod-name-or-filename]
  (-> mod-name-or-filename
      string/lower-case
      (string/replace #"\s+" "_")
      (string/replace #"-" "_")
      (string/replace #"\.sd[7z]$" "")
      remove-v-before-number))

(defn normalize-mod-harder [mod-name-or-filename]
  (-> mod-name-or-filename
      string/lower-case
      (string/replace #"\.sd[7z]$" "")
      (string/replace #"[-_\.\s+]" "")
      remove-v-before-number))

(def mod-aliases
  {"Total Atomization Prime" "TAPrime"})

(defn replace-all [s rs]
  (reduce
    (fn [s [k v]]
      (string/replace s k v))
    s
    rs))

(defn could-be-this-mod?
  "Returns true if this resource might be the mod with the given name, by magic, false otherwise."
  [mod-name {:keys [resource-filename resource-name]}]
  (and mod-name
       (or (= mod-name resource-name)
           (when (and mod-name resource-filename)
             (or
               (= (normalize-mod mod-name)
                  (normalize-mod resource-filename))
               (= (normalize-mod-harder mod-name)
                  (normalize-mod-harder resource-filename))
               (= (normalize-mod (replace-all mod-name mod-aliases))
                  (normalize-mod (replace-all resource-filename mod-aliases))))))))

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


(defn spring-root-resources [spring-root by-spring-root]
  (let [spring-root-data (get by-spring-root (fs/canonical-path spring-root))
        {:keys [engines maps mods]} spring-root-data
        maps (filter :map-name maps)
        mods (filter :mod-name mods)
        git-mods (->> mods
                      (filter :mod-name)
                      (filter (comp #(string/includes? % "git:") :mod-name))
                      (map (juxt (comp u/mod-name-git-no-ref :mod-name) identity))
                      (filter first)
                      (into {}))
        mods-by-name (->> mods
                          (map (juxt :mod-name identity))
                          (into {})
                          (merge git-mods))]
    {:spring-isolation-dir spring-root
     :engines engines
     :engines-by-version (into {} (map (juxt :engine-version identity) engines))
     :maps maps
     :maps-by-name (into {} (map (juxt :map-name identity) maps))
     :mods mods
     :mods-by-name mods-by-name}))
