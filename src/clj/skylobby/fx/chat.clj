(ns skylobby.fx.chat
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.channels :as fx.channels]
    [skylobby.fx.main-tabs :as fx.main-tabs]
    [skylobby.fx.user :as fx.user]
    [skylobby.util :as u]
    [spring-lobby.fx.font-icon :as font-icon]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def window-key :chat)


(def chat-window-width 1600)
(def chat-window-height 1000)


(defn chat-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        window-states (fx/sub-val context :window-states)
        server-key (fx/sub-ctx context skylobby.fx/selected-tab-server-key-sub)
        show (boolean (fx/sub-val context :show-chat-window))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (partial skylobby.fx/add-maximized-listener window-key)
     :desc
     {:fx/type :stage
      :showing show
      :title (str u/app-name " Chat " server-key)
      :icons skylobby.fx/icons
      :on-close-request {:event/type :spring-lobby/dissoc
                         :key :show-chat-window}
      :maximized (get-in window-states [window-key :maximized] false)
      :x (skylobby.fx/fitx screen-bounds (get-in window-states [window-key :x]))
      :y (skylobby.fx/fity screen-bounds (get-in window-states [window-key :y]))
      :width (skylobby.fx/fitwidth screen-bounds (get-in window-states [window-key :width]) chat-window-width)
      :height (skylobby.fx/fitheight screen-bounds (get-in window-states [window-key :height]) chat-window-height)
      :on-width-changed (partial skylobby.fx/window-changed window-key :width)
      :on-height-changed (partial skylobby.fx/window-changed window-key :height)
      :on-x-changed (partial skylobby.fx/window-changed window-key :x)
      :on-y-changed (partial skylobby.fx/window-changed window-key :y)
      :scene
      {:fx/type :scene
       :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
       :root
       (if show
         (let [
               join-channel-name (fx/sub-val context :join-channel-name)
               channels (fx/sub-val context get-in [:by-server server-key :channels])
               client-data (fx/sub-val context get-in [:by-server server-key :client-data])
               users-view {:fx/type fx.user/users-view
                           :server-key server-key
                           :v-box/vgrow :always}]
           {:fx/type :split-pane
            :divider-positions [0.70 0.9]
            :items
            [{:fx/type fx.main-tabs/my-channels-view
              :server-key server-key}
             users-view
             {:fx/type :v-box
              :children
              [{:fx/type :label
                :text (str "Channels (" (->> channels vals u/non-battle-channels count) ")")}
               {:fx/type fx.channels/channels-table
                :v-box/vgrow :always
                :server-key server-key}
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
                              :client-data client-data}}]}]}]})
         {:fx/type :pane
          :pref-width chat-window-width
          :pref-height chat-window-height})}}}))

(defn chat-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :chat-window
      (chat-window-impl state))))
