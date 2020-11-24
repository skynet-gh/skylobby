(ns spring-lobby.rapid
  "Utilities for interacting with rapid and its files."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [org.clojars.smee.binary.core :as b]
    [spring-lobby.fs :as fs]
    [taoensso.timbre :as log])
  (:import
    (java.util.zip GZIPInputStream)))


; https://springrts.com/wiki/Rapid


(def md5-codec
  (b/compile-codec
    (b/blob :length 16)
    ;(b/repeated :ubyte :length 16)
    (fn [md5-string]
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
        ;:filename (b/blob :length filename-length)
        :filename (b/string "ISO-8859-1" :length filename-length)
        :md5 md5-codec
        ;:md5 (b/string "UTF-8" :length 16)
        :crc32 :uint-le ; (b/string "ISO-8859-1" :length 4)
        :file-size :uint-le))
    (constantly nil) ; TODO writing SDP files
    :keep-header? false))


(defn decode-sdp [f]
  (with-open [is (io/input-stream f)
              gz (GZIPInputStream. is)]
    {::source (.getAbsolutePath f)
     :items (b/decode (b/repeated sdp-line) gz)}))

(defn sdp-files []
  (log/debug "Loading sdp file names")
  (let [packages-root (io/file (fs/spring-root) "packages")]
    (or
      (when (.exists packages-root)
        (seq (.listFiles packages-root)))
      [])))

(defn slurp-from-pool [md5]
  (let [pool-dir (subs md5 0 2)
        pool-file (str (subs md5 2) ".gz")
        f (io/file (fs/spring-root) "pool" pool-dir pool-file)]
    (with-open [is (io/input-stream f)
                gz (GZIPInputStream. is)]
      (slurp gz))))

(defn inner [decoded-sdp inner-filename]
  (if-let [inner-details (->> decoded-sdp
                              :items
                              (filter (comp #{inner-filename} :filename))
                              first)]
    (assoc inner-details :contents (slurp-from-pool (:md5 inner-details)))
    (throw (ex-info "No such inner rapid file" {:package (::source decode-sdp)
                                                :inner-filename inner-filename}))))

(defn rapid-inner [sdp-file inner-filename]
  (inner (decode-sdp sdp-file) inner-filename))

(defn sdp-hash [sdp-file]
  (-> (.getName sdp-file)
      (string/split #"\.")
      first))


(def repos-url "http://repos.springrts.com/repos.gz")


(defn repos []
  (log/debug "Loading rapid repo names")
  (->> (.listFiles (io/file (fs/spring-root) "rapid" "repos.springrts.com"))
       seq
       (filter #(.isDirectory %))
       (map #(.getName %))))

#_
(repos)

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


(defn package-versions []
  (-> (fs/spring-root)
      (io/file "rapid" "packages.springrts.com" "versions.gz")
      (rapid-versions)))

(def package-by-hash
  (or
    (try
      (->> (package-versions)
           (map (juxt :hash identity))
           (into {}))
      (catch Exception e
        (log/error e "Error loading Rapid package versions")))
    {}))

#_
(take 10 package-by-hash)


(defn versions [repo]
  (log/debug "Loading rapid versions for repo" repo)
  (-> (fs/spring-root)
      (io/file "rapid" "repos.springrts.com" repo "versions.gz")
      (rapid-versions)))

#_
(take 10 (versions "ba"))

(def all-versions
  (mapcat versions (repos)))

(def versions-by-hash
  (->> all-versions
       (map (juxt :hash identity))
       (into {})))

#_
(take 10 (ba-versions))

#_
(let [sdps (sdp-files)
      one (->> sdps
               (filter #(string/starts-with? (.getName %) "e9"))
               first)
      decoded (decode-sdp one)]
  (println (::source decoded))
  (rapid-inner one "modinfo.lua")
  #_
  (->> (decode-sdp one)
       :items
       (filter (comp #(string/starts-with? % "mod") :filename))
       #_
       (fn [{:keys [body]}]
         (let [{:keys [md5]} body]
           {:raw md5
            :hex (format "%16x" (java.math.BigInteger. 1 md5))})))
       ;:md5
       ;detect)
  #_
  (b/decode sdp-line (io/input-stream one))
  #_
  (->> (io/input-stream one)
       (b/decode :ubyte)
       #_(b/decode sdp-line))
  #_
  (->> sdps
       (map
         (fn [f]
           [(.getName f)
            (decode-sdp (io/input-stream f))]))
       (into {})))
