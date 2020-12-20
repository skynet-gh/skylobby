(ns spring-lobby.fs.sdfz
  "Parsing of Spring demo files aka replays."
  (:require
    [clojure.java.io :as io]
    [org.clojars.smee.binary.core :as b]
    [spring-lobby.fs :as fs])
  (:import
    (java.util.zip GZIPInputStream)))


(def replay-filename-re
  #"^(\d+)_(\d+)_(.*)_(.+).sdfz$")


; https://github.com/springlobby/springlobby/blob/master/src/replaylist.h
; https://github.com/spring/spring/blob/master/rts/System/LoadSave/demofile.h

(def sdfz-header
  (b/ordered-map
    :magic (b/string "ISO-8859-1" :length 16)
    :version :int-le
    :header-size :int-le
    :engine-version (b/string "ISO-8859-1" :length 16)
    :game-id (b/blob :length 16)
    :unix-time :ulong-le
    :script-size :int-le
    :demo-stream-size :int-le
    :game-time :int-le
    :wallclock-time :int-le
    :max-player-num :int-le
    :num-players :int-le
    :team-stat-size :int-le
    :team-stat-elim-size :int-le
    :team-stat-period :int-le
    :winning-ally-team :int-le))


(defn decode-replay-header [^java.io.File f]
  (with-open [is (io/input-stream f)
              gz (GZIPInputStream. is)]
    (->> gz
         (b/decode sdfz-header)
         (into (sorted-map)))))


(defn parse-replay-filename [^java.io.File f]
  (let [filename (fs/filename f)
        [_all timestamp game-id map-name engine-version] (re-find replay-filename-re filename)]
    {:timestamp timestamp
     :game-id game-id
     :map-name map-name
     :engine-version engine-version}))
