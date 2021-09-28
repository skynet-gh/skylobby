(ns skylobby.fx.chat
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.channel :as fx.channel]
    [skylobby.fx.channels :as fx.channels]
    [skylobby.fx.main-tabs :as fx.main-tabs]
    [skylobby.fx.user :as fx.user]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]))


(def window-key :chat)


(def chat-window-width 1600)
(def chat-window-height 1000)

(def chat-window-keys
  (concat
    [:chat-auto-scroll :css :join-channel-name :screen-bounds :show-chat-window :window-states]
    fx.channel/channel-state-keys))

(defn chat-window
  [{:keys [channels client-data css join-channel-name screen-bounds
           server-key show-chat-window window-states]
    :as state}]
  (let [{:keys [width height]} screen-bounds
        users-view (merge
                     {:fx/type fx.user/users-view
                      :v-box/vgrow :always}
                     (select-keys state fx.user/users-table-keys))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (partial skylobby.fx/add-maximized-listener window-key)
     :desc
     {:fx/type :stage
      :showing show-chat-window
      :title (str u/app-name " Chat " server-key)
      :icons skylobby.fx/icons
      :on-close-request {:event/type :spring-lobby/dissoc
                         :key :show-chat-window}
      :maximized (get-in window-states [window-key :maximized] false)
      :x (get-in window-states [window-key :x] 0)
      :y (get-in window-states [window-key :y] 0)
      :width ((fnil min chat-window-width) width (get-in window-states [window-key :width] chat-window-width))
      :height ((fnil min chat-window-height) height (get-in window-states [window-key :height] chat-window-height))
      :on-width-changed (partial skylobby.fx/window-changed window-key :width)
      :on-height-changed (partial skylobby.fx/window-changed window-key :height)
      :on-x-changed (partial skylobby.fx/window-changed window-key :x)
      :on-y-changed (partial skylobby.fx/window-changed window-key :y)
      :scene
      {:fx/type :scene
       :stylesheets (skylobby.fx/stylesheet-urls css)
       :root
       {:fx/type :split-pane
        :divider-positions [0.70 0.9]
        :items
        [(merge
           {:fx/type fx.main-tabs/my-channels-view}
           (select-keys state fx.main-tabs/my-channels-view-keys))
         users-view
         {:fx/type :v-box
          :children
          [{:fx/type :label
            :text (str "Channels (" (->> channels vals u/non-battle-channels count) ")")}
           (merge
             {:fx/type fx.channels/channels-table
              :v-box/vgrow :always}
             (select-keys state fx.channels/channels-table-keys))
           {:fx/type :h-box
            :alignment :center-left
            :children
            [
             {:fx/type :button
              :text ""
              :on-action {:event/type :spring-lobby/join-channel
                          :channel-name join-channel-name
                          :client-data client-data}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-plus:20:white"}}
             {:fx/type :text-field
              :text join-channel-name
              :prompt-text "New Channel"
              :on-text-changed {:event/type :spring-lobby/assoc-in
                                :path [:by-server server-key :join-channel-name]}
              :on-action {:event/type :spring-lobby/join-channel
                          :channel-name join-channel-name
                          :client-data client-data}}]}]}]}}}}))
