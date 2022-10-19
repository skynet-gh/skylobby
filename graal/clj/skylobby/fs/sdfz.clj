(ns skylobby.fs.sdfz
  "Parsing of Spring demo files aka replays."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [org.clojars.smee.binary.core :as b]
    [skylobby.fs :as fs]
    [skylobby.spring.script :as spring-script]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.util.zip GZIPInputStream)))


(set! *warn-on-reflection* true)


(def replay-filename-re
  #"^(\d+)_(\d+)_(.*)_(.+).sdfz$")

(defn header-and-body
  [{:keys [header body]}]
  (merge header body))


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


(defn map-draw-protocol [length]
  (case (inc (int length))
    ; erase
    8
    (do
      (assert (= 7 length))
      (b/ordered-map
        :pad :ubyte
        :player-num :ubyte
        :map-draw-action :ubyte
        :x :short-le
        :z :short-le))
    ; line
    12
    (do
      (assert (= 11 length))
      (b/ordered-map
        :pad :ubyte
        :player-num :ubyte
        :map-draw-action :ubyte
        :x1 :short-le
        :z1 :short-le
        :x2 :short-le
        :z2 :short-le))
    ; point
    (do
      (assert (<= 7 length))
      (b/ordered-map
        :pad :ubyte
        :player-num :ubyte
        :map-draw-action :ubyte
        :x :short-le
        :z :short-le
        :label (b/string "ISO-8859-1" :length (- length 7))))))


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
          ; quit
          3
          (b/ordered-map
            :reason (b/string "ISO-8859-1" :length l))
          ; playername
          6
          (do
            (assert (<= 3 length))
            (b/ordered-map
              :pad :byte
              :player-num :ubyte
              :player-name (b/string "ISO-8859-1" :length (- length 3))))
          ; chat
          7
          (do
            (assert (<= 4 length))
            (b/ordered-map
              :pad :byte
              :from :ubyte
              :dest :ubyte
              :message (b/string "ISO-8859-1" :length (- length 4))))
          ; pause
          13
          (do
            (assert (= 3 length))
            (b/ordered-map
              :player-num :ubyte
              :paused :ubyte))
          ; gameover
          30
          (do
            (assert (<= 3 length))
            (b/ordered-map
              :pad :byte
              :player-num :ubyte
              :winning-ally-teams (b/repeated :ubyte :length (- length 3))))
          ; mapdraw
          31 (map-draw-protocol l)
          ; startpos
          36
          (do
            (assert (= 16 length))
            (b/ordered-map
              :player-num :ubyte
              :my-team :ubyte
              :ready :ubyte
              :x :float-le
              :y :float-le
              :z :float-le))
          ; playerleft
          37
          (do
            (assert (= 3 length))
            (b/ordered-map
              :player-num :ubyte
              :intended :ubyte))
          ; team
          51
          (do
            (assert (= 5 length) (str length))
            (b/ordered-map
              :player-num :ubyte
              :team-action :ubyte
              :parameter1 :ubyte
              :parameter2 :ubyte))
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
  (with-open [^java.lang.AutoCloseable is (io/input-stream f)
              ^java.lang.AutoCloseable
              is (if (string/ends-with? (fs/filename f) ".sdf")
                   is
                   (GZIPInputStream. is))]
    (->> is
         (b/decode sdfz-header)
         (into (sorted-map)))))

(defn sanitize-replay-engine [replay-engine-version]
  (some-> replay-engine-version u/remove-nonprintable string/trim fs/sync-version-to-engine-version))

