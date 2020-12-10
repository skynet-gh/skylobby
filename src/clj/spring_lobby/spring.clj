(ns spring-lobby.spring
  "Interface to run Spring."
  (:require
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.set]
    [clojure.string :as string]
    [clojure.walk]
    [com.evocomputing.colors :as colors]
    [spring-lobby.client.message :as client]
    [spring-lobby.fs :as fs]
    [spring-lobby.rapid :as rapid]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.io FileOutputStream)
    (java.nio.file CopyOption StandardCopyOption)
    (java.util.zip ZipEntry ZipOutputStream)
    (org.apache.commons.io FileUtils)))


(set! *warn-on-reflection* true)


(def startpostypes
  {0 "Fixed"
   1 "Random"
   2 "Choose in game"
   3 "Choose before game"})

(def sides
  {0 "ARM"
   1 "CORE"})

(def map-multiplier
  "Multiplier from positions in map file into positions that Spring uses."
  8.0)

(def startpostypes-by-name
  (clojure.set/map-invert startpostypes))


(defn startpostype-name [startpostype]
  (when startpostype
    (let [startpostype (int
                         (or
                           (if (string? startpostype)
                             (edn/read-string startpostype)
                             startpostype)
                           0))]
      (get startpostypes startpostype))))


(defn unit-rgb
  [i]
  (/ i 255.0))

(defn format-color [team-color]
  (when-let [decimal-color (or (when (number? team-color) team-color)
                               (try (Integer/parseInt team-color)
                                    (catch Exception _ nil)))]
    (let [[r g b _a] (:rgba (colors/create-color decimal-color))]
      (str (unit-rgb b) " " (unit-rgb g) " " (unit-rgb r))))) ; Spring lobby uses bgr

(defn team-name [battle-status]
  (keyword (str "team" (:id battle-status))))

(defn teams [battle]
  (map
    (comp first second)
    (group-by (comp :id :battle-status second)
      (filter
        (comp :mode :battle-status second)
        (merge (:users battle) (:bots battle))))))

(defn team-keys [teams]
  (set (map (comp team-name :battle-status second) teams)))

