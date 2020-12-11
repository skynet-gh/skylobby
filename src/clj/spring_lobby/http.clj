(ns spring-lobby.http
  (:require
    [clojure.string :as string]
    [crouton.html :as html]
    [spring-lobby.fs :as fs]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)

(def default-engine-branch "master")
(def engine-branches
  ["master" "maintenance" "develop"])  ; TODO maybe auto detect these

(def springrts-buildbot-root
  "https://springrts.com/dl/buildbot/default")

(def springfiles-maps-url
  "http://api.springfiles.com/files/maps")

(def springfightclub-root
  "https://www.springfightclub.com/data")


(defn- by-tag [element tag]
  (->> element
       :content
       (filter (comp #{tag} :tag))
       first))

(defn- in-tags [element tags]
  (let [tag (first tags)
        it (by-tag element tag)]
    (if-let [r (seq (rest tags))]
      (recur it r)
      it)))

(defn links [parsed-html]
  (let [rows (-> parsed-html
                 (in-tags [:body :table :tbody])
                 :content)]
    (->> rows
         (filter :content)
         (map
           (fn [row]
             (some
               #(by-tag % :a)
               (:content row))))
         (filter some?)
         (map (comp :href :attrs)))))


(defn files [parsed]
  (let [rows (-> parsed
                 (in-tags [:body :table :tbody])
                 :content)
        files (->> rows
                   (filter (comp #{:tr} :tag))
                   (map
                     (fn [{:keys [content]}]
                       (let [[_image link-td date-td size-td _description] content
                             link (-> link-td :content first)]
                         {:filename (-> link :content first)
                          :url (-> link :attrs :href)
                          :date (-> date-td :content first)
                          :size (-> size-td :content first)})))
                   (filter :filename))]
    (-> files
        (nthrest 2))))


(defn parsed-springrts-buildbot
  ([]
   (parsed-springrts-buildbot nil))
  ([path]
   (let [url (string/join
               "/"
               (concat
                 [springrts-buildbot-root]
                 path))]
     (log/info "Getting html table from" url)
     (html/parse url))))

(defn parsed-springfiles-maps []
  (html/parse springfiles-maps-url))

(defn parsed-springfightclub
  ([]
   (parsed-springfightclub  nil))
  ([path]
   (let [url (string/join
               "/"
               (concat
                 [springfightclub-root]
                 path))]
     (log/info "Getting html table from" url)
     (html/parse url))))


(defn springrts-buildbot-links
  ([]
   (springrts-buildbot-links nil))
  ([path]
   (let [parsed (parsed-springrts-buildbot path)]
     (links parsed))))

(defn springrts-buildbot-files
  ([]
   (springrts-buildbot-files nil))
  ([path]
   (let [parsed (parsed-springrts-buildbot path)]
     (files parsed))))

(defn springfiles-maps
  [path]
  (files (parsed-springrts-buildbot path)))

(defn springfightclub-files
  ([]
   (springfightclub-files nil))
  ([path]
   (let [parsed (parsed-springfightclub path)]
     (files parsed))))


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
   (let [{:keys [os-name]} (fs/sys-data)
         platform (if (and (string/includes? os-name "Linux")
                           (not (fs/wsl?)))
                    "linux64"
                    "win32")]
     (engine-archive branch version platform)))
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

(defn engine-path
  "Returns the path to the Spring archive to download for this system."
  ([version]
   (engine-path (detect-engine-branch version) version))
  ([branch version]
   (when version
     (let [{:keys [os-name]} (fs/sys-data)
           platform (if (and (string/includes? os-name "Linux")
                             (not (fs/wsl?)))
                      "linux64"
                      "win32")]
       (str (first (string/split version #"\s"))
            "/" platform "/" (engine-archive branch version platform))))))


(defn engine-url
  "Returns the url for the archive for the given engine version."
  [engine-version]
  (when engine-version
    (let [engine-branch (detect-engine-branch engine-version)
          archive-path (engine-path engine-branch engine-version)]
      (str springrts-buildbot-root "/" engine-branch "/" archive-path))))


(defn map-url [map-name]
  (when map-name
    (str springfiles-maps-url "/" (fs/map-filename map-name))))
