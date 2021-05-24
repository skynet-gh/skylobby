(ns skylobby.fx.main-tabs
  (:require
    [cljfx.ext.tab-pane :as fx.ext.tab-pane]
    [clojure.string :as string]
    [skylobby.fx.battles-table :as fx.battles-table]
    [skylobby.fx.console :as fx.console]
    [skylobby.fx.channel :as fx.channel]
    [skylobby.fx.channels :as fx.channels]
    [skylobby.fx.user :as fx.user]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (javafx.application Platform)))


(defn- focus-text-field [^javafx.scene.control.Tab tab]
  (when-let [content (.getContent tab)]
    (let [^javafx.scene.Node text-field (-> content (.lookupAll "#channel-text-field") first)]
      (log/info "Found text field" (.getId text-field))
      (Platform/runLater
        (fn []
          (.requestFocus text-field))))))

(def my-channels-view-keys
  [:channels :chat-auto-scroll :client-data :message-drafts :my-channels :selected-tab-channel
   :server-key])

(defn- my-channels-view
  [{:keys [channels chat-auto-scroll client-data message-drafts my-channels selected-tab-channel
           server-key]}]
  (let [my-channel-names (->> my-channels
                              keys
                              (remove u/battle-channel-name?)
                              sort)
        selected-index (if (contains? (set my-channel-names) selected-tab-channel)
                         (.indexOf ^java.util.List my-channel-names selected-tab-channel)
                         0)]
    (if (seq my-channel-names)
      {:fx/type fx.ext.tab-pane/with-selection-props
       :props
       {:on-selected-item-changed {:event/type :spring-lobby/selected-item-changed-channel-tabs}
        :selected-index selected-index}
       :desc
       {:fx/type :tab-pane
        :on-tabs-changed {:event/type :spring-lobby/my-channels-tab-action}
        :style {:-fx-font-size 16}
        :tabs
        (map
          (fn [channel-name]
            {:fx/type :tab
             :graphic {:fx/type :label
                       :text (str channel-name)}
             :id channel-name
             :closable (not (u/battle-channel-name? channel-name))
             :on-close-request {:event/type :spring-lobby/leave-channel
                                :channel-name channel-name
                                :client-data client-data}
             :on-selection-changed (fn [^javafx.event.Event ev] (focus-text-field (.getTarget ev)))
             :content
             {:fx/type fx.channel/channel-view
              :channel-name channel-name
              :channels channels
              :chat-auto-scroll chat-auto-scroll
              :client-data client-data
              :message-draft (get message-drafts channel-name)
              :server-key server-key}})
          my-channel-names)}}
      {:fx/type :pane})))


(def main-tab-ids
  ["battles" "chat" "console"])
(def main-tab-id-set (set main-tab-ids))


(def main-tab-view-state-keys
  (concat
    fx.battles-table/battles-table-state-keys
    [:filter-battles :filter-users :join-channel-name :selected-tab-channel :selected-tab-main]))

(def main-tab-view-keys
  (concat
    fx.battles-table/battles-table-keys
    fx.console/console-view-keys
    fx.user/users-table-keys
    [:battles :client-data :channels :filter-battles :filter-users :join-channel-name :selected-tab-channel :selected-tab-main
     :server :users]))

(defn main-tab-view
  [{:keys [battles client-data channels filter-battles filter-users join-channel-name
           selected-tab-main server-key users]
    :as state}]
  (let [selected-index (if (contains? (set main-tab-ids) selected-tab-main)
                         (.indexOf ^java.util.List main-tab-ids selected-tab-main)
                         0)
        users-view {:fx/type :v-box
                    :children
                    [{:fx/type :h-box
                      :alignment :center-left
                      :children
                      (concat
                        [
                         {:fx/type :label
                          :text (str "Users (" (count users) ")")}
                         {:fx/type :pane
                          :h-box/hgrow :always}
                         {:fx/type :label
                          :text (str " Filter: ")}
                         {:fx/type :text-field
                          :text (str filter-users)
                          :on-text-changed {:event/type :spring-lobby/assoc
                                            :key :filter-users}}]
                        (when-not (string/blank? filter-users)
                          [{:fx/type :button
                            :on-action {:event/type :spring-lobby/dissoc
                                        :key :filter-users}
                            :graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-close:16:white"}}]))}
                     (merge
                       {:fx/type fx.user/users-table
                        :v-box/vgrow :always}
                       (select-keys state fx.user/users-table-keys))]}]
    {:fx/type fx.ext.tab-pane/with-selection-props
     :props
     (merge
       {:on-selected-item-changed {:event/type :spring-lobby/selected-item-changed-main-tabs}}
       (when (< selected-index (count main-tab-ids))
         {:selected-index selected-index}))
     :desc
     {:fx/type :tab-pane
      :tab-drag-policy :reorder
      :style {:-fx-font-size 16
              :-fx-min-height 164
              :-fx-pref-height 164}
      :tabs
      [
       {:fx/type :tab
        :graphic {:fx/type :label
                  :text "Battles"}
        :closable false
        :id "battles"
        :content
        {:fx/type :split-pane
         :divider-positions [0.75]
         :items
         [
          {:fx/type :v-box
           :children
           [{:fx/type :h-box
             :alignment :center-left
             :children
             (concat
               [
                {:fx/type :label
                 :text (str "Battles (" (count battles) ")")}
                {:fx/type :pane
                 :h-box/hgrow :always}
                {:fx/type :label
                 :text (str " Filter: ")}
                {:fx/type :text-field
                 :text (str filter-battles)
                 :on-text-changed {:event/type :spring-lobby/assoc
                                   :key :filter-battles}}]
               (when-not (string/blank? filter-battles)
                 [{:fx/type :button
                   :on-action {:event/type :spring-lobby/dissoc
                               :key :filter-battles}
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-close:16:white"}}]))}
            (merge
              {:fx/type fx.battles-table/battles-table
               :v-box/vgrow :always}
              (select-keys state fx.battles-table/battles-table-keys))]}
          users-view]}}
       {:fx/type :tab
        :graphic {:fx/type :label
                  :text "Chat"}
        :closable false
        :id "chat"
        :content
        {:fx/type :split-pane
         :divider-positions [0.70 0.9]
         :items
         [(merge
            {:fx/type my-channels-view}
            (select-keys state my-channels-view-keys))
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
                           :client-data client-data}}]}]}]}}
       {:fx/type :tab
        :graphic {:fx/type :label
                  :text "Console"}
        :closable false
        :id "console"
        :content
        (merge
          {:fx/type fx.console/console-view}
          (select-keys state fx.console/console-view-keys))}]}}))
