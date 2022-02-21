(ns skylobby.data
  (:require
    [clojure.string :as string]))


(defn filter-battles
  [battles {:keys [filter-battles hide-empty-battles hide-locked-battles hide-passworded-battles]}]
  (let [
        filter-battles (when (string? filter-battles)
                         filter-battles)
        filter-lc (when-not (string/blank? filter-battles)
                    (string/lower-case filter-battles))]
    (->> battles
         vals
         (filter :battle-title)
         (filter
           (fn [{:keys [battle-map battle-modname battle-title]}]
             (if filter-lc
               (or (and battle-map (string/includes? (string/lower-case battle-map) filter-lc))
                   (and battle-modname (string/includes? (string/lower-case battle-modname) filter-lc))
                   (string/includes? (string/lower-case battle-title) filter-lc))
               true)))
         (remove
           (fn [{:keys [battle-passworded]}]
             (if hide-passworded-battles
               (= "1" battle-passworded)
               false)))
         (remove
           (fn [{:keys [battle-locked]}]
             (if hide-locked-battles
               (= "1" battle-locked)
               false)))
         (remove
           (fn [{:keys [users]}]
             (if hide-empty-battles
               (boolean (<= (count users) 1)) ; TODO bot vs human hosts
               false)))
         (sort-by (juxt (comp count :users) :battle-spectators))
         reverse
         doall)))
