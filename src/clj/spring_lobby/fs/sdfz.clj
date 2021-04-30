(ns spring-lobby.fs.sdfz
  "Parsing of Spring demo files aka replays."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [org.clojars.smee.binary.core :as b]
    [spring-lobby.fs :as fs]
    [spring-lobby.spring.script :as spring-script]
    [taoensso.timbre :as log])
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


(defn replay-game-type [allyteam-counts]
  (let [one-per-allyteam? (= #{1} (set allyteam-counts))
        num-allyteams (count allyteam-counts)]
    (cond
      (= 2 num-allyteams)
      (if one-per-allyteam?
        :duel
        :team)
      (< 2 num-allyteams)
      (if one-per-allyteam?
        :ffa
        :teamffa)
      :else
      :invalid)))

(defn- replay-type-and-players [parsed-replay]
  (let [teams (->> parsed-replay
                   :body
                   :script-data
                   :game
                   (filter (comp #(string/starts-with? % "team") name first)))
        teams-by-allyteam (->> teams
                               (group-by (comp keyword (partial str "allyteam") :allyteam second)))
        allyteam-counts (sort (map (comp count second) teams-by-allyteam))]
    {:game-type (replay-game-type allyteam-counts)
     :player-counts allyteam-counts}))

(defn parse-replay [^java.io.File f]
  (let [replay (try
                 (decode-replay f)
                 (catch Exception e
                   (log/error e "Error reading replay" f)))]
    (merge
      {:file f
       :filename (fs/filename f)
       :file-size (fs/size f)}
      replay
      (replay-type-and-players replay))))
