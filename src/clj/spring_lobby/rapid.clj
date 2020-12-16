(ns spring-lobby.rapid
  "Utilities for interacting with rapid and its files."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [org.clojars.smee.binary.core :as b]
    [spring-lobby.fs :as fs]
    [spring-lobby.lua :as lua]
    [taoensso.timbre :as log])
  (:import
    (java.util.zip GZIPInputStream)))


(set! *warn-on-reflection* true)


; https://springrts.com/wiki/Rapid


(def md5-codec
  (b/compile-codec
    (b/blob :length 16)
    ;(b/repeated :ubyte :length 16)
    (fn [^String md5-string]
      (.toByteArray
        (java.math.BigInteger. md5-string 16))) ; TODO verify
    (fn [md5-bytes]
      (apply str (map (partial format "%02x") md5-bytes)))))


; https://springrts.com/wiki/Rapid#an_index_archive:_.sdp
(def sdp-line
  (b/header
    (b/ordered-map
      :filename-length :ubyte)
    (fn [{:keys [filename-length]}]
      (b/ordered-map
        :filename (b/string "ISO-8859-1" :length filename-length)
        :md5 md5-codec
        :crc32 :uint-le
        :file-size :uint-le))
    (constantly nil) ; TODO writing SDP files
    :keep-header? false))


(defn decode-sdp [^java.io.File f]
  (with-open [is (io/input-stream f)
              gz (GZIPInputStream. is)]
    {::source (.getAbsolutePath f)
     :items (b/decode (b/repeated sdp-line) gz)}))

(defn sdp-files
  ([]
   (sdp-files (fs/isolation-dir)))
  ([root]
   (log/debug "Loading sdp file names from" root)
   (let [packages-root (io/file root "packages")]
     (or
       (when (.exists packages-root)
         (seq (.listFiles packages-root)))
       []))))

(defn file-in-pool
  ([md5]
   (file-in-pool (fs/isolation-dir) md5))
  ([root md5]
   (let [pool-dir (subs md5 0 2)
         pool-file (str (subs md5 2) ".gz")]
     (io/file root "pool" pool-dir pool-file))))

(defn slurp-from-pool
  ([md5]
   (slurp-from-pool (fs/isolation-dir) md5))
  ([root md5]
   (let [f (file-in-pool root md5)]
     (with-open [is (io/input-stream f)
                 gz (GZIPInputStream. is)]
       (slurp gz)))))

; https://clojuredocs.org/clojure.core/slurp
(defn slurp-bytes
  "Slurp the bytes from a slurpable thing"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))

(defn slurp-bytes-from-pool
  ([md5]
   (slurp-bytes-from-pool (fs/isolation-dir) md5))
  ([root md5]
   (let [f (file-in-pool root md5)]
     (with-open [is (io/input-stream f)
                 gz (GZIPInputStream. is)]
       (slurp-bytes gz)))))

(defn inner
  ([decoded-sdp inner-filename]
   (inner (fs/isolation-dir) decoded-sdp inner-filename))
  ([root decoded-sdp inner-filename]
   (if-let [inner-details (->> decoded-sdp
                               :items
                               (filter (comp #{inner-filename} :filename))
                               first)]
     (assoc inner-details :content-bytes (slurp-bytes-from-pool root (:md5 inner-details)))
     (log/warn "No such inner rapid file"
               (pr-str {:package (::source decoded-sdp)
                        :inner-filename inner-filename})))))

(defn root-from-sdp
  "Returns the spring root for the given sdp file."
  [f]
  (when-let [parent (.getParentFile f)] ; packages dir
    (.getParentFile parent)))

(defn rapid-inner [sdp-file inner-filename]
  (let [root (root-from-sdp sdp-file)]
    (inner
      root
      (decode-sdp sdp-file)
      inner-filename)))

(defn sdp-hash [^java.io.File sdp-file]
  (-> (.getName sdp-file)
      (string/split #"\.")
      first))


(def repos-url "http://repos.springrts.com/repos.gz")


(defn repos
  ([]
   (repos (fs/isolation-dir)))
  ([root]
   (log/debug "Loading rapid repo names")
   (->> (.listFiles (io/file root "rapid" "repos.springrts.com"))
        seq
        (filter #(.isDirectory ^java.io.File %))
        (map #(.getName ^java.io.File %)))))

(defn rapid-versions [f]
  (with-open [is (io/input-stream f)
              gz (GZIPInputStream. is)]
    (->> gz
         slurp
         (string/split-lines)
         (map
           (fn [line]
             (let [[id commit detail version] (string/split line #",")]
               {:id id
                :hash commit
                :detail detail
                :version version}))))))


(defn package-versions
  ([]
   (package-versions (fs/isolation-dir)))
  ([root]
   (-> root
       (io/file "rapid" "packages.springrts.com" "versions.gz")
       (rapid-versions))))

#_
(def package-by-hash
  (or
    (try
      (->> (package-versions)
           (map (juxt :hash identity))
           (into {}))
      (catch Exception e
        (log/error e "Error loading Rapid package versions")))
    {}))


(defn versions [repo]
  (log/debug "Loading rapid versions for repo" repo)
  (-> (fs/spring-root)
      (io/file "rapid" "repos.springrts.com" repo "versions.gz")
      (rapid-versions)))


(defn- try-inner-lua
  [f filename]
  (try
    (when-let [inner (rapid-inner f filename)]
      (let [contents (slurp (:content-bytes inner))]
        (when-not (string/blank? contents)
          (lua/read-modinfo contents))))
    (catch Exception e
      (log/warn e "Error reading" filename "in" f))))

(defn read-sdp-mod
  ([^java.io.File f]
   (read-sdp-mod f nil))
  ([^java.io.File f {:keys [modinfo-only]}]
   (let [modinfo (try-inner-lua f "modinfo.lua")] ; TODO don't parse the .sdp over and over
     (merge
       {::fs/source :rapid
        :filename (.getName f)
        :absolute-path (.getAbsolutePath f)
        :modinfo modinfo}
       (when-not modinfo-only
         (when modinfo
           {:modoptions (try-inner-lua f "modoptions.lua")
            :engineoptions (try-inner-lua f "engineoptions.lua")
            :luaai (try-inner-lua f "luaai.lua")}))))))

(defn mods []
  (some->> (sdp-files)
           (map read-sdp-mod)
           (filter :modinfo)
           doall))
