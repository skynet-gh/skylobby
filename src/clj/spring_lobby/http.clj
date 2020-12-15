(ns spring-lobby.http
  (:require
    [clj-http.client :as http]
    [clojure.core.memoize :as mem]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.xml :as xml]
    [crouton.html :as html]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
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

(def springlauncher-root
  "https://content.spring-launcher.com")


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


(defn springrts-engine-url
  "Returns the url for the archive for the given engine version on springrts."
  [engine-version]
  (when engine-version
    (let [engine-branch (detect-engine-branch engine-version)
          archive-path (engine-path engine-branch engine-version)]
      (str springrts-buildbot-root "/" engine-branch "/" archive-path))))


(defn map-url [map-name]
  (when map-name
    (str springfiles-maps-url "/" (fs/map-filename map-name))))


(defn get-springlauncher-root
  "Returns parsed XML string body from springlauncher root."
  []
  (log/info "GET" springlauncher-root)
  (->> (clj-http.client/get springlauncher-root {:as :stream})
       :body
       xml/parse
       :content
       (filter (comp #{:Contents} :tag))
       (mapcat
         (fn [{:keys [content]}]
           (->> content
                (filter (comp #{:Key} :tag))
                first
                :content)))))


(def springlauncher-links
  (mem/ttl (partial get-springlauncher-root) :ttl/threshold 3600000))

(defn springlauncher-engine-url [engine-version]
  (let [engine-archive (engine-archive engine-version)]
    (->> (springlauncher-links)
         (filter (comp #(clojure.string/starts-with? % "engines/")))
         (remove #{"engines/"})
         (filter (comp #(and % engine-archive
                             (clojure.string/ends-with? % engine-archive))))
         first
         (str springlauncher-root "/"))))

(defn engine-url
  "Returns the url for the archive for the given engine version."
  [engine-version]
  (or (springlauncher-engine-url engine-version)
      (springrts-engine-url engine-version)))

(defn engine-download-file [engine-version]
  (when engine-version
    (io/file (fs/download-dir) "engine" (engine-archive engine-version))))


(defn spring-resource? [filename]
  (and filename
    (or (string/ends-with? filename ".sdz")
        (string/ends-with? filename ".sd7"))))

(defn resource-type [url]
  (if (spring-resource? url)
    :spring-lobby/map ; TODO
    nil))

(defn html-downloadables
  [{:keys [url download-source-name]}]
  (let [base-url url
        now (u/curr-millis)]
    (->> base-url
         html/parse
         files
         (map
           (fn [{:keys [filename url date size]}]
             {:download-url (str base-url "/" url)
              :download-source-name download-source-name
              :resource-filename filename
              :resource-type (resource-type url)
              :resource-size size
              :resource-date date
              :resource-updated now})))))
