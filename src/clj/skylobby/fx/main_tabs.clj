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
    [skylobby.fx.engine-sync :refer [engine-sync-pane]]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.map-sync :refer [map-sync-pane]]
    [skylobby.fx.matchmaking :as fx.matchmaking]
    [skylobby.fx.minimap :as fx.minimap]
    [skylobby.fx.mod-sync :refer [mod-sync-pane]]
    [skylobby.fx.players-table :refer [players-table]]
    [skylobby.fx.user :as fx.user]
    [skylobby.util :as u]
    [spring-lobby.spring :as spring]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.application Platform)))


(set! *warn-on-reflection* true)


(defn- focus-text-field
  ([^javafx.scene.control.Tab tab]
   (focus-text-field tab "#channel-text-field"))
  ([^javafx.scene.control.Tab tab id-str]
   (when-let [content (.getContent tab)]
     (when-let [^javafx.scene.Node text-field (-> content (.lookupAll id-str) first)]
       (log/info "Found text field" (.getId text-field))
       (Platform/runLater
         (fn []
           (.requestFocus text-field)))))))


(defn my-channels-view-impl
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        highlight-tabs-with-new-chat-messages (fx/sub-val context :highlight-tabs-with-new-chat-messages)
        my-channels (fx/sub-val context get-in [:by-server server-key :my-channels])
        selected-tab-channel (fx/sub-val context get-in [:selected-tab-channel server-key])
        ignore-users (fx/sub-val context get-in [:ignore-users server-key])
        ignore-channels-set (->> ignore-users
                                 (filter second)
                                 (map first)
                                 (map u/user-channel-name)
                                 set)
        my-channel-names (->> my-channels
                              keys
                              (remove u/battle-channel-name?)
                              (remove ignore-channels-set)
                              sort)
        selected-index (if (contains? (set my-channel-names) selected-tab-channel)
                         (.indexOf ^java.util.List my-channel-names selected-tab-channel)
                         0)
        needs-focus (fx/sub-val context :needs-focus)
        mute (fx/sub-val context :mute)]
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
             :style-class (concat ["tab"] (when (and highlight-tabs-with-new-chat-messages
                                                     (not= selected-tab-channel channel-name)
                                                     (-> needs-focus (get server-key) (get "chat") (contains? channel-name))
                                                     (not (contains? (get mute server-key) channel-name)))
                                            ["skylobby-tab-focus"]))
             :closable (not (u/battle-channel-name? channel-name))
             :on-close-request {:event/type :spring-lobby/leave-channel
                                :channel-name channel-name
                                :server-key server-key}
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
  ["battles" "chat" "console" "matchmaking"])

(defn old-battle-tab-id [battle-id]
  (str "old-battle-" battle-id))

