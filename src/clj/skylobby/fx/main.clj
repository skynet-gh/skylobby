(ns skylobby.fx.main
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.tab-pane :as fx.ext.tab-pane]
    skylobby.fx
    [skylobby.fx.server-tab :as fx.server-tab]
    [skylobby.fx.welcome :as fx.welcome]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(defn main-window-impl
  [{:fx/keys [context]}]
  (let [valid-server-keys (fx/sub-ctx context skylobby.fx/valid-server-keys-sub)
        tab-ids (concat ["local"] valid-server-keys #_(when (seq valid-server-keys) ["multi"]))
        tab-id-set (set tab-ids)
        selected-server-tab (fx/sub-val context :selected-server-tab)
        selected-index (or (when (contains? tab-id-set selected-server-tab)
                             (.indexOf ^java.util.List tab-ids selected-server-tab))
                           0)
        needs-focus (fx/sub-val context :needs-focus)
        highlight-tabs-with-new-battle-messages (fx/sub-val context :highlight-tabs-with-new-battle-messages)
        highlight-tabs-with-new-chat-messages (fx/sub-val context :highlight-tabs-with-new-chat-messages)
        selected-tab-main (fx/sub-val context :selected-tab-main)
        selected-tab-channel (fx/sub-val context :selected-tab-channel)
        mute (fx/sub-val context :mute)]
    {:fx/type :v-box
     :style {:-fx-font-size 14}
     :alignment :top-left
     :children
     [
      {:fx/type fx.ext.tab-pane/with-selection-props
       :v-box/vgrow :always
       :props
       {:on-selected-item-changed {:event/type :spring-lobby/selected-item-changed-server-tabs
                                   :selected-tab-channel selected-tab-channel
                                   :selected-tab-main selected-tab-main}
        :selected-index selected-index}
       :desc
       {:fx/type :tab-pane
        :tabs
        (concat
          [{:fx/type :tab
            :id "local"
            :closable false
            :graphic {:fx/type :label
                      :text "local"
                      :style {:-fx-font-size 18}}
            :content
            {:fx/type fx.welcome/welcome-view
             :v-box/vgrow :always}}]
          (mapv
            (fn [server-key]
              (let [classes (if (and (not= server-key selected-server-tab)
                                     (contains? needs-focus server-key)
                                     (or (and highlight-tabs-with-new-battle-messages
                                              (contains? (get needs-focus server-key) "battle")
                                              (not (get-in mute [server-key "battle" :battle])))
                                         (and highlight-tabs-with-new-chat-messages
                                              (contains? (get needs-focus server-key) "chat")
                                              (some (fn [channel-name] (not (contains? (get mute server-key) channel-name)))
                                                    (keys (get-in needs-focus [server-key "chat"]))))))
                                ["tab" "skylobby-tab-focus"]
                                ["tab"])]
                {:fx/type :tab
                 :id (str server-key)
                 :style-class classes
                 :graphic {:fx/type :label
                           :text (str (let [[_ server-config] (fx/sub-val context get-in [:by-server server-key :server])]
                                        (:alias server-config))
                                      " (" server-key ")")
                           :style {:-fx-font-size 18}}
                 :on-close-request {:event/type :spring-lobby/disconnect
                                    :server-key server-key}
                 :content
                 {:fx/type fx.server-tab/server-tab
                  :server-key server-key}}))
            valid-server-keys)
          #_
          (when (seq valid-servers)
            [{:fx/type :tab
              :id "multi"
              :closable false
              :graphic {:fx/type :label
                        :text "All Servers"
                        :style {:-fx-font-size 18}}
              :content
              (merge
                {:fx/type multi-server-tab}
                (select-keys state [:map-details :mod-details]))}]))}}]}))

(defn main-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :main-window
      (main-window-impl state))))
