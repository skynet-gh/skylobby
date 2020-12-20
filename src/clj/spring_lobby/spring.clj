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
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(def startpostypes
  {0 "Fixed"
   1 "Random"
   2 "Choose in game"
   3 "Choose before game"})

(def ba-sides
  {0 "ARM"
   1 "CORE"})

(def bar-sides
  {0 "Armada"
   1 "Cortex"
   2 "Random"})

(defn sides
  ([]
   (sides nil))
  ([mod-name]
   (if (and mod-name (string/starts-with? mod-name "Beyond All Reason")) ; TODO where to get this
     bar-sides
     ba-sides)))

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

(defn team-name [id]
  (keyword (str "team" id)))

(defn players-and-bots
  "Returns the non-spectating players and bots in a battle."
  [battle]
  (filter
    (comp :mode :battle-status second)
    (merge (:users battle) (:bots battle))))

(defn teams [battle]
  (map
    (comp first second)
    (group-by (comp :id :battle-status second)
      (players-and-bots battle))))

(defn team-keys [teams]
  (set (map (comp team-name :id :battle-status second) teams)))

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

(defn script-data-client
  [battle {:keys [game]}]
  (let [hostip-override (-> battle :scripttags :game :hostip)]
    {:game
     {:hostip (if (string/blank? hostip-override)
                (:battle-ip battle)
                hostip-override)
      :hostport (:battle-port battle)
      :ishost 0
      :myplayername (:myplayername game)}}))

(defn script-data-host
  "Given data for a battle, returns data that can be directly formatted to script.txt format for Spring."
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
                                 (into {}))}
         startpostype (-> battle :scripttags :game :startpostype str)
         team-ids (->> battle
                       players-and-bots
                       (map (comp :id :battle-status second))
                       set)
         shuffled-ids (zipmap
                        team-ids
                        (if (= "1" startpostype)
                          (shuffle team-ids)
                          team-ids))
         actual-team-id (fn [id] (get shuffled-ids id id))]
     (u/deep-merge
       (when (= "3" startpostype)
         default-map-teams)
       (update
         (:scripttags battle)
         :game
         (fn [game]
           (let [all-modoptions (->> mod-details
                                     :modoptions
                                     (map second)
                                     (remove (comp #{"section"} :type)))]
             (->> (update game :modoptions
                          (fn [modoptions]
                            (->> all-modoptions
                                 (map
                                   (fn [modoption]
                                     (let [k (-> modoption :key string/lower-case keyword)
                                           v (:def modoption)]
                                       [k (get modoptions k v)])))
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
                (let [team (-> battle-status :id actual-team-id)
                      spectator (if (:mode battle-status) 0 1)]
                  [(keyword (str "player" team))
                   {:name player
                    :team team
                    :isfromdemo 0 ; TODO replays
                    :spectator spectator
                    :countrycode (:country user)}]))
              (:users battle))
            (map
              (fn [[_player {:keys [battle-status team-color owner]}]]
                (let [team-id (-> battle-status :id actual-team-id)
                      team-leader (if owner
                                    (-> battle :users (get owner) :battle-status :id actual-team-id)
                                    team-id)]
                    [(team-name team-id)
                     {:teamleader team-leader
                      :handicap (:handicap battle-status)
                      :allyteam (:ally battle-status)
                      :rgbcolor (format-color team-color)
                      :side (get (sides (:battle-modname battle)) (:side battle-status))}]))
              teams)
            (map
              (fn [[bot-name {:keys [ai-name ai-version battle-status owner]}]]
                (let [team (-> battle-status :id actual-team-id)
                      host (-> battle :users (get owner) :battle-status :id actual-team-id)]
                  [(keyword (str "ai" team))
                   {:name bot-name
                    :shortname ai-name
                    :version ai-version
                    :host host
                    :isfromdemo 0 ; TODO replays
                    :team team
                    :options {}}])) ; TODO ai options
              (:bots battle))
            (map
              (fn [ally]
                [(keyword (str "allyteam" ally)) {:numallies 0}])
              ally-teams)
            (:game opts)))}))))

(defn script-data
  "Given data for a battle, returns data that can be directly formatted to script.txt format for Spring."
  [battle {:keys [is-host] :as opts}]
  (if is-host
    (script-data-host battle opts)
    (script-data-client battle opts)))

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

(defn short-git-commit [git-commit-id]
  (when git-commit-id
    (subs git-commit-id 0 7)))

(defn mod-name [{:keys [git-commit-id modinfo]}]
  (str (:name modinfo) " "
       (or (when git-commit-id
             (str "git:" (short-git-commit git-commit-id)))
           (:version modinfo))))

; TODO find a better place for this
(defn mod-details [mods filter-mod-name]
  (some->> mods
           (filter (comp #{filter-mod-name} :mod-name))
           first))

(defn battle-script-txt [{:keys [username battle-map-details battle-mod-details] :as state}]
  (let [battle (battle-details state)
        script (script-data battle
                 {:is-host (= username (:host-username battle))
                  :game {:myplayername username}
                  :map-details battle-map-details
                  :mod-details battle-mod-details})]
    (script-txt script)))

(defn engine-details [engines engine-version]
  (some->> engines
           (filter (comp #{engine-version} :engine-version))
           first))

(defn hidden-directory? [path-str]
  (or (re-find #"^\." path-str)
      (re-find #"/\." path-str)
      (re-find #"\\\." path-str)))

(defn start-game [{:keys [client engines] :as state}]
  (try
    (log/info "Starting game")
    (client/send-message client "MYSTATUS 1")
    (let [battle (-> state
                     :battles
                     (get (-> state :battle :battle-id)))
          {:keys [battle-version]} battle
          script-txt (battle-script-txt state)
          isolation-dir (fs/isolation-dir)
          engine-dir (some->> engines
                              (filter (comp #{battle-version} :engine-version))
                              first
                              :file)
          engine-file (io/file engine-dir (fs/spring-executable))
          _ (log/info "Engine executable" engine-file)
          script-file (io/file (fs/app-root) "spring" "script.txt") ; TODO match isolation?
          script-file-param (fs/wslpath script-file)
          isolation-dir-param (fs/wslpath isolation-dir)
          write-dir-param (fs/wslpath engine-dir)]
      (spit script-file script-txt)
      (log/info "Wrote script to" script-file)
      (let [command [(fs/canonical-path engine-file)
                     "--isolation-dir" write-dir-param
                     "--write-dir" isolation-dir-param
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
