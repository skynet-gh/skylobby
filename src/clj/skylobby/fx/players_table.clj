(ns skylobby.fx.players-table
  (:require
    [clojure.string :as string]
    [skylobby.color :as color]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-table-column-auto-size]]
    [skylobby.fx.flag-icon :as flag-icon]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.scene.input Clipboard ClipboardContent)
    (javafx.scene.paint Color)))


#_
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

(def allyteam-colors
  (->> color/ffa-colors-web
       (map u/hex-color-to-css)
       (map-indexed vector)
       (into {})))

(def allyteam-javafx-colors
  (->> color/ffa-colors-web
       (map #(Color/web %))
       (map-indexed vector)
       (into {})))


(defn players-table-impl
  [{:keys [am-host battle-players-color-type channel-name client-data host-ingame host-username
           ignore-users
           indexed-mod players players-table-columns ready-on-unspec scripttags server-key sides singleplayer username]}]
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
    {:fx/type ext-recreate-on-key-changed
     :key players-table-columns
     :desc
     {:fx/type ext-table-column-auto-size
      :items
      (sort-by
        (juxt
          (comp u/to-number not u/to-bool :mode :battle-status)
          (comp u/to-number :bot :client-status :user)
          (comp u/to-number :ally :battle-status)
          (comp (fnil - 0) u/parse-skill :skill)
          (comp u/to-number :id :battle-status))
        players-with-skill)
      :desc
      {:fx/type :table-view
       :column-resize-policy :constrained ; TODO auto resize
       :style {:-fx-font-size 14
               :-fx-min-height 200}
       :row-factory
       {:fx/cell-type :table-row
        :describe
        (fn [{:keys [owner team-color username user]}]
          (tufte/profile {:dynamic? true
                          :id :skylobby/player-table}
            (tufte/p :row
              {
               :context-menu
               {:fx/type :context-menu
                :items
                (concat []
                  (when (not owner)
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
                                      :server-key server-key}}])
                      [{:fx/type :menu-item
                        :text "!whois"
                        :on-action {:event/type :spring-lobby/send-message
                                    :client-data client-data
                                    :channel-name (u/user-channel host-username)
                                    :message (str "!whois " username)
                                    :server-key server-key}}]))
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
                    :text (str "User ID: " (-> user :user-id))}
                   {:fx/type :menu-item
                    :text (str "Copy color")
                    :on-action (fn [_event]
                                 (let [clipboard (Clipboard/getSystemClipboard)
                                       content (ClipboardContent.)
                                       color (u/spring-color-to-javafx team-color)]
                                   (.putString content (str color))
                                   (.setContent clipboard content)))}])}})))}
       :columns
       (concat
         [{:fx/type :table-column
           :text "Nickname"
           :resizable true
           :pref-width 480
           :min-width 200
           :cell-value-factory identity
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [{:keys [owner] :as id}]
              (tufte/profile {:dynamic? true
                              :id :skylobby/player-table}
                (tufte/p :nickname
                  (let [not-spec (-> id :battle-status :mode u/to-bool)
                        text-color-javafx (or
                                            (when not-spec
                                              (case battle-players-color-type
                                                "team" (get allyteam-javafx-colors (-> id :battle-status :ally))
                                                "player" (-> id :team-color u/spring-color-to-javafx)
                                                ; else
                                                nil))
                                            Color/WHITE)
                        text-color-css (-> text-color-javafx str u/hex-color-to-css)]
                    {:text ""
                     :graphic
                     {:fx/type :h-box
                      :alignment :center
                      :children
                      (concat
                        (when (and username
                                   (not= username (:username id))
                                   (or am-host
                                       (= owner username)))
                          [
                           {:fx/type :button
                            :on-action
                            (merge
                              {:event/type :spring-lobby/kick-battle
                               :client-data client-data
                               :singleplayer singleplayer}
                              (select-keys id [:bot-name :username]))
                            :graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-account-remove:16:white"}}])
                        [{:fx/type :pane
                          :style {:-fx-pref-width 8}}
                         (merge
                           {:fx/type :text
                            :effect {:fx/type :drop-shadow
                                     :color (if (color/dark? text-color-javafx)
                                              "#d5d5d5"
                                              "black")
                                     :radius 2
                                     :spread 1}
                            :text (u/nickname id)
                            :fill text-color-css
                            :style
                            (merge
                              {:-fx-font-size "16"
                               :-fx-font-smoothing-type :gray}
                              (when not-spec
                                {:-fx-font-weight "bold"}))})])}}))))}}]
         (when (:skill players-table-columns)
           [{:fx/type :table-column
             :text "Skill"
             :resizable false
             :pref-width 80
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [{:keys [skill skilluncertainty]}]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :skill
                    {:text
                     (str skill
                          " "
                          (when (number? skilluncertainty)
                            (apply str (repeat skilluncertainty "?"))))})))}}])
         (when (:status players-table-columns)
           [{:fx/type :table-column
             :text "Status"
             :resizable false
             :pref-width 82
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [{:keys [battle-status user username]}]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :status
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
                              (case (:sync battle-status)
                                1 "mdi-sync:16:green"
                                2 "mdi-sync-off:16:red"
                                ; else
                                "mdi-sync-alert:16:yellow")}])
                          [(cond
                             (:bot client-status)
                             {:fx/type font-icon/lifecycle
                              :icon-literal "mdi-robot:16:grey"}
                             (not (u/to-bool (:mode battle-status)))
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
                              :icon-literal "mdi-sleep:16:grey"}]))}}))))}}])
         (when (:ally players-table-columns)
           [{:fx/type :table-column
             :text "Ally"
             :resizable false
             :pref-width 80
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :ally
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
                                             (= (:owner i) username))))}}})))}}])
         (when (:team players-table-columns)
           [{:fx/type :table-column
             :text "Team"
             :resizable false
             :pref-width 80
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :team
                    (let [items (map str (take 16 (iterate inc 0)))
                          value (str (:id (:battle-status i)))]
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (u/nickname i)
                        :desc
                        {:fx/type :combo-box
                         :value value
                         :on-value-changed {:event/type :spring-lobby/battle-team-changed
                                            :client-data (when-not singleplayer client-data)
                                            :is-me (= (:username i) username)
                                            :is-bot (-> i :user :client-status :bot)
                                            :id i}
                         :items items
                         :disable (or (not username)
                                      (not (or am-host
                                               (= (:username i) username)
                                               (= (:owner i) username))))}}}))))}}])
         (when (:color players-table-columns)
           [{:fx/type :table-column
             :text "Color"
             :resizable false
             :pref-width 130
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [{:keys [team-color] :as i}]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :color
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
                                             (= (:owner i) username))))}}})))}}])
         (when (:spectator players-table-columns)
           [{:fx/type :table-column
             :text "Spectator"
             :resizable false
             :pref-width 80
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :spectator
                    (let [am-spec (-> i :battle-status :mode u/to-bool not boolean)]
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (u/nickname i)
                        :desc
                        {:fx/type :check-box
                         :selected am-spec
                         :on-selected-changed {:event/type :spring-lobby/battle-spectate-change
                                               :client-data (when-not singleplayer client-data)
                                               :is-me (= (:username i) username)
                                               :is-bot (-> i :user :client-status :bot)
                                               :id i
                                               :ready-on-unspec ready-on-unspec}
                         :disable (or (not username)
                                      (not (or (and am-host (not am-spec))
                                               (= (:username i) username)
                                               (= (:owner i) username))))}}}))))}}])
         (when (:faction players-table-columns)
           [{:fx/type :table-column
             :text "Faction"
             :resizable false
             :pref-width 120
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :faction
                    (let [items (->> sides seq (sort-by first) (map second))]
                      {:text ""
                       :graphic
                       (if (seq items)
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
                                              :indexed-mod indexed-mod
                                              :sides sides}
                           :items items
                           :disable (or (not username)
                                        (not (or am-host
                                                 (= (:username i) username)
                                                 (= (:owner i) username))))}}
                         {:fx/type :label
                          :text "loading..."})}))))}}])
         (when (:rank players-table-columns)
           [{:fx/type :table-column
             :editable false
             :text "Rank"
             :pref-width 48
             :resizable false
             :cell-value-factory (comp u/to-number :rank :client-status :user)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe (fn [rank] {:text (str rank)})}}])
         (when (:country players-table-columns)
           [{:fx/type :table-column
             :text "Country"
             :resizable false
             :pref-width 64
             :cell-value-factory (comp :country :user)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [country]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :flag
                    {:text ""
                     :graphic
                     {:fx/type flag-icon/flag-icon
                      :country-code country}})))}}])
         (when (:bonus players-table-columns)
           [{:fx/type :table-column
             :text "Bonus"
             :resizable true
             :min-width 64
             :pref-width 64
             :cell-value-factory identity
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [i]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :bonus
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
                                           :id i}}}}})))}}]))}}}))

(defn players-table
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :players-table
      (players-table-impl state))))
