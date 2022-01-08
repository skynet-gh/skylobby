(ns skylobby.fx.players-table
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    java-time
    [skylobby.color :as color]
    skylobby.fx
    [skylobby.fx.color :as fx.color]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-table-column-auto-size]]
    [skylobby.fx.flag-icon :as flag-icon]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.spring-options :as fx.spring-options]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.util :as u]
    [spring-lobby.spring :as spring]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.scene.input Clipboard ClipboardContent)
    (javafx.scene.paint Color)))


(set! *warn-on-reflection* true)


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


(def sort-playing (comp u/to-number not u/to-bool :mode :battle-status))
(def sort-bot (comp u/to-number :bot :client-status :user))
(def sort-ally (comp u/to-number :ally :battle-status))
(def sort-skill (comp (fnil - 0) u/parse-skill :skill))
(def sort-id (comp u/to-number :id :battle-status))
(def sort-side (comp u/to-number :side :battle-status))
(def sort-rank (comp (fnil - 0) u/to-number :rank :client-status :user))


(defn ai-options-window [{:fx/keys [context]}]
  (let [
        show (boolean (fx/sub-val context :show-ai-options-window))
        server-key (fx/sub-val context get-in [:ai-options :server-key])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        am-host (fx/sub-ctx context sub/am-host server-key)
        bot-name (fx/sub-val context get-in [:ai-options :bot-name])
        bot-version (fx/sub-val context get-in [:ai-options :bot-version])
        bot-username (fx/sub-val context get-in [:ai-options :bot-username])
        current-options (fx/sub-val context get-in [:by-server server-key :battle :scripttags "game" "bots" bot-username "options"])
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        engine-version (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-version])
        engine-details (fx/sub-ctx context sub/indexed-engine spring-root engine-version)
        engine-bots (:engine-bots engine-details)
        mod-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-root mod-name)
        battle-mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        bots (concat engine-bots
                     (->> battle-mod-details :luaai
                          (map second)
                          (map (fn [ai]
                                 {:bot-name (:name ai)
                                  :bot-version "<game>"}))))
        bot-options-map (->> bots
                             (map (juxt (juxt :bot-name :bot-version) :bot-options))
                             (into {}))
        available-options (get bot-options-map [bot-name bot-version])]
    {:fx/type :stage
     :showing show
     :title (str u/app-name " AI Options")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-ai-options-window}
     :height 480
     :width 600
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      {:fx/type :v-box
       :style {:-fx-font-size 16}
       :children
       [
        {:fx/type :label
         :style {:-fx-font-size 24}
         :text (str bot-username)}
        {:fx/type :label
         :text "Options:"}
        {:fx/type :v-box
         :children
         [
          {:fx/type fx.spring-options/modoptions-view
           :event-data {:event/type :spring-lobby/aioption-change
                        :bot-username bot-username
                        :server-key server-key}
           :modoptions available-options
           :current-options current-options
           :server-key server-key
           :singleplayer (= server-key :local)}]}
        {:fx/type :button
         :style {:-fx-font-size 24}
         :text "Save"
         :on-action {:event/type :spring-lobby/save-aioptions
                     :am-host am-host
                     :available-options available-options
                     :bot-username bot-username
                     :channel-name channel-name
                     :client-data client-data
                     :current-options current-options}}]}}}))