(defn battle-details [{:fx/keys [context] :keys [server-key]}]
  (let [selected-battle-id (fx/sub-val context get-in [:by-server server-key :selected-battle])
        selected-battle-details (fx/sub-val context get-in [:by-server server-key :battles selected-battle-id])
        users (fx/sub-val context get-in [:by-server server-key :users])
        user-details (fx/sub-val context get-in [:by-server server-key :battles selected-battle-id :user-details])
        bot-details (->> user-details
                         (filter (comp #(string/includes? % "(bot)") :username second))
                         (map (fn [[k v]]
                                (let [[_all bot-name] (re-find #"(.*)\s+\(bot\)" k)]
                                  [bot-name v])))
                         (into {}))
        selected-battle-details (-> selected-battle-details
                                    (update :users
                                      (fn [users]
                                        (reduce-kv
                                          (fn [m k v]
                                            (update m k merge v (get user-details k)))
                                          {}
                                          users))))
        selected-battle-details (update selected-battle-details :bots merge bot-details)
        server-url (fx/sub-val context get-in [:by-server server-key :client-data :server-url])
        spring-root (fx/sub-ctx context skylobby.fx/spring-root-sub server-url)
        engine-version (:battle-version selected-battle-details)
        map-name (:battle-map selected-battle-details)
        mod-name (:battle-modname selected-battle-details)
        minimap-size (fx/sub-val context :minimap-size)
        minimap-size (or (u/to-number minimap-size)
                         fx.minimap/default-minimap-size)
        {:keys [mods-by-name]} (fx/sub-ctx context skylobby.fx/spring-resources-sub spring-root)
        indexed-mod (get mods-by-name mod-name)
        mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        sides (spring/mod-sides mod-details)
        minimap-type (fx/sub-val context :minimap-type)
        height (+ minimap-size 80)]
    {:fx/type ext-recreate-on-key-changed
     :key (str minimap-size)
     :desc
     {:fx/type :h-box
      :pref-height height
      :max-height height
      :children
      [
       {:fx/type :v-box
        :h-box/hgrow :always
        :children
        [{:fx/type :label
          :style {:-fx-font-size 20}
          :text (str (:battle-title selected-battle-details))}
         {:fx/type players-table
          :players (fx.battle/battle-players-and-bots
                     {:battle selected-battle-details
                      :users users})
          :read-only true
          :server-key server-key
          :sides sides}]}
       {:fx/type :flow-pane
        :vgap 5
        :hgap 5
        :padding 5
        :children
        [
         {:fx/type engine-sync-pane
          :engine-version engine-version
          :spring-isolation-dir spring-root}
         {:fx/type mod-sync-pane
          :engine-version engine-version
          :index-only true
          :mod-name mod-name
          :spring-isolation-dir spring-root}
         {:fx/type map-sync-pane
          :index-only true
          :map-name map-name
          :spring-isolation-dir spring-root}]}
       {:fx/type :v-box
        :children
        [
         {:fx/type fx.minimap/minimap-pane
          :map-name map-name
          :server-key server-key}
         {:fx/type :label
          :text (str "Map: " map-name)}
         {:fx/type :flow-pane
          :children
          [
           {:fx/type :label
            :text (str " Display (px): ")}
           {:fx/type :combo-box
            :value minimap-size
            :items fx.minimap/minimap-sizes
            :on-value-changed {:event/type :spring-lobby/assoc
                               :key :minimap-size}}
           {:fx/type :combo-box
            :value minimap-type
            :items fx.battle/minimap-types
            :on-value-changed {:event/type :spring-lobby/assoc
                               :key :minimap-type}}]}]}]}}))

(defn main-tab-view-impl
  [{:fx/keys [context] :keys [server-key]}]
  (let [battle-as-tab (fx/sub-val context :battle-as-tab)
        join-channel-name (fx/sub-val context get-in [:by-server server-key :join-channel-name])
        pop-out-battle (fx/sub-val context :pop-out-battle)
        selected-tab-channel (fx/sub-val context get-in [:selected-tab-channel server-key])
        selected-tab-main (fx/sub-val context get-in [:selected-tab-main server-key])
        selected-server-tab (fx/sub-val context :selected-server-tab)
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        battles (fx/sub-val context get-in [:by-server server-key :battles])
        channels (fx/sub-val context get-in [:by-server server-key :channels])
        friend-requests (fx/sub-val context get-in [:by-server server-key :friend-requests])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        compflags (fx/sub-val context get-in [:by-server server-key :compflags])
        matchmaking (u/matchmaking? {:compflags compflags})
        main-tab-ids (if matchmaking
                       matchmaking-tab-ids
                       no-matchmaking-tab-ids)
        show-closed-battles (fx/sub-val context :show-closed-battles)
        old-battles (fx/sub-val context get-in [:by-server server-key :old-battles])
        in-battle (and battle-id
                       (not pop-out-battle))
        show-battle-tab (and in-battle battle-as-tab)
        main-tab-ids (concat
                       (when show-battle-tab ["battle"])
                       main-tab-ids
                       (when show-closed-battles
                         (map (comp old-battle-tab-id first) old-battles)))
        selected-index (if (contains? (set main-tab-ids) selected-tab-main)
                         (.indexOf ^java.util.List main-tab-ids selected-tab-main)
                         0)
        users-view {:fx/type fx.user/users-view
                    :v-box/vgrow :always
                    :server-key server-key}
        needs-focus (fx/sub-val context :needs-focus)
        ignore-users (fx/sub-val context get-in [:ignore-users server-key])
        ignore-channels-set (->> ignore-users
                                 (filter second)
                                 (map first)
                                 (map u/user-channel-name)
                                 set)
        highlight-tabs-with-new-battle-messages (fx/sub-val context :highlight-tabs-with-new-chat-messages)
        highlight-tabs-with-new-chat-messages (fx/sub-val context :highlight-tabs-with-new-chat-messages)
        mute (fx/sub-val context :mute)
        vertical (= "vertical" (fx/sub-val context :battles-layout))
        selected-battle-id (fx/sub-val context get-in [:by-server server-key :selected-battle])
        selected-battle-details (fx/sub-val context get-in [:by-server server-key :battles selected-battle-id])
        my-channels (fx/sub-val context get-in [:by-server server-key :my-channels])]
    {:fx/type fx.ext.tab-pane/with-selection-props
     :props {:on-selected-item-changed {:event/type :spring-lobby/selected-item-changed-main-tabs
                                        :server-key server-key
                                        :selected-tab-channel selected-tab-channel}
             :selected-index (or selected-index 0)}
     :desc
     {:fx/type :tab-pane
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
            :style-class (concat ["tab"] (when (and highlight-tabs-with-new-battle-messages
                                                    (not= selected-tab-main "battle")
                                                    (contains? (get needs-focus server-key) "battle")
                                                    (not (get-in mute [server-key :battle])))
                                           ["skylobby-tab-focus"]))
            :on-selection-changed (fn [^javafx.event.Event ev] (focus-text-field (.getTarget ev)))
            :content
            (cond
              (not= selected-server-tab server-key)
              {:fx/type :pane}
              battle-id
              {:fx/type fx.battle/battle-view
               :server-key server-key}
              :else
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
           (concat
             [
              {:fx/type :split-pane
               :orientation (if vertical
                              :vertical
                              :horizontal)
               :v-box/vgrow :always
               :divider-positions [(if vertical 0.75 0.99)]
               :items
               [
                {:fx/type fx.battles-table/battles-table
                 :v-box/vgrow :always
                 :server-key server-key}
                {:fx/type :h-box
                 :children
                 (concat
                   (when (and vertical selected-battle-details)
                     [{:fx/type battle-details
                       :server-key server-key
                       :h-box/hgrow :always}])
                   [(assoc users-view :h-box/hgrow :always)])}]}
              {:fx/type fx.battles-buttons/battles-buttons-view
               :server-key server-key}]
             (when (and (not vertical) selected-battle-details)
               [{:fx/type battle-details
                 :server-key server-key
                 :h-box/hgrow :always}]))}}]
        [{:fx/type :tab
          :graphic {:fx/type :label
                    :text (str "Chat" (when (seq friend-requests)
                                        (str " (" (count friend-requests) " friend requests)")))}
          :closable false
          :id "chat"
          :on-selection-changed (fn [^javafx.event.Event ev] (focus-text-field (.getTarget ev)))
          :style-class (concat ["tab"] (when (and highlight-tabs-with-new-chat-messages
                                                  (not= selected-tab-main "chat")
                                                  (contains? (get needs-focus server-key) "chat")
                                                  (some (fn [channel-name]
                                                          (and
                                                            (contains? my-channels channel-name)
                                                            (not (contains? (get mute server-key) channel-name))
                                                            (not (contains? ignore-channels-set channel-name))))
                                                        (keys (get-in needs-focus [server-key "chat"]))))
                                         ["skylobby-tab-focus"]))
          :content
          {:fx/type :split-pane
           :divider-positions [0.90]
           :items
           [{:fx/type my-channels-view
             :server-key server-key}
            {:fx/type :v-box
             :children
             [users-view
              {:fx/type :v-box
               :children
               (concat
                 [{:fx/type :label
                   :text (str "Friend Requests (" (count friend-requests) ")")}]
                 (when (seq friend-requests)
                   [{:fx/type :table-view
                     :items (or (keys friend-requests) [])
                     :columns
                     [{:fx/type :table-column
                       :text "Username"
                       :pref-width 300
                       :cell-value-factory identity
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe (fn [i] {:text (str i)})}}
                      {:fx/type :table-column
                       :text "Actions"
                       :resizable false
                       :pref-width 180
                       :cell-value-factory identity
                       :cell-factory
                       {:fx/cell-type :table-cell
                        :describe
                        (fn [i]
                          {:text ""
                           :graphic
                           {:fx/type :h-box
                            :children
                            [{:fx/type :button
                              :text "Accept"
                              :on-action {:event/type :spring-lobby/accept-friend-request
                                          :client-data client-data
                                          :username i}}
                             {:fx/type :button
                              :text "Decline"
                              :on-action {:event/type :spring-lobby/decline-friend-request
                                          :client-data client-data
                                          :username i}}]}})}}]}]))}
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
                               :server-key server-key}
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
                               :server-key server-key}}]}]}]}]}}
         {:fx/type :tab
          :graphic {:fx/type :label
                    :text "Console"}
          :closable false
          :id "console"
          :on-selection-changed (fn [^javafx.event.Event ev] (focus-text-field (.getTarget ev) "#console-text-field"))
          :content
          {:fx/type fx.console/console-view
           :server-key server-key}}]
        (when matchmaking
          [{:fx/type :tab
            :graphic {:fx/type :label
                      :text "Matchmaking"}
            :closable false
            :id "matchmaking"
            :content
            {:fx/type :split-pane
             :divider-positions [0.99]
             :items
             [
              {:fx/type fx.matchmaking/matchmaking-view
               :server-key server-key}
              users-view]}}])
       (when show-closed-battles
         (map
           (fn [[battle-id _battle]]
             {:fx/type :tab
              :graphic {:fx/type :label
                        :text (str "(old) battle " battle-id)}
              :closable true
              :on-close-request {:event/type :spring-lobby/dissoc-in
                                 :path [:by-server server-key :old-battles battle-id]}
              :id (old-battle-tab-id battle-id)
              :content
              {:fx/type fx.battle/battle-view
               :battle-id battle-id
               :server-key server-key}})
           old-battles)))}}))

(defn main-tab-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :main-tab-view
      (main-tab-view-impl state))))
