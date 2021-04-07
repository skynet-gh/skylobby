(ns spring-lobby.sound
  (:require
    [clojure.java.io :as io])
  (:import
    (javafx.scene.media Media MediaPlayer)))


(def ring-resource-name
  "186669__fordps3__computer-boop.wav")


(defn play-ring []
  (let [media (Media. (str (io/resource ring-resource-name)))
        media-player (MediaPlayer. media)]
    (.play media-player)))
