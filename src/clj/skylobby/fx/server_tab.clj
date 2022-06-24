(ns skylobby.fx.server-tab
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.main-tabs :as fx.main-tabs]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(defn- server-tab-impl
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [battle-as-tab (fx/sub-val context :battle-as-tab)
        pop-out-battle (fx/sub-val context :pop-out-battle)
        battle (fx/sub-val context get-in [:by-server server-key :battle])
        in-battle (and (seq battle)
                       (or (not (:battle-id battle))
                           (not pop-out-battle)))
        show-battle-pane (and in-battle (not battle-as-tab))
        divider-positions (fx/sub-val context :divider-positions)
        divider-key :server-tab
        tabs {:fx/type fx.main-tabs/main-tab-view
              :v-box/vgrow :always
              :server-key server-key}]
    {:fx/type :v-box
     :style {:-fx-font-size 14}
     :alignment :top-left
     :children
     [
      (if show-battle-pane
        {:fx/type fx/ext-on-instance-lifecycle
         :v-box/vgrow :always
         :on-created (fn [^javafx.scene.control.SplitPane node]
                       (skylobby.fx/add-divider-listener node divider-key))
         :desc
         {:fx/type :split-pane
          :orientation :vertical
          :divider-positions [(or (get divider-positions divider-key)
                                  0.20)]
          :items
          [tabs
           (if (:battle-id battle)
             {:fx/type fx.battle/battle-view
              :server-key server-key}
             {:fx/type :h-box
              :alignment :top-left
              :children
              [{:fx/type :v-box
                :h-box/hgrow :always
                :children
                [{:fx/type :label
                  :style {:-fx-font-size 20}
                  :text "Waiting for battle details from server..."}]}]})]}}
        tabs)]}))

(defn server-tab [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :server-tab
      (server-tab-impl state))))
