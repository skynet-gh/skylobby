(ns spring-lobby.http
  "Resources for "
  (:require
    [clj-http.client :as http]
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

(def bar-spring-releases-url
  "https://api.github.com/repos/beyond-all-reason/spring/releases")


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

(def engine-archive-re
  #"^spring_(\{\w+\})?([^_]*)_([0-9a-z\-]+)\.7z$")

(defn engine-archive?
  "Returns true if the given filename appears to be a spring engine archive, false otherwise."
  [filename]
  (boolean
    (re-find engine-archive-re filename)))


(defn engine-path
  "Returns the path to the Spring archive to download for this system."
  ([version]
   (engine-path (detect-engine-branch version) version))
  ([branch version]
   (engine-path branch version (fs/platform)))
  ([branch version platform]
   (when version
     (str (first (string/split version #"\s"))
          "/" platform "/" (engine-archive branch version platform)))))


(defn springrts-engine-url
  "Returns the url for the archive for the given engine version on springrts."
  [engine-version]
  (when engine-version
    (let [engine-branch (detect-engine-branch engine-version)
          archive-path (engine-path engine-branch engine-version)]
      (str springrts-buildbot-root "/" engine-branch "/" archive-path))))


(defn- tag-content
  "Returns the content for the first matching tag (for parsed XML)."
  [content tag]
  (some->> content
           (filter (comp #{tag} :tag))
           first
           :content
           first))

(defn seven-zip? [filename]
  (string/ends-with? filename ".7z"))

(defn spring-resource? [filename]
  (and filename
    (or (string/ends-with? filename ".sdz")
        (string/ends-with? filename ".sd7"))))

(defn springlauncher-resource-type [url]
  (cond
    (not url) nil
    (and (string/starts-with? url "engines/")
         (seven-zip? url))
    :spring-lobby/engine
    (and (string/starts-with? url "maps/")
         (spring-resource? url))
    :spring-lobby/map
    :else nil))

(defn filename [url]
  (when url
    (last (string/split url #"/"))))

(defn get-springlauncher-downloadables
  "Returns parsed downloadable resources from the XML at springlauncher root."
  ([{:keys [url download-source-name]}]
   (let [base-url url
         now (u/curr-millis)]
     (log/info "GET" base-url)
     (->> (http/get base-url {:as :stream})
          :body
          xml/parse
          :content
          (filter (comp #{:Contents} :tag))
          (map
            (fn [{:keys [content]}]
              (let [path (tag-content content :Key)
                    url (str base-url "/" path)]
                {:resource-type (springlauncher-resource-type path)
                 :download-url url
                 :resource-filename (filename url)
                 :resource-date (tag-content content :LastModified)
                 :download-source-name download-source-name
                 :resource-updated now})))
          (filter :resource-type)))))

(defn engine-download-file [engine-version]
  (when engine-version
    (io/file (fs/download-dir) "engine" (engine-archive engine-version))))

(defn guess-resource-type [url]
  (cond
    (spring-resource? url) :spring-lobby/map ; TODO mods?
    (seven-zip? url) :spring-lobby/engine ; TODO
    :else
    nil))

(defn html-downloadables
  ([downloadable]
   (html-downloadables guess-resource-type downloadable))
  ([resource-type-fn {:keys [url download-source-name]}]
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
               :resource-type (resource-type-fn url)
               :resource-size size
               :resource-date date
               :resource-updated now}))))))


(defn- urls [files]
  (->> files
       (map :url)
       (filter some?)))

(defn- link? [url]
  (string/ends-with? url "/"))

(defn crawl-springrts-engine-downloadables
  [{:keys [download-source-name url]}]
  (let [base-url url
        branches (->> (springrts-buildbot-files)
                      urls
                      (filter link?))
        now (u/curr-millis)]
    (mapcat
      (fn [branch]
        (let [versions (->> (springrts-buildbot-files [branch])
                            urls
                            (filter link?)
                            (remove #(string/starts-with? % "LATEST")))]
           (mapcat
             (fn [version]
               (let [platforms (->> (springrts-buildbot-files [(str branch version)])
                                    urls
                                    (filter link?))]
                 (mapcat
                   (fn [platform]
                     (let [files (->> (springrts-buildbot-files [(str branch version platform)])
                                      (remove link?)
                                      (filter (every-pred :filename :url))
                                      (filter (comp engine-archive? :filename)))]
                       (map
                         (fn [{:keys [filename url size date]}]
                           {:download-source-name download-source-name
                            :download-url (str base-url "/" branch version platform url)
                            :resource-date date
                            :resource-filename filename
                            :resource-size size
                            :resource-type :spring-lobby/engine
                            :resource-updated now})
                         files)))
                   platforms)))
             versions)))
      branches)))


(def bar-engine-re
  #"^spring_bar_\.BAR\.([^_]*)_([0-9a-z\-]+)\.7z$")

(defn bar-engine-filename?
  [filename]
  (boolean
    (re-find bar-engine-re filename)))


(defn get-github-release-engine-downloadables
  [{:keys [download-source-name url]}]
  (let [now (u/curr-millis)]
    (->> (http/get url {:as :auto})
         :body
         (mapcat
           (fn [{:keys [assets html_url]}]
             (map
               (fn [{:keys [browser_download_url created_at]}]
                 {:release-url html_url
                  :asset-url browser_download_url
                  :created-at created_at})
               assets)))
         (map
           (fn [{:keys [asset-url created-at]}]
             (let [decoded-url (u/decode asset-url)
                   filename (filename decoded-url)]
               {:download-url asset-url
                :resource-filename filename
                :resource-type (when (bar-engine-filename? filename)
                                 :spring-lobby/engine)
                :resource-date created-at
                :download-source-name download-source-name
                :resource-updated now}))))))
