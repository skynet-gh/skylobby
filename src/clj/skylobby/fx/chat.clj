(ns skylobby.fx.chat
  (:require
    skylobby.fx
    [skylobby.fx.channel :as fx.channel]
    [spring-lobby.util :as u]))


(def chat-window-width 1600)
(def chat-window-height 1000)

(def chat-window-keys
  (concat
    [:chat-auto-scroll :css :screen-bounds :show-chat-window :window-states]
    fx.channel/channel-state-keys))

(defn chat-window
  [{:keys [channel-name channels chat-auto-scroll client-data css message-drafts screen-bounds
           server-key show-chat-window window-states]
    :as state}]
  (let [{:keys [width height]} screen-bounds]
    {:fx/type :stage
     :showing show-chat-window
     :title (str u/app-name " Battle Chat")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :pop-out-chat}
     :x (get-in window-states [:chat :x] Double/NaN)
     :y (get-in window-states [:chat :y] Double/NaN)
     :width ((fnil min chat-window-width) width (get-in window-states [:chat :width] chat-window-width))
     :height ((fnil min chat-window-height) height (get-in window-states [:chat :height] chat-window-height))
     :on-width-changed {:event/type :spring-lobby/assoc-in
                        :path [:window-states :chat :width]}
     :on-height-changed {:event/type :spring-lobby/assoc-in
                         :path [:window-states :chat :height]}
     :on-x-changed {:event/type :spring-lobby/assoc-in
                    :path [:window-states :chat :x]}
     :on-y-changed {:event/type :spring-lobby/assoc-in
                    :path [:window-states :chat :y]}
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
