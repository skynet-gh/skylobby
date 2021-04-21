(ns skylobby.fx.multi-server
  (:require
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.main-tabs :as fx.main-tabs]
    [skylobby.fx.user :as fx.user]
    [spring-lobby.fs :as fs]))


(def multi-battle-view-keys
  fx.battle/battle-view-keys)

(defn multi-battle-view
  [{:keys [by-server by-spring-root servers spring-isolation-dir] :as state}]
  (let [tabs (->> by-server
                  vals
                  (filter (comp :battle-id :battle))
                  (map
                    (fn [{:keys [battle battles client-data] :as server-data}]
                      (let [spring-root (or (-> servers (get (:server-url client-data)) :spring-isolation-dir)
                                            spring-isolation-dir)
                            spring-root-data (get by-spring-root (fs/canonical-path spring-root))]
                        {:fx/type :tab
                         :on-close-request {:event/type :spring-lobby/leave-battle
                                            :client-data client-data}
                         :graphic {:fx/type :label
                                   :text (str (-> battles (get (:battle-id battle)) :battle-title))}
                         :content
                         (merge
                           {:fx/type fx.battle/battle-view}
                           (select-keys state fx.battle/battle-view-keys)
                           server-data
                           {:spring-isolation-dir spring-root
                            :engines (:engines spring-root-data)
                            :maps (:maps spring-root-data)
                            :mods (:mods spring-root-data)})}))))]
    (cond
      (empty? tabs) {:fx/type :pane}
      (= 1 (count tabs)) (:content (first tabs))
      :else
      {:fx/type :tab-pane
       :style {:-fx-font-size 16}
       :tabs tabs})))


(def multi-server-tab-keys
  (concat
    fx.battle/battle-view-keys
    [:by-server :by-spring-root :servers :spring-isolation-dir :tasks-by-type]))

(defn multi-server-tab
  [{:keys [by-server by-spring-root servers spring-isolation-dir tasks-by-type]
    :as state}]
  (let [last-failed-message (some (comp :last-failed-message second) by-server)
        battles (mapcat (comp :battles second) by-server)
        users (mapcat (comp :users second) by-server)]
    {:fx/type :v-box
     :style {:-fx-font-size 14}
     :alignment :top-left
     :children
     [{:fx/type :split-pane
       :orientation :vertical
       :divider-positions [0.35]
       :v-box/vgrow :always
       :items
       [
        {:fx/type :v-box
         :children
         [
          {:fx/type :split-pane
           :divider-positions [0.80]
           :items
           [
            {:fx/type :v-box
             :children
             [{:fx/type :label
               :text (str "Battles (" (count battles) ")")}
              (merge
                {:fx/type fx.main-tabs/battles-table
                 :v-box/vgrow :always}
                {:battles battles})]}
            {:fx/type :v-box
             :children
             [{:fx/type :label
               :text (str "Users (" (count users) ")")}
              (merge
                {:fx/type fx.user/users-table
                 :v-box/vgrow :always}
                {:users users})]}]}]}
        (merge
          {:fx/type multi-battle-view
           :by-server by-server
           :by-spring-root by-spring-root
           :servers servers
           :spring-isolation-dir spring-isolation-dir}
          (select-keys state fx.battle/battle-view-keys))]}
      {:fx/type :h-box
       :alignment :center-left
       :children
       [{:fx/type :label
         :text (str last-failed-message)
         :style {:-fx-text-fill "#FF0000"}}
        {:fx/type :pane
         :h-box/hgrow :always}
        {:fx/type :button
         :text (str (count tasks-by-type) " tasks")
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-tasks-window}}]}]}))
