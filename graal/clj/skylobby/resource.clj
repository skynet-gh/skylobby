(ns skylobby.resource
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    [skylobby.rapid :as rapid]))


(set! *warn-on-reflection* true)


(defn resource-dest
  [root {:keys [resource-filename resource-file resource-type]}]
  (let [filename (or resource-filename
                     (fs/filename resource-file))]
    (case resource-type
      :spring-lobby/engine
      (cond
        (and resource-file (fs/exists? resource-file) (fs/is-directory? resource-file))
        (io/file (fs/engines-dir root) filename)
        filename (io/file (fs/download-dir) "engine" filename)
        ;resource-name (http/engine-download-file resource-name)
        :else nil)
      :spring-lobby/mod
      (when filename
        (if (string/ends-with? filename ".sdp")
          (rapid/sdp-file root filename)
          (fs/mod-file root filename)))
      :spring-lobby/map
      (when filename (io/file (fs/map-file root filename)))
      :spring-lobby/replay
      (when filename (io/file (fs/replays-dir root) filename))
      nil)))

(defn remove-v-before-number [s]
  (when s
    (string/replace s #"v(\d+)" "$1")))


(defn normalize-map [map-name-or-filename]
  (some-> map-name-or-filename
          string/lower-case
          (string/replace #"\s+" "_")
          (string/replace #"[-']" "_")
          (string/replace #"\.sd[7z]$" "")
          remove-v-before-number))

(defn could-be-this-map?
  "Returns true if this resource might be the map with the given name, by magic, false otherwise."
  [map-name {:keys [resource-filename resource-name]}]
  (and map-name
       (or (= map-name resource-name)
           (when (and map-name resource-filename)
             (= (normalize-map map-name)
                (normalize-map resource-filename))))))
