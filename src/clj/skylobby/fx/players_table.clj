(ns skylobby.fx.players-table
  (:require
    [clojure.string :as string]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.flag-icon :as flag-icon]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]))


(def allyteam-colors
  {0 "crimson"
   1 "royalblue"
   2 "goldenrod"
   3 "limegreen"
   4 "deeppink"
   5 "darkturquoise"
   6 "darkorange"
   7 "forestgreen"
   8 "brown"
   9 "teal"
   10 "purple"
   11 "goldenrod"})

(defn players-table
  [{:keys [am-host battle-players-color-allyteam channel-name client-data host-ingame host-username
           players scripttags server-key sides singleplayer username]}]
  (let [players-with-skill (map
                             (fn [{:keys [skill skilluncertainty username] :as player}]
                               (let [username-kw (when username (keyword (string/lower-case username)))
                                     tags (some-> scripttags :game :players (get username-kw))
                                     uncertainty (or (try (u/to-number skilluncertainty)
                                                          (catch Exception e
                                                            (log/debug e "Error parsing skill uncertainty")))
                                                     (try (u/to-number (:skilluncertainty tags))
                                                          (catch Exception e
                                                            (log/debug e "Error parsing skill uncertainty")))
                                                     3)]
                                 (assoc player
                                        :skill (or skill (:skill tags))
                                        :skilluncertainty uncertainty)))
                             players)]
    {:fx/type :table-view
     :column-resize-policy :constrained ; TODO auto resize
     :items (->> players-with-skill
                 (sort-by
                   (juxt
                     (comp not :mode :battle-status)
                     (comp :bot :client-status :user)
                     (comp :ally :battle-status)
                     (comp (fnil - 0) u/parse-skill :skill)
                     (comp :id :battle-status))))
     :style {:-fx-font-size 14
             :-fx-min-height 200}
     :row-factory
     {:fx/cell-type :table-row
      :describe (fn [{:keys [owner username user]}]
                  {
                   :context-menu
                   {:fx/type :context-menu
                    :items
                    (concat []
                      (when (and (not owner))
                        [
                         {:fx/type :menu-item
                          :text "Message"
                          :on-action {:event/type :spring-lobby/join-direct-message
                                      :server-key server-key
                                      :username username}}])
                      [{:fx/type :menu-item
                        :text "Ring"
                        :on-action {:event/type :spring-lobby/ring
                                    :client-data client-data
                                    :channel-name channel-name
                                    :username username}}]
                      (when (and host-username
                                 (= host-username username)
                                 (-> user :client-status :bot))
                        (concat
                          [{:fx/type :menu-item
                            :text "!help"
                            :on-action {:event/type :spring-lobby/send-message
                                        :client-data client-data
                                        :channel-name (u/user-channel host-username)
                                        :message "!help"
                                        :server-key server-key}}
                           {:fx/type :menu-item
                            :text "!status battle"
                            :on-action {:event/type :spring-lobby/send-message
                                        :client-data client-data
                                        :channel-name (u/user-channel host-username)
                                        :message "!status battle"
                                        :server-key server-key}}
                           {:fx/type :menu-item
                            :text "!status game"
                            :on-action {:event/type :spring-lobby/send-message
                                        :client-data client-data
                                        :channel-name (u/user-channel host-username)
                                        :message "!status game"
                                        :server-key server-key}}]
                          (when-not host-ingame
                            [{:fx/type :menu-item
                              :text "!stats"
                              :on-action {:event/type :spring-lobby/send-message
                                          :client-data client-data
                                          :channel-name (u/user-channel host-username)
                                          :message "!stats"
                                          :server-key server-key}}])))
                      (when (-> user :client-status :bot)
                        [{:fx/type :menu-item
                          :text "!whois"
                          :on-action {:event/type :spring-lobby/send-message
                                      :client-data client-data
                                      :channel-name (u/user-channel host-username)
                                      :message (str "!whois " username)
                                      :server-key server-key}}])
                      [{:fx/type :menu-item
                        :text (str "User ID: " (-> user :user-id))}])}})}
     :columns
     [{:fx/type :table-column
       :text "Nickname"
       :resizable true
       :pref-width 400
       :min-width 120
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [{:keys [owner] :as id}]
          (let [not-spec (-> id :battle-status :mode)]
            (merge
              {:text (u/nickname id)
               :style
               (merge
                 {:-fx-text-fill (if (and battle-players-color-allyteam not-spec)
                                     (get allyteam-colors (-> id :battle-status :ally) "white")
                                     "white")
                  :-fx-alignment "CENTER"}
                 (when not-spec
                   {:-fx-font-weight "bold"}))}
              (when (and username
                         (not= username (:username id))
                         (or am-host
                             (= owner username)))
                {:graphic
                 {:fx/type :button
                  :on-action
                  (merge
                    {:event/type :spring-lobby/kick-battle
                     :client-data client-data
                     :singleplayer singleplayer}
                    (select-keys id [:bot-name :username]))
                  :graphic
                  {:fx/type font-icon/lifecycle
                   :icon-literal "mdi-account-remove:16:white"}}}))))}}
      {:fx/type :table-column
       :text "Skill"
       :resizable false
       :pref-width 80
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [{:keys [skill skilluncertainty]}]
          {:text
           (str skill
                " "
                (when (number? skilluncertainty)
                  (apply str (repeat skilluncertainty "?"))))})}}
      {:fx/type :table-column
       :text "Ally"
       :resizable false
       :pref-width 80
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [i]
          {:text ""
           :graphic
           {:fx/type ext-recreate-on-key-changed
            :key (u/nickname i)
            :desc
            {:fx/type :combo-box
             :value (str (:ally (:battle-status i)))
             :on-value-changed {:event/type :spring-lobby/battle-ally-changed
                                :client-data (when-not singleplayer client-data)
                                :is-me (= (:username i) username)
                                :is-bot (-> i :user :client-status :bot)
                                :id i}
             :items (map str (take 16 (iterate inc 0)))
             :disable (or (not username)
                          (not (or am-host
                                   (= (:username i) username)
                                   (= (:owner i) username))))}}})}}
      {:fx/type :table-column
       :text "Team"
       :resizable false
       :pref-width 80
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [i]
          {:text ""
           :graphic
           {:fx/type ext-recreate-on-key-changed
            :key (u/nickname i)
            :desc
            {:fx/type :combo-box
             :value (str (:id (:battle-status i)))
             :on-value-changed {:event/type :spring-lobby/battle-team-changed
                                :client-data (when-not singleplayer client-data)
                                :is-me (= (:username i) username)
                                :is-bot (-> i :user :client-status :bot)
                                :id i}
             :items (map str (take 16 (iterate inc 0)))
             :disable (or (not username)
                          (not (or am-host
                                   (= (:username i) username)
                                   (= (:owner i) username))))}}})}}
      {:fx/type :table-column
       :text "Color"
       :resizable false
       :pref-width 130
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [{:keys [team-color] :as i}]
          {:text ""
           :graphic
           {:fx/type ext-recreate-on-key-changed
            :key (u/nickname i)
            :desc
            {:fx/type :color-picker
             :value (u/spring-color-to-javafx team-color)
             :on-action {:event/type :spring-lobby/battle-color-action
                         :client-data (when-not singleplayer client-data)
                         :is-me (= (:username i) username)
                         :is-bot (-> i :user :client-status :bot)
                         :id i}
             :disable (or (not username)
                          (not (or am-host
                                   (= (:username i) username)
                                   (= (:owner i) username))))}}})}}
      {:fx/type :table-column
       :text "Status"
       :resizable false
       :pref-width 82
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [{:keys [battle-status user username]}]
          (let [client-status (:client-status user)
                am-host (= username host-username)]
            {:text ""
             :graphic
             {:fx/type :h-box
              :children
              (concat
                (when-not singleplayer
                  [
                   {:fx/type font-icon/lifecycle
                    :icon-literal
                    (if (= 1 (:sync battle-status))
                      "mdi-sync:16:green"
                      "mdi-sync-off:16:red")}])
                [(cond
                   (:bot client-status)
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-robot:16:grey"}
                   (not (:mode battle-status))
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-magnify:16:white"}
                   (:ready battle-status)
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-account-check:16:green"}
                   am-host
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-account-key:16:orange"}
                   :else
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-account:16:white"})]
                (when (:ingame client-status)
                  [{:fx/type font-icon/lifecycle
                    :icon-literal "mdi-sword:16:red"}])
                (when (:away client-status)
                  [{:fx/type font-icon/lifecycle
                    :icon-literal "mdi-sleep:16:grey"}]))}}))}}
      {:fx/type :table-column
       :text "Spectator"
       :resizable false
       :pref-width 80
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [i]
          {:text ""
           :graphic
           {:fx/type ext-recreate-on-key-changed
            :key (u/nickname i)
            :desc
            {:fx/type :check-box
             :selected (not (:mode (:battle-status i)))
             :on-selected-changed {:event/type :spring-lobby/battle-spectate-change
                                   :client-data (when-not singleplayer client-data)
                                   :is-me (= (:username i) username)
                                   :is-bot (-> i :user :client-status :bot)
                                   :id i}
             :disable (or (not username)
                          (not (or (and am-host (:mode (:battle-status i)))
                                   (= (:username i) username)
                                   (= (:owner i) username))))}}})}}
      {:fx/type :table-column
       :text "Faction"
       :resizable false
       :pref-width 120
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [i]
          {:text ""
           :graphic
           {:fx/type ext-recreate-on-key-changed
            :key (u/nickname i)
            :desc
            {:fx/type :combo-box
             :value (->> i :battle-status :side (get sides) str)
             :on-value-changed {:event/type :spring-lobby/battle-side-changed
                                :client-data (when-not singleplayer client-data)
                                :is-me (= (:username i) username)
                                :is-bot (-> i :user :client-status :bot)
                                :id i
                                :sides sides}
             :items (->> sides seq (sort-by first) (map second))
             :disable (or (not username)
                          (not (or am-host
                                   (= (:username i) username)
                                   (= (:owner i) username))))}}})}}
      #_
      {:fx/type :table-column
       :editable false
       :text "Rank"
       :cell-value-factory (comp u/to-number :rank :client-status :user)
       :cell-factory
       {:fx/cell-type :table-cell
        :describe (fn [rank] {:text (str rank)})}}
      {:fx/type :table-column
       :text "Country"
       :resizable false
       :pref-width 64
       :cell-value-factory (comp :country :user)
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [country]
          {:text ""
           :graphic
           {:fx/type flag-icon/flag-icon
            :country-code country}})}}
      {:fx/type :table-column
       :text "Bonus"
       :resizable true
       :min-width 64
       :pref-width 64
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [i]
          {:text ""
           :graphic
           {:fx/type ext-recreate-on-key-changed
            :key (u/nickname i)
            :desc
            {:fx/type :text-field
             :disable (not am-host)
             :text-formatter
             {:fx/type :text-formatter
              :value-converter :integer
              :value (int (or (:handicap (:battle-status i)) 0))
              :on-value-changed {:event/type :spring-lobby/battle-handicap-change
                                 :client-data (when-not singleplayer client-data)
                                 :is-bot (-> i :user :client-status :bot)
                                 :id i}}}}})}}]}))
