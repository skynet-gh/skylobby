(ns skylobby.fx.chat
  (:require
    skylobby.fx
    [skylobby.fx.channel :as fx.channel]
    [spring-lobby.util :as u]))


(def chat-window-width 1600)
(def chat-window-height 1000)

(def chat-window-keys
  [:css :screen-bounds :show-chat-window])

(defn chat-window
  [{:keys [channel-name channels chat-auto-scroll client-data css message-drafts screen-bounds
           server-key show-chat-window]}]
  (let [{:keys [width height]} screen-bounds]
    {:fx/type :stage
     :showing show-chat-window
     :title (str u/app-name " Battle Chat")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :pop-out-chat}
     :width (min chat-window-width width)
     :height (min chat-window-height height)
     :scene
     {:fx/type :scene
      :stylesheets (skylobby.fx/stylesheet-urls css)
      :root
      {:fx/type fx.channel/channel-view
       :h-box/hgrow :always
       :channel-name channel-name
       :channels channels
       :chat-auto-scroll chat-auto-scroll
       :client-data client-data
       :hide-users true
       :message-draft (get message-drafts channel-name)
       :server-key server-key}}}))
