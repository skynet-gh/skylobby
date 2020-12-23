(ns spring-lobby.util
  (:require
    [clojure.edn :as edn])
  (:import
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