(defn decode-replay
  ([^java.io.File f]
   (decode-replay f nil))
  ([^java.io.File f {:keys [parse-stream]}]
   (with-open [^java.lang.AutoCloseable is (io/input-stream f)
               ^java.lang.AutoCloseable
               is (if (string/ends-with? (fs/filename f) ".sdf")
                    is
                    (GZIPInputStream. is))]
     (-> (->> (b/decode sdfz-protocol is)
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
                                                       (map header-and-body)
                                                       (map (juxt :player-num (comp u/remove-nonprintable :player-name)))
                                                       (into {}))
                               start-positions (->> parsed
                                                    (map (comp :demo-stream-chunk :body))
                                                    (filter (comp #{36} :command :header)))
                               chat-log (->> parsed
                                             (map (fn [{:keys [header body]}]
                                                    (merge
                                                      header
                                                      (:demo-stream-chunk body))))
                                             (filter (comp #{3 7 13 30 31 37 51} :command :header))
                                             (map (fn [{:keys [body header] :as m}]
                                                    (merge
                                                      (dissoc m :body :header)
                                                      header
                                                      body)))
                                             (remove (comp #(and % (string/starts-with? % "My player ID is")) :message))
                                             doall)]
                           {:chat-log chat-log
                            :player-num-to-name player-num-to-name
                            :start-positions start-positions})
                         (catch Exception e
                           (log/warn e "Exception parsing demo stream")))
                       nil))))))))))


(defn replay-player-count
  [player-counts]
  (reduce (fnil + 0) 0 player-counts))

(defn replay-skills
  [{:keys [body]}]
  (let [skills (some->> body :script-data :game
                        (filter (comp #(string/starts-with? % "player") name first))
                        (filter (comp #{0 "0"} :spectator second))
                        (map (comp :skill second))
                        (map u/parse-skill)
                        (filter some?))]
    skills))

(defn average-skill [coll]
  (when (seq coll)
    (with-precision 3
      (/ (bigdec (reduce + coll))
         (bigdec (count coll))))))

(def replay-id
  (some-fn :id (comp :game-id :header)))

(defn spec-names [replay]
  (some->> replay :body :script-data :game
           (filter (comp #(string/starts-with? % "player") name first))
           (map (comp str #(some % [:name :username]) second))
           (remove (comp #{0 "0"} :spectator second))))

(defn replay-metadata [parsed-replay]
  (let [teams (->> parsed-replay
                   :body
                   :script-data
                   :game
                   (filter (comp #(string/starts-with? % "team") name first)))
        teams-by-allyteam (->> teams
                               (group-by (comp keyword (partial str "allyteam") :allyteam second)))
        players-by-team (->> parsed-replay
                             :body
                             :script-data
                             :game
                             (filter (comp (some-fn #(string/starts-with? % "player")
                                                    #(string/starts-with? % "ai"))
                                           name first))
                             (filter
                               (some-fn
                                 (comp #{0 "0"} :spectator second)
                                 (comp not #(contains? % :spectator) second)))
                             (map (juxt (comp keyword #(str "team" %) :team second) (comp str #(some % [:name :username]) second)))
                             (into {}))
        allyteam-counts (sort (map (comp count second) teams-by-allyteam))
        allyteam-players (map
                           (fn [[_allyteam teams]]
                             (map
                               (comp players-by-team first)
                               teams))
                           teams-by-allyteam)
        skills (replay-skills parsed-replay)]
    {:game-type (u/game-type allyteam-counts)
     :player-counts allyteam-counts
     :replay-player-count (replay-player-count allyteam-counts)
     :replay-skills skills
     :replay-average-skill (average-skill skills)
     :replay-id (replay-id parsed-replay)
     :replay-allyteam-player-names allyteam-players
     :replay-spec-names (spec-names parsed-replay)
     :replay-engine-version (-> parsed-replay :header :engine-version)
     :replay-mod-name (-> parsed-replay :body :script-data :game :gametype)
     :replay-map-name (-> parsed-replay :body :script-data :game :mapname)
     :replay-unix-time-str (some-> parsed-replay :header :unix-time str)
     :replay-timestamp (some-> parsed-replay :header :unix-time (* 1000))
     :replay-game-time (-> parsed-replay :header :game-time)}))

(defn parse-replay
  ([^java.io.File f]
   (parse-replay f nil))
  ([^java.io.File f {:keys [details] :as opts}]
   (let [size (fs/size f)]
     (merge
       {:file f
        :filename (fs/filename f)
        :file-size size}
       (if (pos? size)
         (let [replay (try
                        (decode-replay f opts)
                        (catch Exception e
                          (log/error e "Error reading replay" f)))]
           (merge
             (when details replay)
             (replay-metadata replay)))
         (log/info "Skipping replay parse of" f "because it has 0 size"))))))
