(ns skylobby.util
  (:require
    [clojure.string :as string])
  (:import
    (java.net InetAddress NetworkInterface ServerSocket URL URLDecoder URLEncoder)
    (java.nio.charset StandardCharsets)
    (org.apache.commons.io FileUtils)))


(def app-name "skylobby")


(def default-history-index -1)


(defn curr-millis
  "Returns (System/currentTimeMillis)."
  []
  (System/currentTimeMillis))

; https://stackoverflow.com/a/50889042/984393
(defn str->bytes
  "Convert string to byte array."
  ([^String s]
   (str->bytes s "UTF-8"))
  ([^String s, ^String encoding]
   (.getBytes s encoding)))

(defn format-bytes
  "Returns a string of the given byte count in human readable format."
  [n]
  (when (number? n)
    (FileUtils/byteCountToDisplaySize (long n))))

(defn download-progress
  [{:keys [current done total]}]
  (if done
    (str (when total (format-bytes total)) " complete")
    (if (and current total)
      (str (format-bytes current)
           " / "
           (format-bytes total))
      "Downloading...")))


(defn decode [^String s]
  (URLDecoder/decode s (.name (StandardCharsets/UTF_8))))


(defn server-key [{:keys [server-url username]}]
  (str username "@" server-url))


(defn battle-channel-name [{:keys [battle-id channel-name]}]
  (or channel-name
      (str "__battle__" battle-id)))

(defn battle-channel-name? [channel-name]
  (and channel-name
       (string/starts-with? channel-name "__battle__")))

(defn visible-channel [{:keys [by-server selected-tab-channel selected-tab-main]} server-key]
  (let [main-tab (get selected-tab-main server-key)]
    (if (= "battle" main-tab)
      (when-let [battle-id (get-in by-server [server-key :battle :battle-id])]
        (battle-channel-name battle-id))
      (get selected-tab-channel server-key))))
