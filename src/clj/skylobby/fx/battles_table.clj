(ns skylobby.fx.battles-table
  (:require
    [clojure.string :as string]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [skylobby.fx.flag-icon :as flag-icon]
    [skylobby.fx.ext :refer [with-layout-on-items-prop]]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]))


(def battles-table-keys
  [:battle :battle-password :battles :client-data :filter-battles :selected-battle :server-key :users])

(defn battles-table
  [{:keys [battle battle-password battles client-data filter-battles selected-battle server-key users]}]
  (let [filter-lc (when-not (string/blank? filter-battles)
                    (string/lower-case filter-battles))]
    {:fx/type fx.ext.table-view/with-selection-props
     :props {:selection-mode :single
             :on-selected-item-changed {:event/type :spring-lobby/select-battle
                                        :server-key server-key}}
     :desc
     {:fx/type with-layout-on-items-prop
      :props {
              :items (->> battles
                          vals
                          (filter :battle-title)
                          (filter
                            (fn [{:keys [battle-map battle-modname battle-title]}]
                              (if filter-lc
                                (or (and battle-map (string/includes? (string/lower-case battle-map) filter-lc))
                                    (and battle-modname (string/includes? (string/lower-case battle-modname) filter-lc))
                                    (string/includes? (string/lower-case battle-title) filter-lc))
                                true)))
                          (sort-by (juxt (comp count :users) :battle-spectators))
                          reverse)}
      :desc
      {:fx/type :table-view
       :style {:-fx-font-size 15}
       :column-resize-policy :constrained ; TODO auto resize
       :row-factory
       {:fx/cell-type :table-row
        :describe (fn [{:keys [battle-engine battle-id battle-map battle-modname battle-title battle-version users]}]
                    {:on-mouse-clicked
                     {:event/type :spring-lobby/on-mouse-clicked-battles-row
                      :battle battle
                      :battle-password battle-password
                      :client-data client-data
                      :selected-battle selected-battle
                      :battle-passworded (= "1" (-> battles (get selected-battle) :battle-passworded))}
                     :tooltip
                     {:fx/type :tooltip
                      :style {:-fx-font-size 16}
                      :show-delay [10 :ms]
                      :text (str battle-title "\n\n"
                                 "Map: " battle-map "\n"
                                 "Game: " battle-modname "\n"
                                 "Engine: " battle-engine " " battle-version "\n\n"
                                 (->> users
                                      keys
                                      (sort String/CASE_INSENSITIVE_ORDER)
                                      (string/join "\n")
                                      (str "Players:\n\n")))}
                     :context-menu
                     {:fx/type :context-menu
                      :items
                      [{:fx/type :menu-item
                        :text "Join Battle"
                        :on-action {:event/type :spring-lobby/join-battle
                                    :battle battle
                                    :client-data client-data
                                    :selected-battle battle-id}}]}})}
       :columns
       [
        {:fx/type :table-column
         :text "Game"
         :pref-width 200
         :cell-value-factory :battle-modname
         :cell-factory
         {:fx/cell-type :table-cell
          :describe (fn [battle-modname] {:text (str battle-modname)})}}
        {:fx/type :table-column
         :text "Status"
         :resizable false
         :pref-width 56
         :cell-value-factory identity
         :cell-factory
         {:fx/cell-type :table-cell
          :describe
          (fn [status]
            (cond
              (or (= "1" (:battle-passworded status))
                  (= "1" (:battle-locked status)))
              {:text ""
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-lock:16:yellow"}}
              (->> status :host-username (get users) :client-status :ingame)
              {:text ""
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-sword:16:red"}}
              :else
              {:text ""
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-checkbox-blank-circle-outline:16:green"}}))}}
        {:fx/type :table-column
         :text "Map"
         :pref-width 200
         :cell-value-factory :battle-map
         :cell-factory
         {:fx/cell-type :table-cell
          :describe (fn [battle-map] {:text (str battle-map)})}}
        {:fx/type :table-column
         :text "Play (Spec)"
         :resizable false
         :pref-width 100
         :cell-value-factory (juxt (comp count :users) #(or (u/to-number (:battle-spectators %)) 0))
         :cell-factory
         {:fx/cell-type :table-cell
          :describe
          (fn [[total-user-count spec-count]]
            {:text (str (if (and (number? total-user-count) (number? spec-count))
                          (- total-user-count spec-count)
                          total-user-count)
                        " (" spec-count ")")})}}
        {:fx/type :table-column
         :text "Battle Name"
         :pref-width 200
         :cell-value-factory :battle-title
         :cell-factory
         {:fx/cell-type :table-cell
          :describe (fn [battle-title] {:text (str battle-title)})}}
        {:fx/type :table-column
         :text "Country"
         :resizable false
         :pref-width 64
         :cell-value-factory #(:country (get users (:host-username %)))
         :cell-factory
         {:fx/cell-type :table-cell
          :describe
          (fn [country]
            {:text ""
             :graphic
             {:fx/type flag-icon/flag-icon
              :country-code country}})}}
        {:fx/type :table-column
         :text "Host"
         :pref-width 100
         :cell-value-factory :host-username
         :cell-factory
         {:fx/cell-type :table-cell
          :describe (fn [host-username] {:text (str host-username)})}}
        #_
        {:fx/type :table-column
         :text "Engine"
         :cell-value-factory #(str (:battle-engine %) " " (:battle-version %))
         :cell-factory
         {:fx/cell-type :table-cell
          :describe (fn [engine] {:text (str engine)})}}]}}}))
