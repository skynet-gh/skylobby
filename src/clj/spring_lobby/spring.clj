(ns spring-lobby.spring
  "Interface to run Spring."
  (:require
    [clojail.core :as clojail]
    [clojure.edn :as edn]
    [clojure.set]
    [clojure.string :as string]
    [clojure.walk]
    [com.evocomputing.colors :as colors]
    [instaparse.core :as instaparse]
    [taoensso.timbre :as log]))


(def startpostypes
  {0 "Fixed"
   1 "Random"
   2 "Choose in game"
   3 "Choose before game"})

(def sides
  {0 "ARM"
   1 "CORE"})


(def startpostypes-by-name
  (clojure.set/map-invert startpostypes))

(def default-modoptions
  {:relayhoststartpostype 1
   :disablemapdamage 0
   :fixedallies 1
   :maxunits 500
   :starttime 0
   :deathmode "com"
   :scoremode "disabled"
   :pathfinder "normal"})

(def default-scripttags ; TODO read these from lua in map, mod/game, and engine
  {:game
   {:startpostype 1
    :modoptions default-modoptions}})


; https://stackoverflow.com/a/17328219/984393
(defn deep-merge [& ms]
  (apply
    merge-with
    (fn [x y]
      (cond (map? y) (deep-merge x y)
            (vector? y) (concat x y)
            :else y))
    ms))

(defn parse-scripttags [raw-scripttags]
  (->> (string/split raw-scripttags #"\t")
       (remove string/blank?)
       (map
         (fn [raw-scripttag]
           (let [[_all ks v] (re-find #"([^\s]+)=(.*)" raw-scripttag)
                 kws (map keyword (string/split ks #"/"))]
             (assoc-in {} kws v))))
       (apply deep-merge)))


(defn unit-rgb
  [i]
  (/ i 255.0))

(defn format-color [team-color]
  (when-let [decimal-color (or (when (number? team-color) team-color)
                               (try (Integer/parseInt team-color)
                                    (catch Exception _ nil)))]
    (let [[r g b _a] (:rgba (colors/create-color decimal-color))]
      (str (unit-rgb r) " " (unit-rgb g) " " (unit-rgb b)))))

(defn script-data
  "Given data for a battle, returns data that can be directly formatted to script.txt format for Spring."
  ([battle]
   (script-data battle nil))
  ([battle opts]
   (let [is-host true] ; TODO
     (deep-merge
       (:scripttags battle)
       {:game
        (into
          {:gametype (:battle-modname battle)
           :mapname (:battle-map battle)
           :hostip (when-not is-host (:battle-ip battle))
           :hostport (:battle-port battle)
           :ishost (if is-host 1 0)}
          (concat
            (map
              (fn [[player {:keys [battle-status user]}]]
                [(str "player" (:id battle-status))
                 {:name player
                  :team (:id battle-status)
                  :isfromdemo 0 ; TODO
                  :spectator (if (:mode battle-status) 0 1)
                  :countrycode (:country user)}])
              (:users battle))
            (map
              (fn [[_player {:keys [battle-status team-color owner]}]]
                [(str "team" (:id battle-status))
                 {:teamleader (if owner
                                (-> battle :users (get owner) :battle-status :id)
                                (:id battle-status))
                  :handicap (:handicap battle-status)
                  :allyteam (:ally battle-status)
                  :rgbcolor (format-color team-color)
                  :side (get sides (:side battle-status))}])
              (map
                (comp first second)
                (group-by (comp :id :battle-status second)
                  (filter
                    (comp :mode :battle-status second)
                    (merge (:users battle) (:bots battle)))))) ; TODO group-by :team ?
            (map
              (fn [[bot-name {:keys [ai-name ai-version battle-status owner]}]]
                [(str "ai" (:id battle-status))
                 {:name bot-name
                  :shortname ai-name
                  :version ai-version
                  :host (-> battle :users (get owner) :battle-status :id)
                  :isfromdemo 0 ; TODO
                  :team (:id battle-status)
                  :options {}}]) ; TODO
              (:bots battle))
            (map
              (fn [ally]
                [(str "allyteam" ally) {:numallies 0}])
              (set
                (map
                  (comp :ally :battle-status second)
                  (filter
                    (comp :mode :battle-status second)
                    (mapcat battle [:users :bots])))))
            opts))}))))

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


(def script-grammar
  (instaparse/parser
    "config = block*
     block = <comment*>
             tag <comment*>
             <'{'>
               ( block | field | <comment> )*
             <'}'>
             <comment*>
     tag = <'['> #'[\\w\\s]+' <']'>
     field = <#'\\s+'?> #'\\w+' <#'\\s+'?> <'='> <#'\\s*'> #'[^;]*' <';'> <#'.*'>?
     comment = '//' #'.*'"
    :auto-whitespace :standard))


(declare parse-fields-or-blocks)

(defn parse-number
  [v]
  (try
    (if-let [e (edn/read-string v)] ; TODO clean
       (if (number? e)
         e
         v)
       v)
    (catch Exception _e
      v)))

(defn parse-field-or-block [field-or-block]
  (let [kind (first field-or-block)]
    (case kind
      :field
      (let [[_kind k v] field-or-block]
        [(keyword (string/lower-case k))
         (parse-number v)])
      :block
      (let [[_block [_tag tag]] field-or-block
            block (nthrest field-or-block 2)]
        [(keyword (string/lower-case tag))
         (parse-fields-or-blocks block)]))))

(defn parse-fields-or-blocks
  [fields-or-blocks]
  (into (sorted-map)
    (map parse-field-or-block fields-or-blocks)))

(defn postprocess [script-parsed-raw]
  (let [blocks (rest script-parsed-raw)]
    (parse-fields-or-blocks blocks)))


; https://stackoverflow.com/a/62915361/984393
(defn remove-nonprintable [s]
  (string/replace s #"[\p{C}&&^(\S)]" ""))


(defn parse-script
  "Returns the parsed data representation of a spring config."
  [script-txt]
  (clojail/thunk-timeout
    #(let [cleaned (remove-nonprintable script-txt)
           parsed (instaparse/parse script-grammar cleaned)]
       (if (instaparse/failure? parsed)
         (do
           (log/debug parsed)
           (throw (ex-info "Failed to parse" {:pr-script-txt (pr-str cleaned)})))
         (postprocess parsed)))
    2000))
