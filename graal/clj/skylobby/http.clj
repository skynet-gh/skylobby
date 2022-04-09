(ns skylobby.http
  (:require
    [clj-http.client :as http]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.xml :as xml]
    [crouton.html :as html]
    [me.raynes.fs :as raynes-fs]
    [skylobby.fs :as fs]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (org.apache.commons.io.input CountingInputStream)))


(set! *warn-on-reflection* true)


(def progress-update-frequency-millis 1000)
(def last-progress-update
  (atom {}))


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

(def ba-github-releases-url
  "https://api.github.com/repos/Balanced-Annihilation/Balanced-Annihilation/releases")

(def bar-spring-releases-url
  "https://api.github.com/repos/beyond-all-reason/spring/releases")

(def bar-maps-github-releases-url
  "https://api.github.com/repos/beyond-all-reason/Maps/releases")

(def mf-github-releases-url
  "https://api.github.com/repos/springraaar/metal_factions/releases")

(def tap-maps-github-releases-url
  "https://api.github.com/repos/FluidPlay/TAPrime-maps/releases")

(def evo-rts-github-releases-url
  "https://api.github.com/repos/EvolutionRTS/Evolution-RTS/releases")

(def tap-github-releases-url
  "https://api.github.com/repos/FluidPlay/TAPrime_v2/releases")

(def bar-replays-api-url
  "https://bar-rts.com/api/replays/")




; https://github.com/dakrone/clj-http/pull/220/files

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
  (swap! state-atom update-in [:http-download url]
    (fn [data]
      (-> data
          (assoc :running true)
          (update :tries (fnil inc 0)))))
  (log/info "Request to download" url "to" dest-file)
  (try
    (fs/make-parent-dirs dest-file)
    (http/with-middleware
      (-> http/default-middleware
          (insert-after http/wrap-url wrap-downloaded-bytes-counter)
          (conj http/wrap-lower-case-headers))
      (let [response (http/get url
                               {:as :stream
                                :conn-timeout 10000
                                :socket-timeout 10000})
            ^String content-length (get-in response [:headers "content-length"] "0")
            length (Integer/valueOf content-length)
            buffer-size (* 1024 10)]
        (swap! state-atom update-in [:http-download url]
               merge
               {:current 0
                :total length})
        (with-open [^java.io.InputStream input (:body response)
                    output (io/output-stream dest-file)]
          (let [buffer (make-array Byte/TYPE buffer-size)
                ^CountingInputStream counter (:downloaded-bytes-counter response)]
            (loop []
              (let [size (.read input buffer)]
                (when (pos? size)
                  (.write output buffer 0 size)
                  (when counter
                    (try
                      (let [last-updated (get @last-progress-update url)
                            now (u/curr-millis)]
                        (when (or (not last-updated)
                                  (< progress-update-frequency-millis (- now last-updated)))
                          (swap! last-progress-update assoc url now)
                          (swap! state-atom update-in [:http-download url]
                                 merge
                                 {:current (.getByteCount counter)
                                  :total length})))
                      (catch Exception e
                        (log/warn e "Error updating download status"))))
                  (recur))))
            (swap! state-atom assoc-in [:http-download url :done] true)))))
    (catch Exception e
      (log/error e "Error downloading" url "to" dest-file)
      (raynes-fs/delete dest-file))
    (finally
      (swap! state-atom assoc-in [:http-download url :running] false)
      (fs/update-file-cache! state-atom dest-file)
      (log/info "Finished downloading" url "to" dest-file))))

