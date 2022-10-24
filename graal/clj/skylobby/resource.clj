(ns skylobby.resource
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.http :as http]
    [skylobby.rapid :as rapid]))


(set! *warn-on-reflection* true)


(def max-tries 5)

(def resource-types
  [:spring-lobby/engine :spring-lobby/map :spring-lobby/mod :spring-lobby/sdp])

(def no-springfiles
  [#"Beyond All Reason"
   #"Total Atomization Prime"
   #"Total Atomic Power"
   #"Tech Annihilation"
   #"SplinterFaction"])

(def no-rapid
  [#"Total Atomization Prime"
   #"Total Atomic Power"
   #"Evolution RTS Music Addon"
   #"SplinterFaction"])


(defn mod-dependencies [mod-name]
  (if (and mod-name (string/starts-with? mod-name "Evolution RTS"))
    ["Evolution RTS Music Addon v2"]
    nil))

(defn mod-repo-name [mod-name]
  (when mod-name
    (cond
      (string/includes? mod-name "Beyond All Reason") "byar"
      (string/includes? mod-name "Balanced Annihilation") "ba"
      (string/includes? mod-name "Metal Factions") "mf"
      (string/includes? mod-name "Tech Annihilation") "techa"
      :else nil)))

(defn update-mod-repo-name [mod-name]
  (or (mod-repo-name mod-name)
      "i18n"))


(defn resource-dest
  [root {:keys [resource-filename resource-file resource-type]}]
  (let [filename (or resource-filename
                     (fs/filename resource-file))]
    (case resource-type
      :spring-lobby/engine
      (cond
        (and resource-file (fs/exists? resource-file) (fs/is-directory? resource-file))
        (io/file (fs/engines-dir root) filename)
        filename (io/file (fs/download-dir) "engine" filename)
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

(defn remove-v-before-number [s]
  (when s
    (string/replace s #"v(\d+)" "$1")))


(defn normalize-map [map-name-or-filename]
  (some-> map-name-or-filename
          string/lower-case
          (string/replace #"\s+" "_")
          (string/replace #"[-']" "_")
          (string/replace #"\.sd[7z]$" "")
          remove-v-before-number))

(defn could-be-this-map?
  "Returns true if this resource might be the map with the given name, by magic, false otherwise."
  [map-name {:keys [resource-filename resource-name]}]
  (and map-name
       (or (= map-name resource-name)
           (when (and map-name resource-filename)
             (= (normalize-map map-name)
                (normalize-map resource-filename))))))


(def default-engine-branch "master")

(def engine-branches
  ["master" "maintenance" "develop"])  ; TODO maybe auto detect these


(defn detect-engine-branch
  [engine-version]
  (when engine-version
    (or
      (some
        (fn [engine-branch]
          (when
            (string/includes? engine-version engine-branch)
            engine-branch))
        engine-branches)
      default-engine-branch)))

(defn engine-archive
  ([version]
   (engine-archive (detect-engine-branch version) version))
  ([branch version]
   (engine-archive branch version (fs/platform)))
  ([branch version platform]
   (when version
     (let [mp "minimal-portable"
           suffix (cond
                    (string/starts-with? platform "linux")
                    (str mp "-" platform "-static")
                    (string/starts-with? platform "win")
                    (str platform "-" mp))]
       (str "spring_"
            (when (not= "master" branch) (str "{" branch "}"))
            (first (string/split version #"\s"))
            "_" suffix ".7z")))))


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
                   (= (engine-archive engine-version)
                      resource-filename)
                   (some
                     (fn [platform]
                       (= (engine-archive "master" engine-version platform)
                          resource-filename))
                     (fs/platforms))
                   (= (http/bar-engine-filename engine-version) resource-filename)))))))


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
  [["total_atomization_prime" "taprime"]
   ["total_atomization_prime" "tap"]
   ["total_atomic_power" "tap"]
   ["evolution_rts" "evo_rts"]
   ["evolution_rts" "evo"]
   ["tech_annihilation" "techa"]
   ["balanced_annihilation" "ba"]
   ["beyond_all_reason" "bar"]
   ["beyond_all_reason" "byar"]
   ["metal_factions" "mf"]])

(def mod-aliases-harder
  [["totalatomizationprime" "taprime"]
   ["totalatomizationprime" "tap"]
   ["evolutionrts" "evorts"]
   ["evolutionrts" "evo"]
   ["techannihilation" "techa"]
   ["balancedannihilation" "ba"]
   ["beyondallreason" "bar"]
   ["beyondallreason" "byar"]
   ["metalfactions" "mf"]])

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
               (= (replace-all (normalize-mod mod-name) mod-aliases)
                  (replace-all (normalize-mod resource-filename) mod-aliases))
               (= (replace-all (normalize-mod-harder mod-name) mod-aliases-harder)
                  (replace-all (normalize-mod-harder resource-filename) mod-aliases-harder)))))))


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


(defn- parse-mod-name-git [mod-name]
  (or (re-find #"(.+)\s([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\sgit:([0-9a-f]+)$" mod-name)
      (re-find #"(.+)\s(\$VERSION)$" mod-name)))

(defn mod-name-sans-git [mod-name]
  (when mod-name
    (if-let [[_all mod-prefix _git] (parse-mod-name-git mod-name)]
      mod-prefix
      mod-name)))

(defn mod-name-git-no-ref [mod-name]
  (when mod-name
    (when-let [[_all mod-prefix _git] (re-find #"(.+)\sgit:([0-9a-f]+)$" mod-name)]
      (str mod-prefix " git:"))))

(defn engine-details [engines engine-version]
  (some->> engines
           (filter (comp #{engine-version} :engine-version))
           first))

(defn engine-download-source [engine-version]
  (cond
    (string/blank? engine-version) nil
    (string/includes? engine-version "BAR") "BAR GitHub spring"
    :else "SpringRTS buildbot"))

(defn sync-status [server-data spring mod-details map-details]
  (let [battle-id (-> server-data :battle :battle-id)
        battle (-> server-data :battles (get battle-id))
        engines (:engines spring)
        engine-version (:battle-version battle)
        engine-details (engine-details engines engine-version)

        mod-name (:battle-modname battle)
        mod-sans-git (mod-name-sans-git mod-name)
        mod-name-set (set [mod-name mod-sans-git])
        filter-fn (comp mod-name-set mod-name-sans-git :mod-name)
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
  (let [spring-root-path (fs/canonical-path spring-root)
        spring-root-data (get by-spring-root spring-root-path)
        {:keys [engine-version engines map-name maps mod-name mods]} spring-root-data
        maps (filter :map-name maps)
        mods (filter :mod-name mods)
        git-mods (->> mods
                      (filter :mod-name)
                      (filter (comp #(string/includes? % "git:") :mod-name))
                      (map (juxt (comp mod-name-git-no-ref :mod-name) identity))
                      (filter first)
                      (into {}))
        mods-by-name (->> mods
                          (map (juxt :mod-name identity))
                          (into {})
                          (merge git-mods))
        mods-by-name-only (group-by :mod-name-only mods)]
    {:spring-isolation-dir spring-root
     :spring-root-path spring-root-path
     :engine-version engine-version
     :engines engines
     :engines-by-version (into {} (map (juxt :engine-version identity) engines))
     :engines-grouped-by-version (group-by :engine-version engines)
     :map-name map-name
     :maps maps
     :maps-by-name (into {} (map (juxt :map-name identity) maps))
     :mod-name mod-name
     :mods mods
     :mods-by-name mods-by-name
     :mods-by-name-only mods-by-name-only}))
