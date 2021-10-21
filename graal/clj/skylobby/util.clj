(ns skylobby.util
  (:import
    (org.apache.commons.io FileUtils)))


(def app-name "skylobby")


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
  [{:keys [current total]}]
  (if (and current total)
    (str (format-bytes current)
         " / "
         (format-bytes total))
    "Downloading..."))
