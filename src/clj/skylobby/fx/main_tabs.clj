(ns skylobby.fx.main-tabs
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.tab-pane :as fx.ext.tab-pane]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.battles-buttons :as fx.battles-buttons]
    [skylobby.fx.battles-table :as fx.battles-table]
    [skylobby.fx.console :as fx.console]
    [skylobby.fx.channel :as fx.channel]
    [skylobby.fx.channels :as fx.channels]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.matchmaking :as fx.matchmaking]
    [skylobby.fx.user :as fx.user]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.application Platform)))


(set! *warn-on-reflection* true)


(defn- focus-text-field [^javafx.scene.control.Tab tab]
  (when-let [content (.getContent tab)]
    (let [^javafx.scene.Node text-field (-> content (.lookupAll "#channel-text-field") first)]
      (log/info "Found text field" (.getId text-field))
      (Platform/runLater
        (fn []
          (.requestFocus text-field))))))


(defn- my-channels-view-impl
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        my-channels (fx/sub-val context get-in [:by-server server-key :my-channels])
        selected-tab-channel (fx/sub-val context :selected-tab-channel)
        my-channel-names (->> my-channels
                              keys
                              (remove u/battle-channel-name?)
                              sort)
        selected-index (if (contains? (set my-channel-names) selected-tab-channel)
                         (.indexOf ^java.util.List my-channel-names selected-tab-channel)
                         0)
        needs-focus (fx/sub-val context :needs-focus)]
    (if (seq my-channel-names)
      {:fx/type fx.ext.tab-pane/with-selection-props
       :props {:on-selected-item-changed {:event/type :spring-lobby/selected-item-changed-channel-tabs
                                          :server-key server-key}
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
             :style-class (concat ["tab"] (when (-> needs-focus (get server-key) (get "chat") (contains? channel-name)) ["skylobby-tab-focus"]))
             :closable (not (u/battle-channel-name? channel-name))
             :on-close-request {:event/type :spring-lobby/leave-channel
                                :channel-name channel-name
                                :client-data client-data}
             :on-selection-changed (fn [^javafx.event.Event ev] (focus-text-field (.getTarget ev)))
             :content
             {:fx/type ext-recreate-on-key-changed
              :key [server-key channel-name]
              :desc
              {:fx/type fx.channel/channel-view
               :channel-name channel-name
               :server-key server-key}}})
          my-channel-names)}}
      {:fx/type :pane})))

(defn my-channels-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :my-channels-view
      (my-channels-view-impl state))))


(def no-matchmaking-tab-ids
  ["battles" "chat" "console"])
(def matchmaking-tab-ids
  ["battles" "matchmaking" "chat" "console"])


(defn- main-tab-view-impl
  [{:fx/keys [context] :keys [server-key]}]
  (let [battle-as-tab (fx/sub-val context :battle-as-tab)
        filter-battles (fx/sub-val context :filter-battles)
        join-channel-name (fx/sub-val context :join-channel-name)
        pop-out-battle (fx/sub-val context :pop-out-battle)
        selected-tab-channel (fx/sub-val context :selected-tab-channel)
        selected-tab-main (fx/sub-val context :selected-tab-main)
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        battles (fx/sub-val context get-in [:by-server server-key :battles])
        channels (fx/sub-val context get-in [:by-server server-key :channels])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        compflags (fx/sub-val context get-in [:by-server server-key :compflags])
        matchmaking (u/matchmaking? {:compflags compflags})
        main-tab-ids (if matchmaking
                       matchmaking-tab-ids
                       no-matchmaking-tab-ids)
        in-battle (and battle-id
                       (not pop-out-battle))
        show-battle-tab (and in-battle battle-as-tab)
        main-tab-ids (concat (when show-battle-tab ["battle"]) main-tab-ids)
        selected-index (if (contains? (set main-tab-ids) selected-tab-main)
                         (.indexOf ^java.util.List main-tab-ids selected-tab-main)
                         0)
        users-view {:fx/type fx.user/users-view
                    :v-box/vgrow :always
                    :server-key server-key}
        needs-focus (fx/sub-val context :needs-focus)]
    {:fx/type fx.ext.tab-pane/with-selection-props
     :props {:on-selected-item-changed {:event/type :spring-lobby/selected-item-changed-main-tabs
                                        :server-key server-key
                                        :selected-tab-channel selected-tab-channel}
             :selected-index (or selected-index 0)}
     :desc
     {:fx/type :tab-pane
      :tab-drag-policy :reorder
      :style {:-fx-font-size 16
              :-fx-min-height 164
              :-fx-pref-height 164}
      :tabs
      (concat
        (when show-battle-tab
          [{:fx/type :tab
            :graphic {:fx/type :label
                      :text (or (:battle-title (get battles battle-id))
                                "Battle")}
            :closable true
            :on-close-request {:event/type :spring-lobby/leave-battle
                               :client-data client-data
                               :consume true}
            :id "battle"
            :on-selection-changed (fn [^javafx.event.Event ev] (focus-text-field (.getTarget ev)))
            :content
            (if battle-id
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
                   :text "Waiting for battle details from server..."}]}]})}])
        [{:fx/type :tab
          :graphic {:fx/type :label
                    :text "Battles"}
          :closable false
          :id "battles"
          :content
          {:fx/type :v-box
           :children
           [
            {:fx/type :split-pane
             :v-box/vgrow :always
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
                {:fx/type fx.battles-table/battles-table
                 :v-box/vgrow :always
                 :server-key server-key}]}
              users-view]}
            {:fx/type fx.battles-buttons/battles-buttons-view
             :server-key server-key}]}}]
        (when matchmaking
          [{:fx/type :tab
            :graphic {:fx/type :label
                      :text "Matchmaking"}
            :closable false
            :id "matchmaking"
            :content
            {:fx/type :split-pane
             :divider-positions [0.75]
             :items
             [
              {:fx/type fx.matchmaking/matchmaking-view
               :server-key server-key}
              users-view]}}])
        [{:fx/type :tab
          :graphic {:fx/type :label
                    :text "Chat"}
          :closable false
          :id "chat"
          :style-class (concat ["tab"] (when (contains? (get needs-focus server-key) "chat") ["skylobby-tab-focus"]))
          :content
          {:fx/type :split-pane
           :divider-positions [0.70 0.9]
           :items
           [{:fx/type my-channels-view
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
                             :client-data client-data}}]}]}]}}
         {:fx/type :tab
          :graphic {:fx/type :label
                    :text "Console"}
          :closable false
          :id "console"
          :content
          {:fx/type fx.console/console-view
           :server-key server-key}}])}}))

(defn main-tab-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :main-tab-view
      (main-tab-view-impl state))))