(defn normalize-team
  "Returns :team1 from either :team1 or :1."
  [team-kw]
  (let [[_all team] (re-find #"(\d+)" (name team-kw))]
    (keyword (str "team" team))))

(defn map-teams [map-details]
  (or (->> map-details :mapinfo :teams
           (map
             (fn [[k v]]
               [(normalize-team k)
                (-> v
                    (update-in [:startpos :x] u/to-number)
                    (update-in [:startpos :z] u/to-number))]))
           seq)
      (->> map-details
           :smd
           :map
           (filter (comp #(string/starts-with? % "team") name first))
           (map
             (fn [[k {:keys [startposx startposz]}]]
               [(normalize-team k)
                {:startpos {:x startposx :z startposz}}]))
           (into {}))
      []))

(defn script-data
  "Given data for a battle, returns data that can be directly formatted to script.txt format for Spring."
  ([battle]
   (script-data battle nil))
  ([battle {:keys [is-host map-details mod-details] :as opts}]
   (let [teams (teams battle)
         ally-teams (set
                      (map
                        (comp :ally :battle-status second)
                        (filter
                          (comp :mode :battle-status second)
                          (mapcat battle [:users :bots]))))
         team-keys (team-keys teams)
         existing-team? (fn [[k _v]]
                          (if (string/starts-with? (name k) "team")
                            (contains? team-keys k)
                            true))
         default-map-teams {:game
                            (->> (map-teams map-details)
                                 (map
                                   (fn [[k {:keys [startpos]}]]
                                     [(normalize-team k)
                                      {:startposx (:x startpos)
                                       :startposz (:z startpos)}]))
                                 (filter existing-team?)
                                 (into {}))}]
     (u/deep-merge
       (when (= "3" (-> battle :scripttags :game :startpostype str))
         default-map-teams)
       (update
         (:scripttags battle)
         :game
         (fn [game]
           (let [all-modoptions (->> mod-details
                                     :modoptions
                                     (map (comp :key second))
                                     (map (comp keyword string/lower-case))
                                     set)]
             (->> (update game :modoptions
                          (fn [modoptions]
                            (->> modoptions
                                 (filter (comp all-modoptions first))
                                 (into {}))))
                  (filter existing-team?)
                  (into {})))))
       {:game
        (into
          {:gametype (if-let [modinfo (:modinfo mod-details)]
                       (str (:name modinfo) " " (:version modinfo))
                       (:battle-modname battle))
           :mapname (:battle-map battle)
           :hostip (when-not is-host (:battle-ip battle))
           :hostport (:battle-port battle)
           :ishost (if is-host 1 0)}
          (concat
            (map
              (fn [[player {:keys [battle-status user]}]]
                [(keyword (str "player" (:id battle-status)))
                 {:name player
                  :team (:id battle-status)
                  :isfromdemo 0 ; TODO replays
                  :spectator (if (:mode battle-status) 0 1)
                  :countrycode (:country user)}])
              (:users battle))
            (map
              (fn [[_player {:keys [battle-status team-color owner]}]]
                [(team-name battle-status)
                 {:teamleader (if owner
                                (-> battle :users (get owner) :battle-status :id)
                                (:id battle-status))
                  :handicap (:handicap battle-status)
                  :allyteam (:ally battle-status)
                  :rgbcolor (format-color team-color)
                  :side (get sides (:side battle-status))}])
              teams)
            (map
              (fn [[bot-name {:keys [ai-name ai-version battle-status owner]}]]
                [(keyword (str "ai" (:id battle-status)))
                 {:name bot-name
                  :shortname ai-name
                  :version ai-version
                  :host (-> battle :users (get owner) :battle-status :id)
                  :isfromdemo 0 ; TODO replays
                  :team (:id battle-status)
                  :options {}}]) ; TODO ai options
              (:bots battle))
            (map
              (fn [ally]
                [(keyword (str "allyteam" ally)) {:numallies 0}])
              ally-teams)
            (:game opts)))}))))

(defn script-txt-inner
  ([kv]
   (script-txt-inner "" kv))
  ([tabs [k v]]
   (str tabs
        (if (map? v)
          (str "[" (name k ) "]\n" tabs "{\n"
               (apply str (map (partial script-txt-inner (str tabs "\t")) (sort-by first v)))
               tabs "}\n")
          (str (name k) " = " v ";"))
        "\n")))

; https://springrts.com/wiki/Script.txt
; https://github.com/spring/spring/blob/104.0/doc/StartScriptFormat.txt
; https://github.com/springlobby/springlobby/blob/master/src/spring.cpp#L284-L590
(defn script-txt
  "Given data for a battle, return contents of a script.txt file for Spring."
  ([script-data]
   (apply str (map script-txt-inner (sort-by first (clojure.walk/stringify-keys script-data))))))


(defn battle-details [{:keys [battle battles users]}]
  (let [battle (update battle :users
                       #(into {}
                          (map (fn [[k v]]
                                 [k (assoc v :username k :user (get users k))])
                               %)))]
    (merge (get battles (:battle-id battle)) battle)))

(defn mod-name [{:keys [git-commit-id modinfo]}]
  (str (:name modinfo) " "
       (or (when git-commit-id
             (subs git-commit-id 0 7))
           (:version modinfo))))

; TODO find a better place for this
(defn mod-details [mods filter-mod-name]
  (some->> mods
           (filter (comp #{filter-mod-name} mod-name))
           first))

(defn battle-script-txt [{:keys [username battle-map-details mods] :as state}]
  (let [battle (battle-details state)
        mod-details (mod-details mods (:battle-modname battle))
        script (script-data battle
                 {:is-host (= username (:host-username battle))
                  :game {:myplayername username}
                  :map-details battle-map-details
                  :mod-details mod-details})]
    (script-txt script)))

(defn engine-dir-filename [engines engine-version]
  (some->> engines
           (filter (comp #{engine-version} :engine-version))
           first
           :engine-dir-filename))

(defn engine-isolation-file ^java.io.File
  [engine-version]
  (io/file (fs/app-root) "spring" "engine" engine-version))

(defn copy-engine [engines engine-version]
  (if engine-version
    (let [source (io/file (fs/spring-root) "engine" (engine-dir-filename engines engine-version))
          dest (engine-isolation-file engine-version)]
      (if (.exists source)
        (do
          (FileUtils/forceMkdir dest)
          (log/info "Copying" source "to" dest)
          (FileUtils/copyDirectory source dest))
        (log/warn "No engine to copy from" (.getAbsolutePath source)
                  "to" (.getAbsolutePath ^java.io.File dest))))
    (throw
      (ex-info "Missing engine to copy to isolation dir"
               {:engine-version engine-version}))))

(defn mod-isolation-file ^java.io.File
  [{::fs/keys [source] :keys [filename]} engine-version]
  (when filename
    (case source
      :rapid
      (io/file (fs/app-root) "spring" "engine" engine-version "packages" filename)
      ; else
      (io/file (fs/app-root) "spring" "engine" engine-version "games" filename))))


(defn mod-isolation-archive-file ^java.io.File
  [{::fs/keys [source] :as mod-details} engine-version]
  (cond
    (#{:rapid :directory} source)
    (let [filename (str (mod-name mod-details) ".sdz")]
      (io/file (fs/app-root) "spring" "engine" engine-version "games" filename))
    :else
    (mod-isolation-file mod-details engine-version)))

(defn copy-mod [mod-details engine-version]
  (let [mod-filename (:filename mod-details)
        absolute-path (:absolute-path mod-details)]
    (cond
      (not (and mod-filename engine-version))
      (throw
        (ex-info "Missing mod or engine to copy to isolation dir"
                 {:mod-filename mod-filename
                  :engine-version engine-version}))
      (= :rapid (::fs/source mod-details))
      (let [sdp-decoded (rapid/decode-sdp (io/file absolute-path))
            source (io/file absolute-path)
            dest (io/file (fs/app-root) "spring" "engine" engine-version "packages" mod-filename)
            ^java.nio.file.Path source-path (.toPath source)
            ^java.nio.file.Path dest-path (.toPath dest)
            ^"[Ljava.nio.file.CopyOption;" options (into-array ^CopyOption
                                                     [StandardCopyOption/COPY_ATTRIBUTES
                                                      StandardCopyOption/REPLACE_EXISTING])]
        (.mkdirs dest)
        (java.nio.file.Files/copy source-path dest-path options)
        (doseq [item (:items sdp-decoded)]
          (let [md5 (:md5 item)
                ^java.io.File pool-source (rapid/file-in-pool md5)
                ^java.io.File pool-dest (rapid/file-in-pool (io/file (fs/app-root) "spring" "engine" engine-version) md5)
                ^java.nio.file.Path pool-source-path (.toPath pool-source)
                ^java.nio.file.Path pool-dest-path (.toPath pool-dest)]
            (log/info "Copying" pool-source-path "to" pool-dest-path)
            (.mkdirs pool-dest)
            (java.nio.file.Files/copy pool-source-path pool-dest-path options))))
      (= :archive (::fs/source mod-details))
      (let [source (io/file (fs/spring-root) "games" mod-filename)
            dest (io/file (fs/app-root) "spring" "engine" engine-version "games" mod-filename)
            ^java.nio.file.Path source-path (.toPath source)
            ^java.nio.file.Path dest-path (.toPath dest)
            ^"[Ljava.nio.file.CopyOption;" options (into-array ^CopyOption
                                                     [StandardCopyOption/COPY_ATTRIBUTES
                                                      StandardCopyOption/REPLACE_EXISTING])]
        (if (.exists source)
          (do
            (.mkdirs dest)
            (java.nio.file.Files/copy source-path dest-path options))
          (log/warn "No mod file to copy from" (.getAbsolutePath source)
                    "to" (.getAbsolutePath dest)))))))

(defn hidden-directory? [path-str]
  (or (re-find #"^\." path-str)
      (re-find #"/\." path-str)
      (re-find #"\\\." path-str)))

(defn archive-mod [mod-details engine-version]
  (let [mod-filename (:filename mod-details)
        absolute-path (:absolute-path mod-details)]
    (cond
      (not (and mod-filename engine-version))
      (throw
        (ex-info "Missing mod or engine to copy to isolation dir"
                 {:mod-filename mod-filename
                  :engine-version engine-version}))
      (= :rapid (::fs/source mod-details))
      (let [sdp-decoded (rapid/decode-sdp (io/file absolute-path))
            parent (io/file (fs/app-root) "spring" "engine" engine-version "games")
            ^java.io.File dest (mod-isolation-archive-file mod-details engine-version)]
        (.mkdirs parent)
        (with-open [fos (FileOutputStream. dest)
                    zos (ZipOutputStream. fos)]
          (doseq [{:keys [filename]} (:items sdp-decoded)]
            (let [{:keys [content-bytes]} (rapid/inner sdp-decoded filename)
                  ze (ZipEntry. ^String filename)]
              (log/debug "Adding zip entry for" filename)
              (.putNextEntry zos ze)
              (.write zos content-bytes 0 (count content-bytes))
              (.closeEntry zos))))
        (log/info "Finished creating" dest))
      (= :directory (::fs/source mod-details))
      (let [source-dir (io/file absolute-path)
            source-path (.toPath source-dir)
            parent (io/file (fs/app-root) "spring" "engine" engine-version "games")
            ^java.io.File dest (mod-isolation-archive-file mod-details engine-version)]
        (.mkdirs parent)
        (with-open [fos (FileOutputStream. dest)
                    zos (ZipOutputStream. fos)]
          (doseq [subfile (->> source-dir
                               file-seq
                               (filter #(.isFile %)))]
            (let [file-bytes (rapid/slurp-bytes subfile)
                  path (.toPath subfile)
                  relative-path (str (.relativize source-path path))]
              (if-not (hidden-directory? relative-path)
                (let [ze (ZipEntry. ^String relative-path)]
                  (log/debug "Adding zip entry for" relative-path)
                  (.putNextEntry zos ze)
                  (.write zos file-bytes 0 (count file-bytes))
                  (.closeEntry zos))
                (log/info "Skipping hidden directory" relative-path)))))
        (log/info "Finished creating" dest))
      :else
      (log/info "Nothing to do, mod is already an archive: " (:filename mod-details)))))

(defn map-isolation-file ^java.io.File
  [map-filename engine-version]
  (when (and map-filename engine-version)
    (io/file (fs/app-root) "spring" "engine" engine-version "maps" map-filename)))

(defn copy-map [map-filename engine-version]
  (if (and map-filename engine-version)
    (let [source (io/file (fs/spring-root) "maps" map-filename)
          ^java.io.File dest (map-isolation-file map-filename engine-version)
          ^java.nio.file.Path source-path (.toPath source)
          ^java.nio.file.Path dest-path (.toPath dest)
          ^"[Ljava.nio.file.CopyOption;" options (into-array ^CopyOption
                                                   [StandardCopyOption/COPY_ATTRIBUTES
                                                    StandardCopyOption/REPLACE_EXISTING])]
      (if (.exists source)
        (do
          (.mkdirs dest)
          (java.nio.file.Files/copy source-path dest-path options))
        (log/warn "No map file to copy from" (.getAbsolutePath source)
                  "to" (.getAbsolutePath dest))))
    (throw
      (ex-info "Missing map or engine to copy to isolation dir"
               {:map-filename map-filename
                :engine-version engine-version}))))

(defn start-game [{:keys [client engines maps mods] :as state}]
  (try
    (log/info "Starting game")
    (client/send-message client "MYSTATUS 1")
    (let [battle (-> state
                     :battles
                     (get (-> state :battle :battle-id)))
          {:keys [battle-map battle-version battle-modname]} battle
          _ (copy-engine engines battle-version)
          mod-details (some->> mods
                               (filter (comp #{battle-modname} mod-name))
                               first)
          mod-isolation-archive (mod-isolation-archive-file mod-details battle-version)
          _ (if (and mod-isolation-archive (.exists mod-isolation-archive))
              (log/info "Mod archive" mod-isolation-archive "already exists")
              (copy-mod mod-details battle-version))
          map-filename (->> maps
                            (filter (comp #{battle-map} :map-name))
                            first
                            :filename)
          _ (copy-map map-filename battle-version)
          script-txt (battle-script-txt state)
          isolation-dir (io/file (fs/app-root) "spring" "engine" battle-version)
          engine-file (io/file isolation-dir (fs/spring-executable))
          _ (log/info "Engine executable" engine-file)
          script-file (io/file (fs/app-root) "spring" "script.txt")
          script-file-param (fs/wslpath script-file)
          isolation-dir-param (fs/wslpath isolation-dir)]
      (spit script-file script-txt)
      (log/info "Wrote script to" script-file)
      (let [command [(.getAbsolutePath engine-file)
                     "--isolation-dir" isolation-dir-param
                     script-file-param]
            runtime (Runtime/getRuntime)]
        (log/info "Running '" command "'")
        (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
              ^"[Ljava.lang.String;" envp (fs/envp)
              process (.exec runtime cmdarray envp isolation-dir)]
          (client/send-message client "MYSTATUS 1")
          (async/thread
            (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (log/info "(spring out)" line)
                    (recur))
                  (log/info "Spring stdout stream closed")))))
          (async/thread
            (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
              (loop []
                (if-let [line (.readLine reader)]
                  (do
                    (log/info "(spring err)" line)
                    (recur))
                  (log/info "Spring stderr stream closed")))))
          (future
            (.waitFor process)
            (client/send-message client "MYSTATUS 0")))))
    (catch Exception e
      (log/error e "Error starting game")
      (client/send-message client "MYSTATUS 0"))))