(defn report-user-window [{:fx/keys [context]}]
  (let [
        show (boolean (fx/sub-val context :show-report-user-window))
        server-key (fx/sub-ctx context skylobby.fx/selected-tab-server-key-sub)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        battle-id (fx/sub-val context get-in [:report-user server-key :battle-id])
        username (fx/sub-val context get-in [:report-user server-key :username])
        message (fx/sub-val context get-in [:report-user server-key :message])]
    {:fx/type :stage
     :showing show
     :title (str u/app-name " Report User")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-report-user-window}
     :height 480
     :width 600
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      {:fx/type :v-box
       :style {:-fx-font-size 20}
       :children
       [
        {:fx/type :h-box
         :alignment :center
         :children
         [
          {:fx/type :label
           :style {:-fx-font-size 24}
           :text " Report user: "}
          {:fx/type :label
           :style {:-fx-font-size 28}
           :text (str username)}]}
        {:fx/type :h-box
         :alignment :center
         :children
         [
          {:fx/type :label
           :text (str " As " server-key)}]}
        {:fx/type :h-box
         :alignment :center
         :children
         [
          {:fx/type :label
           :text (str " In battle id " battle-id)}]}
        {:fx/type :label
         :text " Reason:"}
        {:fx/type :text-area
         :v-box/vgrow :always
         :text (str message)
         :on-text-changed {:event/type :spring-lobby/assoc-in
                           :path [:report-user server-key :message]}}
        {:fx/type :h-box
         :children
         [{:fx/type :button
           :text "Report"
           :on-action {:event/type :spring-lobby/send-user-report
                       :battle-id battle-id
                       :client-data client-data
                       :message message
                       :username username}}
          {:fx/type :pane
           :h-box/hgrow :always}
          {:fx/type :button
           :text "Cancel"
           :on-action {:event/type :spring-lobby/dissoc
                       :key :show-report-user-window}}]}]}}}))

