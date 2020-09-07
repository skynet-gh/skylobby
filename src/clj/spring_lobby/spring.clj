(ns spring-lobby.spring
  "Interface to run Spring."
  (:require
    [clojure.walk]
    [com.evocomputing.colors :as colors]))


(defn format-color [team-color]
  (when-let [decimal-color (or (when (number? team-color) team-color)
                               (try (Integer/parseInt team-color)
                                    (catch Exception _ nil)))]
    (let [[r g b _a] (:rgba (colors/create-color decimal-color))]
      (str r " " g " " b))))

(defn script-data
  "Given data for a battle, returns data that can be directly formatted to script.txt format for Spring."
  ([battle]
   (script-data battle nil))
  ([battle opts]
   {:game
    (into
      {:gametype (:battle-modname battle)
       :mapname (:battle-map battle)
       :hostip (:battle-ip battle)
       :hostport (:battle-port battle)
       :ishost 1 ; TODO
       :numplayers 1 ; TODO
       :startpostype 2 ; TODO
       :numusers (count (concat (:users battle) (:bots battle)))} ; TODO
      (concat
        (map
          (fn [[player {:keys [battle-status user]}]]
            [(str "player" (:id battle-status))
             {:name player
              :team (:ally battle-status)
              :isfromdemo 0 ; TODO
              :countrycode (:country user)}])
          (:users battle))
        (map
          (fn [[_player {:keys [battle-status]}]]
            [(str "team" (:ally battle-status))
             {:teamleader (:id battle-status)
              :handicap (:handicap battle-status)
              :allyteam (:ally battle-status)
              :rgbcolor (format-color (:team-color battle-status))
              :side (:side battle-status)}])
          (map
            (comp first second)
            (group-by (comp :ally :battle-status second)
              (merge (:users battle) (:bots battle))))) ; TODO group-by :team ?
        (map
          (fn [[bot-name {:keys [ai-name ai-version battle-status owner]}]]
            [(str "ai" (:id battle-status))
             {:name bot-name
              :shortname ai-name
              :version ai-version
              :host (-> battle :users (get owner) :battle-status :id)
              :isfromdemo 0 ; TODO
              :team (:ally battle-status)
              :options {}}]) ; TODO
          (:bots battle))
        (map
          (fn [ally]
            [(str "allyteam" ally) {}])
          (set (map (comp :ally :battle-status second) (mapcat battle [:users :bots]))))
        opts))}))

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
