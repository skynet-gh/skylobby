(ns skylobby.fx.main-tabs
  (:require
    [cljfx.ext.tab-pane :as fx.ext.tab-pane]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [clojure.string :as string]
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

(def battles-table-keys
  [:battle :battle-password :battles :client-data :selected-battle :users])

(defn- battles-table [{:keys [battle battle-password battles client-data selected-battle users]}]
  {:fx/type fx.ext.table-view/with-selection-props
   :props {:selection-mode :single
           :on-selected-item-changed {:event/type :spring-lobby/select-battle
                                      :server-key (u/server-key client-data)}}
   :desc
   {:fx/type :table-view
    :style {:-fx-font-size 15}
    :column-resize-policy :constrained ; TODO auto resize
    :items (->> battles
                vals
                (filter :battle-title)
                (sort-by (juxt (comp count :users) :battle-spectators))
                reverse)
    :row-factory
    {:fx/cell-type :table-row
     :describe (fn [i]
                 {:on-mouse-clicked
                  {:event/type :spring-lobby/on-mouse-clicked-battles-row
                   :battle battle
                   :battle-password battle-password
                   :client-data client-data
                   :selected-battle selected-battle
                   :battle-passworded (= "1" (-> battles (get selected-battle) :battle-passworded))}
                  :tooltip
                  {:fx/type :tooltip
                   :style {:-fx-font-size 16}
                   :show-delay [10 :ms]
                   :text (->> i
                              :users
                              keys
                              (sort String/CASE_INSENSITIVE_ORDER)
                              (string/join "\n")
                              (str "Players:\n\n"))}
                  :context-menu
                  {:fx/type :context-menu
                   :items
                   [{:fx/type :menu-item
                     :text "Join Battle"
                     :on-action {:event/type :spring-lobby/join-battle
                                 :battle battle
                                 :client-data client-data
                                 :selected-battle (:battle-id i)}}]}})}
    :columns
    [
     {:fx/type :table-column
      :text "Game"
      :pref-width 200
      :cell-value-factory :battle-modname
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [battle-modname] {:text (str battle-modname)})}}
     {:fx/type :table-column
      :text "Status"
      :resizable false
      :pref-width 56
      :cell-value-factory identity
      :cell-factory
      {:fx/cell-type :table-cell
       :describe
       (fn [status]
         (cond
           (or (= "1" (:battle-passworded status))
               (= "1" (:battle-locked status)))
           {:text ""
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-lock:16:yellow"}}
           (->> status :host-username (get users) :client-status :ingame)
           {:text ""
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-sword:16:red"}}
           :else
           {:text ""
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-checkbox-blank-circle-outline:16:green"}}))}}
     {:fx/type :table-column
      :text "Map"
      :pref-width 200
      :cell-value-factory :battle-map
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [battle-map] {:text (str battle-map)})}}
     {:fx/type :table-column
      :text "Play (Spec)"
      :resizable false
      :pref-width 100
      :cell-value-factory (juxt (comp count :users) #(or (u/to-number (:battle-spectators %)) 0))
      :cell-factory
      {:fx/cell-type :table-cell
       :describe
       (fn [[total-user-count spec-count]]
         {:text (str (if (and (number? total-user-count) (number? spec-count))
                       (- total-user-count spec-count)
                       total-user-count)
                     " (" spec-count ")")})}}
     {:fx/type :table-column
      :text "Battle Name"
      :pref-width 100
      :cell-value-factory :battle-title
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [battle-title] {:text (str battle-title)})}}
     {:fx/type :table-column
      :text "Country"
      :resizable false
      :pref-width 64
      :cell-value-factory #(:country (get users (:host-username %)))
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [country] {:text (str country)})}}
     {:fx/type :table-column
      :text "Host"
      :pref-width 100
      :cell-value-factory :host-username
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [host-username] {:text (str host-username)})}}
     #_
     {:fx/type :table-column
      :text "Engine"
      :cell-value-factory #(str (:battle-engine %) " " (:battle-version %))
      :cell-factory
      {:fx/cell-type :table-cell
       :describe (fn [engine] {:text (str engine)})}}]}})

(def main-tab-ids
  ["battles" "chat" "console"])
(def main-tab-id-set (set main-tab-ids))


(def main-tab-view-keys
  (concat
    battles-table-keys
    fx.console/console-view-keys
    fx.user/users-table-keys
    [:battles :client-data :channels :join-channel-name :selected-tab-channel :selected-tab-main
     :server :users]))

(defn main-tab-view
  [{:keys [battles client-data channels join-channel-name selected-tab-main server-key users]
    :as state}]
  (let [selected-index (if (contains? (set main-tab-ids) selected-tab-main)
                         (.indexOf ^java.util.List main-tab-ids selected-tab-main)
                         0)
        users-view {:fx/type :v-box
                    :children
                    [{:fx/type :label
                      :text (str "Users (" (count users) ")")}
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
         :divider-positions [0.80]
         :items
         [
          {:fx/type :v-box
           :children
           [{:fx/type :label
             :text (str "Battles (" (count battles) ")")}
            (merge
              {:fx/type battles-table
               :v-box/vgrow :always}
              (select-keys state battles-table-keys))]}
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