(defn filename [url]
  (when url
    (try
      (let [uri (java.net.URI. url)]
        (last (string/split (.getPath uri) #"/")))
      (catch Exception e
        (log/error e "Error parsing url" url)
        (last (string/split url #"/"))))))

(defn bar-replay-download-url [filename-or-id]
  (str bar-replays-api-url filename-or-id))

(defn seven-zip? [filename]
  (string/ends-with? filename ".7z"))

(defn spring-resource? [filename]
  (and filename
    (or (string/ends-with? filename ".sdz")
        (string/ends-with? filename ".sd7"))))

(defn guess-resource-type [url]
  (cond
    (spring-resource? url) :spring-lobby/map
    (seven-zip? url) :spring-lobby/engine
    :else
    nil))

(defn maps-only [url]
  (when (and url (spring-resource? url))
    :spring-lobby/map))

(defn mods-only [url]
  (when (and url (spring-resource? url))
    :spring-lobby/mod))

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


(def bar-engine-re
  #"^spring_bar_\.BAR\.([^_]*)_([0-9a-z\-]+)\.7z$")
(def bar-105-engine-re
  #"^spring_bar_\.BAR105\.([^_]*)_([0-9a-z\-]+)\.7z$")

(defn bar-engine-filename?
  [filename]
  (boolean
    (when filename
      (or
        (re-find bar-engine-re filename)
        (re-find bar-105-engine-re filename)))))

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
                :resource-type (when (and filename resource-type-fn)
                                 (resource-type-fn filename))
                :resource-date created-at
                :download-source-name download-source-name
                :resource-updated now}))))))

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

(def evo-rts-re
  #"^Evolution-RTS\-?v([0-9a-z\.]+)\.sdz$")

(defn evo-rts-filename?
  [filename]
  (boolean
    (re-find evo-rts-re filename)))

(defn get-evo-rts-github-release-downloadables
  [{:keys [download-source-name] :as source}]
  (concat
    (get-github-release-downloadables source)
    [{:resource-type :spring-lobby/mod
      :resource-name "Evolution RTS Music Addon v2"
      :download-url "https://github.com/EvolutionRTS/Evolution-RTS/releases/download/v16.00/Evolution-RTSMusicAddon.sdz"
      :resource-filename "Evolution-RTSMusicAddon.sdz"
      :download-source-name download-source-name}]))

(defn- tag-content
  "Returns the content for the first matching tag (for parsed XML)."
  [content tag]
  (some->> content
           (filter (comp #{tag} :tag))
           first
           :content
           first))

(defn springlauncher-resource-type [url]
  (cond
    (not url) nil
    (and (string/starts-with? url "maps/")
         (spring-resource? url))
    :spring-lobby/map
    :else nil))

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

(defn- urls [files]
  (->> files
       (map :url)
       (filter some?)))

(defn- link? [url]
  (string/ends-with? url "/"))


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

(def engine-archive-re
  #"^spring_(\{\w+\})?([^_]*)_([0-9a-z\-]+)\.7z$")

(defn engine-archive?
  "Returns true if the given filename appears to be a spring engine archive, false otherwise."
  [filename]
  (boolean
    (re-find engine-archive-re filename)))

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


(defn get-bar-replays
  [{:keys [page]}]
  (->> (http/get bar-replays-api-url
         (merge
           {:as :auto}
           (when page
             {:query-params {:page page}})))
       :body
       :data))

(defn get-bar-replay-details
  [{:keys [id]}]
  (:body
    (http/get (bar-replay-download-url id) {:as :auto})))


(defn springfiles-url [springfiles-search-result]
  (let [{:keys [mirrors]} springfiles-search-result]
    (rand-nth mirrors)))



(def springfiles-maps-download-source
  {:download-source-name "SpringFiles Maps"
   :url springfiles-maps-url
   :resources-fn (partial html-downloadables maps-only)})
(def hakora-maps-download-source
  {:download-source-name "Hakora Maps"
   :url "http://www.hakora.xyz/files/springrts/maps"
   :resources-fn (partial html-downloadables maps-only)})
(def download-sources
  [;springfiles-maps-download-source gone now
   hakora-maps-download-source
   {:download-source-name "Balanced Annihilation GitHub releases"
    :url ba-github-releases-url
    :browse-url "https://github.com/Balanced-Annihilation/Balanced-Annihilation/releases"
    :resources-fn get-github-release-downloadables
    :resource-type-fn mods-only}
   {:download-source-name "BAR GitHub spring"
    :url bar-spring-releases-url
    :browse-url "https://github.com/beyond-all-reason/spring/releases"
    :resources-fn get-github-release-engine-downloadables}
   {:download-source-name "BAR GitHub maps"
    :url bar-maps-github-releases-url
    :browse-url "https://github.com/beyond-all-reason/Maps/releases"
    :resources-fn get-bar-maps-github-release-downloadables}
   {:download-source-name "Evolution-RTS GitHub releases"
    :url evo-rts-github-releases-url
    :browse-url "https://github.com/EvolutionRTS/Evolution-RTS/releases"
    :resources-fn get-evo-rts-github-release-downloadables
    :resource-type-fn mods-only}
   {:download-source-name "Metal Factions GitHub releases"
    :url mf-github-releases-url
    :browse-url "https://github.com/springraaar/metal_factions/releases"
    :resources-fn get-github-release-downloadables
    :resource-type-fn mods-only}
   {:download-source-name "SpringFightClub Maps"
    :url (str springfightclub-root "/maps")
    :resources-fn (partial html-downloadables maps-only)}
   {:download-source-name "SpringFightClub Games"
    :url springfightclub-root
    :resources-fn (partial html-downloadables mods-only)}
   {:download-source-name "SpringLauncher"
    :url springlauncher-root
    :resources-fn get-springlauncher-downloadables}
   {:download-source-name "SpringRTS buildbot"
    :url springrts-buildbot-root
    :resources-fn crawl-springrts-engine-downloadables}
   {:download-source-name "TAP GitHub releases"
    :url tap-github-releases-url
    :browse-url "https://github.com/FluidPlay/TAPrime_v2/releases"
    :resources-fn get-github-release-downloadables
    :resource-type-fn mods-only}
   {:download-source-name "TAP GitHub maps"
    :url tap-maps-github-releases-url
    :browse-url "https://github.com/FluidPlay/TAPrime-maps/releases"
    :resources-fn get-bar-maps-github-release-downloadables}])

(def download-sources-by-name
  (into {}
    (map (juxt :download-source-name identity) download-sources)))
