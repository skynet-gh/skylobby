(ns spring-lobby.battle)


(defn battle-players-and-bots-users
  "Returns the sequence of all players and bots for a battle."
  [{:keys [battle users]}]
  (concat
    (mapv
      (fn [[k v]] (assoc v :username k :user (get users k)))
      (:users battle))
    (mapv
      (fn [[k v]]
        (assoc v
               :bot-name k
               :user {:client-status {:bot true}}))
      (:bots battle))))

(defn battle-players-and-bots
  "Returns the sequence of all players and bots for a battle."
  [battle]
  (->> (mapcat battle [:users :bots])
       (mapv second)))


(defn- first-avail [n-set]
  (loop [i 0]
    (if (or (contains? n-set i)
            (contains? n-set (str i)))
      (recur (inc i))
      i)))

(defn available-team-id [battle]
  (let [ids (set (map (comp :id :battle-status) (battle-players-and-bots battle)))]
    (first-avail ids)))

(defn available-ally [battle]
  (let [allies (set (map (comp :ally :battle-status) (battle-players-and-bots battle)))]
    (first-avail allies)))
