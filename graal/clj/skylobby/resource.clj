(ns skylobby.resource
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.rapid :as rapid]))


(set! *warn-on-reflection* true)


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
        ;resource-name (http/engine-download-file resource-name)
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

(def bar-platforms
  {"linux64" "linux-64"
   "win32" "windows-64"
   "win64" "windows-64"})

(defn bar-engine-filename
  ([version]
   (bar-engine-filename version (fs/platform)))
  ([version platform]
   (when version
     (let [bar-platform (get bar-platforms platform)]
       (if (string/includes? version "BAR105")
         (str "spring_bar_.BAR105." (first (string/split version #"\s"))
              "_" bar-platform "-minimal-portable.7z")
         (str "spring_bar_.BAR." (first (string/split version #"\s"))
              "_" bar-platform "-minimal-portable.7z"))))))

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
                   (= (engine-archive engine-version "master" (fs/platform64))
                      resource-filename)
                   (= (bar-engine-filename engine-version) resource-filename)))))))


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
