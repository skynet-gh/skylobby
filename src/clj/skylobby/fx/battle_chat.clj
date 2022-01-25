(ns skylobby.fx.battle-chat
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.channel :as fx.channel]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def window-key :battle-chat)

(def battle-chat-window-width 1600)
(def battle-chat-window-height 1000)


(defn battle-chat-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [pop-out-chat (fx/sub-val context :pop-out-chat)
        window-states (fx/sub-val context :window-states)
        server-key (fx/sub-ctx context skylobby.fx/selected-tab-server-key-sub)
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        show-battle-chat-window (boolean (and pop-out-chat battle-id))]
    {:fx/type :stage
     :showing show-battle-chat-window
     :title (str u/app-name " Battle Chat")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :pop-out-chat}
     :x (skylobby.fx/fitx screen-bounds (get-in window-states [window-key :x] 0))
     :y (skylobby.fx/fity screen-bounds (get-in window-states [window-key :y] 0))
     :width (skylobby.fx/fitwidth screen-bounds (get-in window-states [window-key :width]) battle-chat-window-width)
     :height (skylobby.fx/fitheight screen-bounds (get-in window-states [window-key :height]) battle-chat-window-height)
     :on-width-changed (partial skylobby.fx/window-changed window-key :width)
     :on-height-changed (partial skylobby.fx/window-changed window-key :height)
     :on-x-changed (partial skylobby.fx/window-changed window-key :x)
     :on-y-changed (partial skylobby.fx/window-changed window-key :y)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show-battle-chat-window
        {:fx/type fx.channel/channel-view
         :h-box/hgrow :always
         :channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
         :hide-users true
         :server-key server-key}
        {:fx/type :pane
         :pref-width battle-chat-window-width
         :pref-height battle-chat-window-height})}}))

(defn battle-chat-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battle-chat-window
      (battle-chat-window-impl state))))
