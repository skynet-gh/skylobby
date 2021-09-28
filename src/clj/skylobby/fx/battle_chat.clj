(ns skylobby.fx.battle-chat
  (:require
    skylobby.fx
    [skylobby.fx.channel :as fx.channel]
    [spring-lobby.util :as u]))


(def window-key :battle-chat)

(def battle-chat-window-width 1600)
(def battle-chat-window-height 1000)

(def battle-chat-window-keys
  (concat
    [:chat-auto-scroll :css :screen-bounds :show-battle-chat-window :window-states]
    fx.channel/channel-state-keys))

(defn battle-chat-window
  [{:keys [channel-name channels chat-auto-scroll client-data css message-drafts screen-bounds
           server-key show-battle-chat-window window-states]
    :as state}]
  (let [{:keys [width height]} screen-bounds]
    {:fx/type :stage
     :showing show-battle-chat-window
     :title (str u/app-name " Battle Chat")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :pop-out-chat}
     :x (get-in window-states [window-key :x] 0)
     :y (get-in window-states [window-key :y] 0)
     :width ((fnil min battle-chat-window-width) width (get-in window-states [window-key :width] battle-chat-window-width))
     :height ((fnil min battle-chat-window-height) height (get-in window-states [window-key :height] battle-chat-window-height))
     :on-width-changed (partial skylobby.fx/window-changed window-key :width)
     :on-height-changed (partial skylobby.fx/window-changed window-key :height)
     :on-x-changed (partial skylobby.fx/window-changed window-key :x)
     :on-y-changed (partial skylobby.fx/window-changed window-key :y)
     :scene
     {:fx/type :scene
      :stylesheets (skylobby.fx/stylesheet-urls css)
      :root
      (merge
        {:fx/type fx.channel/channel-view
         :h-box/hgrow :always
         :channel-name channel-name
         :channels channels
         :chat-auto-scroll chat-auto-scroll
         :client-data client-data
         :hide-users true
         :message-draft (get message-drafts channel-name)
         :server-key server-key}
        (select-keys state fx.channel/channel-state-keys))}}))
