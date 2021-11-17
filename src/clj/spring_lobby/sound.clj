(ns spring-lobby.sound
  (:require
    [clojure.java.io :as io]
    [skylobby.fs :as fs]
    [taoensso.timbre :as log])
  (:import
    (javafx.scene.media Media MediaPlayer)))


(set! *warn-on-reflection* true)


(def ring-resource-name
  "186669__fordps3__computer-boop.wav")


(defn play-ring
  [{:keys [ring-sound-file ring-volume use-default-ring-sound]}]
  (future
    (try
      (let [ring-uri (if (or use-default-ring-sound (not ring-sound-file))
                       (str (io/resource ring-resource-name))
                       (some-> ring-sound-file fs/file .toURI str))
            media (Media. ring-uri)
            media-player (MediaPlayer. media)]
        (.setVolume media-player (or ring-volume 1.0))
        (.play media-player))
      (catch Exception e
        (log/error e "Error playing ring")))))
