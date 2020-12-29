(ns spring-lobby.util
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [taoensso.timbre :as timbre]
    [taoensso.timbre.appenders.3rd-party.rotor :as rotor])
  (:import
    (java.net URLDecoder)
    (java.nio.charset StandardCharsets)
    (org.apache.commons.io FileUtils)))


(set! *warn-on-reflection* true)


; https://stackoverflow.com/a/17328219/984393
(defn deep-merge [& ms]
  (apply
    merge-with
    (fn [x y]
      (cond (map? y) (deep-merge x y)
            (vector? y) (concat x y)
            :else y))
    ms))

(defn to-number
  "Returns a nilable number from the given number or reads it from the given string."
  [string-or-number]
  (cond
    (number? string-or-number)
    string-or-number
    (string? string-or-number)
    (edn/read-string string-or-number)
    :else
    nil))

(defn to-bool [v]
  (cond
    (boolean? v) v
    (number? v) (not (zero? v))
    (string? v) (recur (to-number v))
    :else
    (boolean v)))


(defn curr-millis
  "Returns (System/currentTimeMillis)."
  []
  (System/currentTimeMillis))


(defn random-color
  []
  (long (rand (* 255 255 255))))


(defn format-bytes
  "Returns a string of the given byte count in human readable format."
  [n]
  (when (number? n)
    (FileUtils/byteCountToDisplaySize (long n))))


(defn decode [^String s]
  (URLDecoder/decode s (.name (StandardCharsets/UTF_8))))


(defn log-to-file [log-path]
  (println "Setting up log to" log-path)
  (timbre/merge-config!
    {:appenders
     {:rotor (rotor/rotor-appender
               {:path log-path
                :max-size 100000000
                :backlog 9
                :stacktrace-fonts {}})}}))


(defmacro try-log
  "Log message, try an operation, log any Exceptions with message, and log and rethrow Throwables."
  [message & body]
  `(try
     (log/info (str "Start " ~message))
     ~@body
     (catch Exception e#
       (log/error e# (str "Exception " ~message)))
     (catch Throwable t#
       (log/error t# (str "Error " ~message))
       (throw t#))))

; https://clojuredocs.org/clojure.core/slurp
(defn slurp-bytes
  "Slurp the bytes from a slurpable thing"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (io/copy (io/input-stream x) out)
    (.toByteArray out)))