(defn players-table-impl
  [{:fx/keys [context]
    :keys [mod-name players server-key]}]
  (let [am-host (fx/sub-ctx context sub/am-host server-key)
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        battle-players-color-type (fx/sub-val context :battle-players-color-type)
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        host-ingame (fx/sub-ctx context sub/host-ingame server-key)
        host-username (fx/sub-ctx context sub/host-username server-key)
        ignore-users (fx/sub-val context :ignore-users)
        increment-ids (fx/sub-val context :increment-ids)
        players-table-columns (fx/sub-val context :players-table-columns)
        ready-on-unspec (fx/sub-val context :ready-on-unspec)
        scripttags (fx/sub-val context get-in [:by-server server-key :battle :scripttags])
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        mod-name (or mod-name
                     (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname]))
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-root mod-name)
        battle-mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        sides (spring/mod-sides battle-mod-details)
        side-items (->> sides seq (sort-by first) (map second))
        singleplayer (or (not server-key) (= :local server-key))
        username (fx/sub-val context get-in [:by-server server-key :username])

        players-with-skill (map
                             (fn [{:keys [skill skilluncertainty username] :as player}]
                               (let [username-lc (when username (string/lower-case username))
                                     tags (get-in scripttags ["game" "players" username-lc])
                                     uncertainty (or (try (u/to-number skilluncertainty)
                                                          (catch Exception e
                                                            (log/debug e "Error parsing skill uncertainty")))
                                                     (try (u/to-number (get tags "skilluncertainty"))
                                                          (catch Exception e
                                                            (log/debug e "Error parsing skill uncertainty")))
                                                     3)]
                                 (assoc player
                                        :skill (or skill (get tags "skill"))
                                        :skilluncertainty uncertainty)))
                             players)
        incrementing-cell (fn [id]
                            {:text
                             (if increment-ids
                               (when-let [n (u/to-number id)]
                                 (str (inc n)))
                               (str id))})
        now (or (fx/sub-val context :now) (u/curr-millis))
        sorm (if singleplayer "singleplayer" "multiplayer")]
    {:fx/type ext-recreate-on-key-changed
     :key players-table-columns
     :desc
     {:fx/type ext-table-column-auto-size
      :items
      (sort-by
        (juxt
          sort-playing
          sort-bot
          sort-ally
          sort-skill
          sort-id)
        players-with-skill)
      :desc
      {:fx/type :table-view
       :style-class ["table-view" "skylobby-players-" sorm]
       :column-resize-policy :unconstrained
       :style {:-fx-min-height 200}
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
                :style {:-fx-font-size 16}
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
                                    :channel-name (u/user-channel-name host-username)
                                    :message "!help"
                                    :server-key server-key}}
                       {:fx/type :menu-item
                        :text "!status battle"
                        :on-action {:event/type :spring-lobby/send-message
                                    :client-data client-data
                                    :channel-name (u/user-channel-name host-username)
                                    :message "!status battle"
                                    :server-key server-key}}]
                      (if host-ingame
                        [{:fx/type :menu-item
                          :text "!status game"
                          :on-action {:event/type :spring-lobby/send-message
                                      :client-data client-data
                                      :channel-name (u/user-channel-name host-username)
                                      :message "!status game"
                                      :server-key server-key}}]
                        [{:fx/type :menu-item
                          :text "!stats"
                          :on-action {:event/type :spring-lobby/send-message
                                      :client-data client-data
                                      :channel-name (u/user-channel-name host-username)
                                      :message "!stats"
                                      :server-key server-key}}])))
                  (when (->> players (filter (comp #{host-username} :username)) first :user :client-status :bot)
                    [{:fx/type :menu-item
                      :text "!whois"
                      :on-action {:event/type :spring-lobby/send-message
                                  :client-data client-data
                                  :channel-name (u/user-channel-name host-username)
                                  :message (str "!whois " username)
                                  :server-key server-key}}])
                  [
                   {:fx/type :menu-item
                    :text (str "User ID: " (-> user :user-id))}
                   {:fx/type :menu-item
                    :text (str "Copy color")
                    :on-action (fn [_event]
                                 (let [clipboard (Clipboard/getSystemClipboard)
                                       content (ClipboardContent.)
                                       color (fx.color/spring-color-to-javafx team-color)]
                                   (.putString content (str color))
                                   (.setContent clipboard content)))}
                   (if (-> ignore-users (get server-key) (get username))
                     {:fx/type :menu-item
                      :text "Unignore"
                      :on-action {:event/type :spring-lobby/unignore-user
                                  :server-key server-key
                                  :username username}}
                     {:fx/type :menu-item
                      :text "Ignore"
                      :on-action {:event/type :spring-lobby/ignore-user
                                  :server-key server-key
                                  :username username}})]
                  (when (contains? (:compflags client-data) "teiserver")
                    [{:fx/type :menu-item
                      :text "Report"
                      :on-action {:event/type :spring-lobby/show-report-user
                                  :battle-id battle-id
                                  :server-key server-key
                                  :username username}}]))}})))}
       :columns
       (concat
         [{:fx/type :table-column
           :text "Nickname"
           :resizable true
           :min-width 200
           :cell-value-factory (juxt
                                 (comp (fnil string/lower-case "") u/nickname)
                                 u/nickname
                                 identity)
           :cell-factory
           {:fx/cell-type :table-cell
            :describe
            (fn [[_nickname-lc nickname {:keys [ai-name ai-version bot-name owner] :as id}]]
              (tufte/profile {:dynamic? true
                              :id :skylobby/player-table}
                (tufte/p :nickname
                  (let [not-spec (-> id :battle-status :mode u/to-bool)
                        text-color-javafx (or
                                            (when not-spec
                                              (case battle-players-color-type
                                                "team" (get allyteam-javafx-colors (-> id :battle-status :ally))
                                                "player" (-> id :team-color fx.color/spring-color-to-javafx)
                                                ; else
                                                nil))
                                            Color/WHITE)
                        text-color-css (-> text-color-javafx str u/hex-color-to-css)]
                    {:text ""
                     :tooltip
                     {:fx/type tooltip-nofocus/lifecycle
                      :show-delay skylobby.fx/tooltip-show-delay
                      :style {:-fx-font-size 18}
                      :text nickname}
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
                             :icon-literal "mdi-account-remove:16:white"}}
                           {:fx/type :button
                            :on-action
                            {:event/type :spring-lobby/show-ai-options-window
                             :bot-name ai-name
                             :bot-version ai-version
                             :bot-username bot-name
                             :server-key server-key}
                            :graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-settings:16:white"}}])
                        [{:fx/type :pane
                          :style {:-fx-pref-width 8}}
                         (merge
                           {:fx/type :text
                            :style-class ["text" (str "skylobby-players-" sorm "-nickname")]
                            :effect {:fx/type :drop-shadow
                                     :color (if (color/dark? text-color-javafx)
                                              "#d5d5d5"
                                              "black")
                                     :radius 2
                                     :spread 1}
                            :text nickname
                            :fill text-color-css
                            :style
                            (merge
                              {:-fx-font-smoothing-type :gray}
                              (when not-spec
                                {:-fx-font-weight "bold"}))})])}}))))}}]
         (when (:skill players-table-columns)
           [{:fx/type :table-column
             :text "Skill"
             :resizable false
             :pref-width 80
             :cell-value-factory (juxt sort-skill :skilluncertainty :skill)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[_ skilluncertainty skill]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :skill
                    {:alignment :center-left
                     :text
                     (str skill
                          " "
                          (when (number? skilluncertainty)
                            (apply str (repeat skilluncertainty "?"))))})))}}])
         (when (:status players-table-columns)
           [{:fx/type :table-column
             :text "Status"
             :resizable false
             :pref-width 82
             :cell-value-factory (juxt sort-playing (comp :ingame :client-status :user) u/nickname identity)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[_ _ _ {:keys [battle-status user username]}]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :status
                    (let [client-status (:client-status user)
                          am-host (= username host-username)]
                      {:text ""
                       :tooltip
                       {:fx/type tooltip-nofocus/lifecycle
                        :show-delay skylobby.fx/tooltip-show-delay
                        :style {:-fx-font-size 16}
                        :text
                        (str
                          (case (int (or (:sync battle-status) 0))
                            1 "Synced"
                            2 "Unsynced"
                            "Unknown sync")
                          "\n"
                          (if (u/to-bool (:mode battle-status)) "Playing" "Spectating")
                          (when (u/to-bool (:mode battle-status))
                            (str "\n" (if (:ready battle-status) "Ready" "Unready")))
                          (when (:ingame client-status)
                            "\nIn game")
                          (when-let [away-start-time (:away-start-time user)]
                            (when (and (:away client-status) away-start-time)
                              (str "\nAway: "
                                   (let [diff (- now away-start-time)]
                                     (if (< diff 30000)
                                       " just now"
                                       (str " " (u/format-duration (java-time/duration (- now away-start-time) :millis)))))))))}
                       :graphic
                       {:fx/type :h-box
                        :alignment :center-left
                        :children
                        (concat
                          (when-not singleplayer
                            [
                             {:fx/type font-icon/lifecycle
                              :icon-literal
                              (let [sync-status (int (or (:sync battle-status) 0))]
                                (case sync-status
                                  1 "mdi-sync:16:green"
                                  2 "mdi-sync-off:16:red"
                                  ; else
                                  "mdi-sync-alert:16:yellow"))}])
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
             :cell-value-factory (juxt
                                   sort-playing
                                   sort-ally
                                   sort-skill
                                   sort-id
                                   u/nickname
                                   identity)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[_status _ally _skill _team _username i]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :ally
                    {:text ""
                     :graphic
                     {:fx/type ext-recreate-on-key-changed
                      :key [(u/nickname i) increment-ids]
                      :desc
                      {:fx/type :combo-box
                       :value (-> i :battle-status :ally str)
                       :on-value-changed {:event/type :spring-lobby/battle-ally-changed
                                          :client-data (when-not singleplayer client-data)
                                          :is-me (= (:username i) username)
                                          :is-bot (-> i :user :client-status :bot)
                                          :id i}
                       :items (map str (take 16 (iterate inc 0)))
                       :button-cell incrementing-cell
                       :cell-factory {:fx/cell-type :list-cell
                                      :describe incrementing-cell}
                       :disable (or (not username)
                                    (not (or am-host
                                             (= (:username i) username)
                                             (= (:owner i) username))))}}})))}}])
         (when (:team players-table-columns)
           [{:fx/type :table-column
             :text "Team"
             :resizable false
             :pref-width 80
             :cell-value-factory (juxt
                                   sort-playing
                                   sort-id
                                   sort-skill
                                   u/nickname
                                   identity)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[_play id _skill _username i]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :team
                    (let [items (map str (take 16 (iterate inc 0)))
                          value (str id)]
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key [(u/nickname i) increment-ids]
                        :desc
                        {:fx/type :combo-box
                         :value value
                         :on-value-changed {:event/type :spring-lobby/battle-team-changed
                                            :client-data (when-not singleplayer client-data)
                                            :is-me (= (:username i) username)
                                            :is-bot (-> i :user :client-status :bot)
                                            :id i}
                         :items items
                         :button-cell incrementing-cell
                         :cell-factory {:fx/cell-type :list-cell
                                        :describe incrementing-cell}
                         :disable (or (not username)
                                      (not (or am-host
                                               (= (:username i) username)
                                               (= (:owner i) username))))}}}))))}}])
         (when (:color players-table-columns)
           [{:fx/type :table-column
             :text "Color"
             :resizable false
             :pref-width 130
             :cell-value-factory (juxt :team-color u/nickname identity)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[team-color nickname i]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :color
                    {:text ""
                     :graphic
                     {:fx/type ext-recreate-on-key-changed
                      :key nickname
                      :desc
                      {:fx/type :color-picker
                       :value (fx.color/spring-color-to-javafx team-color)
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
             :cell-value-factory (juxt
                                   sort-playing
                                   sort-skill
                                   u/nickname
                                   identity)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[_ _ _ i]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :spectator
                    (let [is-spec (-> i :battle-status :mode u/to-bool not boolean)]
                      {:text ""
                       :alignment :center
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (u/nickname i)
                        :desc
                        {:fx/type :check-box
                         :selected (boolean is-spec)
                         :on-selected-changed {:event/type :spring-lobby/battle-spectate-change
                                               :client-data (when-not singleplayer client-data)
                                               :is-me (= (:username i) username)
                                               :is-bot (-> i :user :client-status :bot)
                                               :id i
                                               :ready-on-unspec ready-on-unspec}
                         :disable (or (not username)
                                      (not (or (and am-host (not is-spec))
                                               (= (:username i) username)
                                               (= (:owner i) username))))}}}))))}}])
         (when (:faction players-table-columns)
           [{:fx/type :table-column
             :text "Faction"
             :resizable false
             :pref-width 120
             :cell-value-factory (juxt
                                   sort-playing
                                   sort-side
                                   sort-skill
                                   :username
                                   identity)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[_ _ _ _ i]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :faction
                    {:text ""
                     :graphic
                     (if (seq side-items)
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
                         :items side-items
                         :disable (or (not username)
                                      (not (or am-host
                                               (= (:username i) username)
                                               (= (:owner i) username))))}}
                       {:fx/type :label
                        :text "loading..."})})))}}])
         (when (:rank players-table-columns)
           [{:fx/type :table-column
             :editable false
             :text "Rank"
             :pref-width 48
             :resizable false
             :cell-value-factory (juxt
                                   sort-rank
                                   (comp u/to-number :rank :client-status :user))
             :cell-factory
             {:fx/cell-type :table-cell
              :describe (fn [[_ rank]] {:text (str rank)})}}])
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
             :cell-value-factory (juxt
                                   (comp str :handicap :battle-status)
                                   (comp :handicap :battle-status)
                                   u/nickname
                                   identity)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[_ bonus _ i]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :bonus
                    {:text ""
                     :graphic
                     {:fx/type ext-recreate-on-key-changed
                      :key (u/nickname i)
                      :desc
                      {:fx/type :text-field
                       :disable (boolean am-spec)
                       :text-formatter
                       {:fx/type :text-formatter
                        :value-converter :integer
                        :value (int (or bonus 0))
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
