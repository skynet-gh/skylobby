(ns skylobby.fx.server-tab
  (:require
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.battles-buttons :as fx.battles-buttons]
    [skylobby.fx.bottom-bar :as fx.bottom-bar]
    [skylobby.fx.channels :as fx.channels]
    [skylobby.fx.main-tabs :as fx.main-tabs]))


(def server-tab-state-keys
  (concat
    fx.battle/battle-view-state-keys
    fx.battles-buttons/battles-buttons-state-keys
    fx.bottom-bar/bottom-bar-keys
    fx.main-tabs/main-tab-view-state-keys
    [:console-auto-scroll :map-details :mod-details :pop-out-battle :selected-battle]))

(def server-tab-keys
  (concat
    fx.battle/battle-view-keys
    fx.main-tabs/main-tab-view-keys
    server-tab-state-keys))

(defn server-tab
  [{:keys [battle console-auto-scroll pop-out-battle selected-tab-channel selected-tab-main
           tasks-by-type]
    :as state}]
  {:fx/type :v-box
   :style {:-fx-font-size 14}
   :alignment :top-left
   :children
   (concat
     (let [main-tab [
                     (merge
                       {:fx/type fx.main-tabs/main-tab-view
                        :v-box/vgrow :always
                        :console-auto-scroll console-auto-scroll
                        :selected-tab-channel selected-tab-channel
                        :selected-tab-main selected-tab-main}
                       (select-keys state
                         (concat
                           fx.main-tabs/main-tab-view-state-keys
                           fx.main-tabs/main-tab-view-keys
                           fx.main-tabs/my-channels-view-keys
                           fx.channels/channels-table-keys)))
                     (merge
                       {:fx/type fx.battles-buttons/battles-buttons-view}
                       (select-keys state
                         (concat fx.battles-buttons/battles-buttons-state-keys
                                 fx.battles-buttons/battles-buttons-keys)))]]
       (if (and (seq battle)
                (or (not (:battle-id battle))
                    (not pop-out-battle)))
         [{:fx/type :split-pane
           :orientation :vertical
           :divider-positions [0.35]
           :v-box/vgrow :always
           :items
           [{:fx/type :v-box
             :children main-tab}
            (if (:battle-id battle)
              (merge
                {:fx/type fx.battle/battle-view
                 :tasks-by-type tasks-by-type}
                (select-keys state fx.battle/battle-view-keys))
              {:fx/type :h-box
               :alignment :top-left
               :children
               [{:fx/type :v-box
                 :h-box/hgrow :always
                 :children
                 [{:fx/type :label
                   :style {:-fx-font-size 20}
                   :text "Waiting for battle details from server..."}]}]})]}]
         main-tab))
     [(merge
        {:fx/type fx.bottom-bar/bottom-bar}
        (select-keys state fx.bottom-bar/bottom-bar-keys))])})
