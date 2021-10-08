(ns spring-lobby.fs.sdfz
  "Parsing of Spring demo files aka replays."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [org.clojars.smee.binary.core :as b]
    [spring-lobby.fs :as fs]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.util.zip GZIPInputStream)))


(set! *warn-on-reflection* true)


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
    (fn [{:keys [demo-stream-size script-size]}]
      (b/ordered-map
        :something :int-le
        :something2 :int-le
        :script-txt (b/string "ISO-8859-1" :length script-size)
        :demo-stream (b/blob :length demo-stream-size)))
    (constantly nil) ; TODO writing replays
    :keep-header? true))


; https://github.com/spring/spring/blob/develop/rts/Net/Protocol/NetMessageTypes.h
(def net-message-header
  (b/ordered-map
    :command :byte))

(defn net-message-protocol [length]
  (b/header
    net-message-header
    (fn [{:keys [command]}]
      (let [l (dec length)]
        (case (int command)
          6 (b/ordered-map
              :pad :byte
              :player-num :ubyte
              :player-name (b/string "ISO-8859-1" :length (- length 3)))
          7 (b/ordered-map
              :pad :byte
              :from :ubyte
              :dest :ubyte
              :message (b/string "ISO-8859-1" :length (- length 4)))
          30 (b/ordered-map
               :pad :byte
               :player-num :ubyte
               :winning-ally-teams (b/repeated :ubyte :length (- length 3)))
          36 (b/ordered-map
               :player-num :ubyte
               :my-team :ubyte
               :ready :ubyte
               :x :float-le
               :y :float-le
               :z :float-le)
          (b/blob :length l))))
    (constantly nil) ; TODO writing replays
    :keep-header? true))


; https://github.com/beyond-all-reason/spring/blob/da07d8db67269bf74fb68856431c1a18e7d66600/rts/System/LoadSave/demofile.h#L98-L103
(def demo-stream-chunk-header
  (b/ordered-map
    :mod-game-time :float-le
    :length :uint-le))

(def demo-stream-chunk-protocol
  (b/header
    demo-stream-chunk-header
    (fn [{:keys [length]}]
      (b/ordered-map
        :demo-stream-chunk (net-message-protocol length)))
    (constantly nil) ; TODO writing replays
    :keep-header? true))


(defn decode-replay-header [^java.io.File f]
  (with-open [is (io/input-stream f)
              gz (GZIPInputStream. is)]
    (->> gz
         (b/decode sdfz-header)
         (into (sorted-map)))))

(defn sanitize-replay-engine [replay-engine-version]
  (some-> replay-engine-version u/remove-nonprintable string/trim fs/sync-version-to-engine-version))

(defn decode-replay
  ([^java.io.File f]
   (decode-replay f nil))
  ([^java.io.File f {:keys [parse-stream]}]
   (with-open [is (io/input-stream f)
               gz (GZIPInputStream. is)]
     (-> (->> (b/decode sdfz-protocol gz)
              (into (sorted-map)))
         (update :header
           (fn [header]
             (-> header
                 (update :engine-version sanitize-replay-engine)
                 (update :game-id u/bytes->hex))))
         (update :body
           (fn [body]
             (-> body
                 (assoc :script-data (spring-script/parse-script (:script-txt body)))
                 (dissoc :script-txt)
                 (update :demo-stream
                   (fn [demo-stream-raw]
                     (if parse-stream
                       (try
                         (let [parsed (b/decode (b/repeated demo-stream-chunk-protocol) (io/input-stream demo-stream-raw))
                               player-num-to-name (->> parsed
                                                       (map (comp :demo-stream-chunk :body))
                                                       (filter (comp #{6} :command :header))
                                                       (map :body)
                                                       (map (juxt :player-num (comp u/remove-nonprintable :player-name)))
                                                       (into {}))
                               chat-log (->> parsed
                                             (map (comp :demo-stream-chunk :body))
                                             (filter (comp #{7} :command :header))
                                             (map :body)
                                             (remove (comp #(string/starts-with? % "My player ID is") :message))
                                             doall)]
                           {:chat-log chat-log
                            :player-num-to-name player-num-to-name})
                         (catch Exception e
                           (log/warn e "Exception parsing demo stream")))
                       nil))))))))))


(defn- replay-type-and-players [parsed-replay]
  (let [teams (->> parsed-replay
                   :body
                   :script-data
                   :game
                   (filter (comp #(string/starts-with? % "team") name first)))
        teams-by-allyteam (->> teams
                               (group-by (comp keyword (partial str "allyteam") :allyteam second)))
        allyteam-counts (sort (map (comp count second) teams-by-allyteam))]
    {:game-type (u/game-type allyteam-counts)
     :player-counts allyteam-counts}))

(defn parse-replay
  ([^java.io.File f]
   (parse-replay f nil))
  ([^java.io.File f opts]
   (let [replay (try
                  (decode-replay f opts)
                  (catch Exception e
                    (log/error e "Error reading replay" f)))]
     (merge
       {:file f
        :filename (fs/filename f)
        :file-size (fs/size f)}
       replay
       (replay-type-and-players replay)))))
