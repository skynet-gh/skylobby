(ns skylobby.fx.user
  (:require
    [clojure.string :as string]
    [skylobby.fx.ext :refer [ext-table-column-auto-size]]
    [skylobby.fx.flag-icon :as flag-icon]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]))


(def users-table-state-keys [:ignore-users])

(def users-table-keys
  (concat
    users-table-state-keys
    [:battles :client-data :filter-users :server-key :users]))

(defn users-table [{:keys [battles client-data ignore-users server-key users]}]
  (let [battles-by-users (->> battles
                              vals
                              (mapcat
                                (fn [battle]
                                  (map
                                    (fn [[username _status]]
                                      [username battle])
                                    (:users battle))))
                              (into {}))]
    {:fx/type ext-table-column-auto-size
     :items (->> users
                 vals
                 (filter :username)
                 (sort-by :username String/CASE_INSENSITIVE_ORDER)
                 vec)
     :desc
     {:fx/type :table-view
      :style {:-fx-font-size 15}
      :column-resize-policy :constrained ; TODO auto resize
      :row-factory
      {:fx/cell-type :table-row
       :describe (fn [{:keys [client-status country user-agent user-id username]}]
                   (let [{:keys [battle-id battle-title] :as battle} (get battles-by-users username)]
                     (merge
                       {:on-mouse-clicked
                        {:event/type :spring-lobby/on-mouse-clicked-users-row
                         :server-key server-key
                         :username username}
                        :context-menu
                        {:fx/type :context-menu
                         :items
                         (concat
                           [{:fx/type :menu-item
                             :text "Message"
                             :on-action {:event/type :spring-lobby/join-direct-message
                                         :server-key server-key
                                         :username username}}]
                           (when battle
                             [{:fx/type :menu-item
                               :text "Join Battle"
                               :on-action {:event/type :spring-lobby/join-battle
                                           :battle battle
                                           :client-data client-data
                                           :selected-battle battle-id}}])
                           (when (= "SLDB" username)
                             [{:fx/type :menu-item
                               :text "!help"
                               :on-action {:event/type :spring-lobby/send-message
                                           :client-data client-data
                                           :channel-name (u/user-channel username)
                                           :message "!help"}}
                              {:fx/type :menu-item
                               :text "!ranking"
                               :on-action {:event/type :spring-lobby/send-message
                                           :client-data client-data
                                           :channel-name (u/user-channel username)
                                           :message "!ranking"}}
                              {:fx/type :menu-item
                               :text "!set privacyMode 0"
                               :on-action {:event/type :spring-lobby/send-message
                                           :client-data client-data
                                           :channel-name (u/user-channel username)
                                           :message "!set privacyMode 0"}}])
                           [(if (-> ignore-users (get server-key) (get username))
                              {:fx/type :menu-item
                               :text "Unignore"
                               :on-action {:event/type :spring-lobby/unignore-user
                                           :server-key server-key
                                           :username username}}
                              {:fx/type :menu-item
                               :text "Ignore"
                               :on-action {:event/type :spring-lobby/ignore-user
                                           :server-key server-key
                                           :username username}})
                            {:fx/type :menu-item
                             :text (str "User ID: " user-id)}])}}
                       {:tooltip
                        {:fx/type :tooltip
                         :style {:-fx-font-size 16}
                         :show-delay [10 :ms]
                         :text (str username "\n\n"
                                    (when (:access client-status)
                                      "Admin\n")
                                    "ID: " user-id "\n"
                                    "Country: " country "\n"
                                    "Rank: " (:rank client-status) "\n"
                                    "Lobby: " user-agent "\n"
                                    (when battle
                                      (str "\nBattle: " battle-title)))}})))}
      :columns
      [{:fx/type :table-column
        :text "Username"
        :resizable true
        :pref-width 200
        :cell-value-factory :username
        :cell-factory
        {:fx/cell-type :table-cell
         :describe
         (fn [username]
           {:text (str username)})}}
       {:fx/type :table-column
        :sortable false
        :text "Status"
        :resizable false
        :pref-width 56
        :cell-value-factory #(select-keys (:client-status %) [:bot :access :away :ingame])
        :cell-factory
        {:fx/cell-type :table-cell
         :describe
         (fn [status]
           {:text ""
            :graphic
            {:fx/type :h-box
             :children
             (concat
               [{:fx/type font-icon/lifecycle
                 :icon-literal
                 (str
                   "mdi-"
                   (cond
                     (:bot status) "robot"
                     (:access status) "account-key"
                     :else "account")
                   ":16:"
                   (cond
                     (:bot status) "grey"
                     (:access status) "orange"
                     :else "white"))}]
               (when (:ingame status)
                 [{:fx/type font-icon/lifecycle
                   :icon-literal "mdi-sword:16:red"}])
               (when (:away status)
                 [{:fx/type font-icon/lifecycle
                   :icon-literal "mdi-sleep:16:grey"}]))}})}}
       {:fx/type :table-column
        :text "Country"
        :resizable false
        :pref-width 64
        :cell-value-factory :country
        :cell-factory
        {:fx/cell-type :table-cell
         :describe
         (fn [country]
           {:text ""
            :graphic
            {:fx/type flag-icon/flag-icon
             :country-code country}})}}
       #_
       {:fx/type :table-column
        :text "Rank"
        :resizable false
        :pref-width 64
        :cell-value-factory (comp :rank :client-status)
        :cell-factory
        {:fx/cell-type :table-cell
         :describe (fn [rank] {:text (str rank)})}}
       {:fx/type :table-column
        :text "Lobby Client"
        :resizable true
        :pref-width 200
        :cell-value-factory :user-agent
        :cell-factory
        {:fx/cell-type :table-cell
         :describe (fn [user-agent] {:text (str user-agent)})}}]}}))


(defn users-view [{:keys [filter-users users] :as state}]
  (let [
        bot-or-human (group-by (comp boolean :bot :client-status) (vals users))
        bot-count (count (get bot-or-human true))
        human-count (count (get bot-or-human false))
        filter-lc (when-not (string/blank? filter-users)
                    (string/lower-case filter-users))
        filtered-users (->> users
                            (filter
                              (fn [[username {:keys [user-agent]}]]
                                (if filter-lc
                                  (or (and (not (string/blank? username))
                                           (string/includes? (string/lower-case username) filter-lc))
                                      (and (not (string/blank? user-agent))
                                           (string/includes? (string/lower-case user-agent) filter-lc)))
                                  true))))]
    {:fx/type :v-box
     :children
     [{:fx/type :h-box
       :alignment :center-left
       :children
       (concat
         [
          {:fx/type :label
           :text (str "Users (" human-count ")  Bots (" bot-count ")")}
          {:fx/type :pane
           :h-box/hgrow :always}
          {:fx/type :label
           :text (str " Filter"
                      (when-not (string/blank? filter-users)
                        (str " (" (count filtered-users) ")"))
                      ": ")}
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
        {:fx/type users-table}
        state
        {:users filtered-users})]}))
