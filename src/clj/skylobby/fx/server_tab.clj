(ns skylobby.fx.server-tab
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.bottom-bar :as fx.bottom-bar]
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
        tabs {:fx/type fx.main-tabs/main-tab-view
              :v-box/vgrow :always
              :server-key server-key}]
    {:fx/type :v-box
     :style {:-fx-font-size 14}
     :alignment :top-left
     :children
     [
      (if show-battle-pane
        {:fx/type :split-pane
         :orientation :vertical
         :divider-positions [0.20]
         :v-box/vgrow :always
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
                 :text "Waiting for battle details from server..."}]}]})]}
        tabs)
      {:fx/type fx.bottom-bar/bottom-bar}]}))

(defn server-tab [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :server-tab
      (server-tab-impl state))))
