(ns skylobby.rapid
  (:require
    [clojure.java.io :as io]))


(defn sdp-file
  [root sdp-filename]
  (when sdp-filename
    (io/file root "packages" sdp-filename)))
