(ns spring-lobby.fs.sdfz
  "Parsing of Spring demo files aka replays."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [org.clojars.smee.binary.core :as b]
    [spring-lobby.fs :as fs]
    [spring-lobby.spring.script :as spring-script])
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
    :engine-version (b/string "ISO-8859-1" :length 256)
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

; https://github.com/spring/spring/blob/master/rts/System/LoadSave/demofile.h#L31-L42
(def sdfz-protocol
  (b/header
    sdfz-header
    (fn [{:keys [script-size]}]
      (b/ordered-map
        :something :int-le
        :something2 :int-le
        :script-txt (b/string "ISO-8859-1" :length script-size)))
    (constantly nil) ; TODO writing replays
    :keep-header? true))

(defn decode-replay-header [^java.io.File f]
  (with-open [is (io/input-stream f)
              gz (GZIPInputStream. is)]
    (->> gz
         (b/decode sdfz-header)
         (into (sorted-map)))))

(defn sanitize-replay-engine [replay-engine-version]
  (some-> replay-engine-version (string/replace #"\P{Print}" "") string/trim fs/sync-version-to-engine-version))

(defn decode-replay [^java.io.File f]
  (with-open [is (io/input-stream f)
              gz (GZIPInputStream. is)]
    (-> (->> (b/decode sdfz-protocol gz)
             (into (sorted-map)))
        (update :header
          (fn [header]
            (-> header
                (update :engine-version sanitize-replay-engine)
                (dissoc :game-id))))
        (update :body
          (fn [body]
            (-> body
                (assoc :script-data (spring-script/parse-script (:script-txt body)))
                (dissoc :script-txt)))))))


(defn parse-replay-filename [^java.io.File f]
  (let [filename (fs/filename f)
        [_all timestamp game-id map-name sync-version] (re-find replay-filename-re filename)]
    {:timestamp timestamp
     :game-id game-id
     :map-name map-name
     :engine-version (fs/sync-version-to-engine-version sync-version)}))
