(ns spring-lobby.http
  "Resources for "
  (:require
    [clj-http.client :as http]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.xml :as xml]
    [crouton.html :as html]
    [diehard.core :as dh]
    [me.raynes.fs :as raynes-fs]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (org.apache.commons.io.input CountingInputStream)))


(set! *warn-on-reflection* true)


(declare limit-download-status)


(dh/defratelimiter limit-download-status {:rate 1}) ; one update per second


(def default-engine-branch "master")
(def engine-branches
  ["master" "maintenance" "develop"])  ; TODO maybe auto detect these

(def springrts-buildbot-root
  "https://springrts.com/dl/buildbot/default")

(def old-springfiles-maps-url
  "http://api.springfiles.com/files/maps")

(def springfiles-maps-url
  "http://springfiles.springrts.com/files/maps")

(def springfightclub-root
  "https://www.springfightclub.com/data")

(def springlauncher-root
  "https://content.spring-launcher.com")

(def bar-spring-releases-url
  "https://api.github.com/repos/beyond-all-reason/spring/releases")

(def bar-maps-github-releases-url
  "https://api.github.com/repos/beyond-all-reason/Maps/releases")

(def tap-maps-github-releases-url
  "https://api.github.com/repos/FluidPlay/TAPrime-maps/releases")

(def evo-rts-github-releases-url
  "https://api.github.com/repos/EvolutionRTS/Evolution-RTS/releases")

(def tap-github-releases-url
  "https://api.github.com/repos/FluidPlay/TAPrime_v2/releases")

(def bar-replays-api-url
  "https://bar-rts.com/api/replays/")


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

(def bar-platforms
  {"linux64" "linux-64"
   "win32" "windows-64"
   "win64" "windows-64"})

(defn bar-engine-filename
  ([version]
   (bar-engine-filename version (fs/platform)))
  ([version platform]
   (let [bar-platform (get bar-platforms platform)]
     (str "spring_bar_.BAR." (first (string/split version #"\s"))
          "_" bar-platform "-minimal-portable.7z"))))


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

(def evo-rts-re
  #"^Evolution-RTSv([0-9a-z]+)\.sdz$")

(defn evo-rts-filename?
  [filename]
  (boolean
    (re-find evo-rts-re filename)))

(defn get-github-release-downloadables
  [{:keys [download-source-name resource-type-fn url]}]
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
                :resource-type (when resource-type-fn
                                 (resource-type-fn filename))
                :resource-date created-at
                :download-source-name download-source-name
                :resource-updated now}))))))

(defn get-evo-rts-github-release-downloadables
  [{:keys [download-source-name] :as source}]
  (concat
    (get-github-release-downloadables source)
    [{:resource-type :spring-lobby/mod
      :resource-name "Evolution RTS Music Addon v2"
      :download-url "https://github.com/EvolutionRTS/Evolution-RTS/releases/download/v16.00/Evolution-RTSMusicAddon.sdz"
      :resource-filename "Evolution-RTSMusicAddon.sdz"
      :download-source-name download-source-name}]))

(defn get-bar-maps-github-release-downloadables
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
                :resource-type (when (fs/spring-archive? filename)
                                 :spring-lobby/map)
                :resource-date created-at
                :download-source-name download-source-name
                :resource-updated now}))))))


(defn get-bar-replays
  [{:keys [page]}]
  (->> (http/get bar-replays-api-url
         (merge
           {:as :auto}
           (when page
             {:query-params {:page page}})))
       :body
       :data))

(defn bar-replay-download-url [filename-or-id]
  (str bar-replays-api-url filename-or-id))

(defn get-bar-replay-details
  [{:keys [id]}]
  (:body
    (http/get (bar-replay-download-url id) {:as :auto})))

; https://github.com/dakrone/clj-http/pull/220/files
(defn- print-progress-bar
  "Render a simple progress bar given the progress and total. If the total is zero
   the progress will run as indeterminated."
  ([progress total] (print-progress-bar progress total {}))
  ([progress total {:keys [bar-width]
                    :or   {bar-width 10}}]
   (if (pos? total)
     (let [pct (/ progress total)
           render-bar (fn []
                        (let [bars (Math/floor (* pct bar-width))
                              pad (- bar-width bars)]
                          (str (clojure.string/join (repeat bars "="))
                               (clojure.string/join (repeat pad " ")))))]
       (print (str "[" (render-bar) "] "
                   (int (* pct 100)) "% "
                   progress "/" total)))
     (let [render-bar (fn [] (clojure.string/join (repeat bar-width "-")))]
       (print (str "[" (render-bar) "] "
                   progress "/?"))))))

(defn- insert-at
  "Addes value into a vector at an specific index."
  [v idx value]
  (-> (subvec v 0 idx)
      (conj value)
      (into (subvec v idx))))

(defn- insert-after
  "Finds an item into a vector and adds val just after it.
   If needle is not found, the input vector will be returned."
  [^clojure.lang.APersistentVector v needle value]
  (let [index (.indexOf v needle)]
    (if (neg? index)
      v
      (insert-at v (inc index) value))))

; https://github.com/dakrone/clj-http/blob/3.x/examples/progress_download.clj
(defn- wrap-downloaded-bytes-counter
  "Middleware that provides an CountingInputStream wrapping the stream output"
  [http-client]
  (fn [req]
    (let [resp (http-client req)
          counter (CountingInputStream. (:body resp))]
      (merge resp {:body                     counter
                   :downloaded-bytes-counter counter}))))

(defn download-file [state-atom url dest-file]
  (swap! state-atom assoc-in [:http-download url] {:running true})
  (log/info "Request to download" url "to" dest-file)
  (future
    (try
      (fs/make-parent-dirs dest-file)
      (http/with-middleware
        (-> http/default-middleware
            (insert-after http/wrap-url wrap-downloaded-bytes-counter)
            (conj http/wrap-lower-case-headers))
        (let [request (http/get url {:as :stream})
              ^String content-length (get-in request [:headers "content-length"] "0")
              length (Integer/valueOf content-length)
              buffer-size (* 1024 10)]
          (swap! state-atom update-in [:http-download url]
                 merge
                 {:current 0
                  :total length})
          (with-open [^java.io.InputStream input (:body request)
                      output (io/output-stream dest-file)]
            (let [buffer (make-array Byte/TYPE buffer-size)
                  ^CountingInputStream counter (:downloaded-bytes-counter request)]
              (loop []
                (let [size (.read input buffer)]
                  (when (pos? size)
                    (.write output buffer 0 size)
                    (when counter
                      (try
                        (dh/with-rate-limiter {:ratelimiter limit-download-status
                                               :max-wait-ms 0}
                          (let [current (.getByteCount counter)]
                            (swap! state-atom update-in [:http-download url]
                                   merge
                                   {:current current
                                    :total length})))
                        (catch Exception e
                          (when-not (:throttled (ex-data e))
                            (log/warn e "Error updating download status")))))
                    (recur))))))))
      (catch Exception e
        (log/error e "Error downloading" url "to" dest-file)
        (raynes-fs/delete dest-file))
      (finally
        (swap! state-atom assoc-in [:http-download url :running] false)
        (fs/update-file-cache! state-atom dest-file)
        (log/info "Finished downloading" url "to" dest-file)))))
