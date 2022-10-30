(ns skylobby.fx.players-table
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [clojure.string :as string]
    java-time
    [skylobby.color :as color]
    skylobby.fx
    [skylobby.fx.color :as fx.color]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-table-column-auto-size ext-with-context-menu]]
    [skylobby.fx.flag-icon :as flag-icon]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.spring-options :as fx.spring-options]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.spring :as spring]
    [skylobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
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

; https://github.com/beyond-all-reason/Beyond-All-Reason/blob/13457a9/luaui/Widgets/gui_advplayerslist.lua#L1197-L1204
(defn uncertainty-color [uncertainty]
  (case (int (or (when (number? uncertainty) uncertainty)
                 3))
    0 "#fafafa"
    1 "#c3c3c3"
    2 "#8c8c8c"
    "#be8282"))


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
                     :current-options current-options
                     :server-key server-key}}]}}}))


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

(defn add-parsed-skill
  [scripttags {:keys [skill skilluncertainty username] :as player}]
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

(defn player-context-menu
  [{:fx/keys [context]
    :keys [battle-id host-is-bot host-ingame player read-only server-key]}]
  (let [{:keys [owner username user]} player
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        ignore-users (fx/sub-val context get-in [:ignore-users server-key])
        host-username (fx/sub-ctx context sub/host-username server-key)]
    {:fx/type :context-menu
     :style {:-fx-font-size 16
             :-fx-font-weight "normal"}
     :items
     (concat []
       (when (not owner)
         [
          {:fx/type :menu-item
           :text "Message"
           :on-action {:event/type :spring-lobby/join-direct-message
                       :server-key server-key
                       :username username}}])
       (when-not read-only
         [{:fx/type :menu-item
           :text "Ring"
           :on-action {:event/type :spring-lobby/ring
                       :client-data client-data
                       :channel-name channel-name
                       :username username}}])
       (when (and host-username
                  (= host-username username)
                  (-> user :client-status :bot))
         (concat
           [{:fx/type :menu-item
             :text "!help"
             :on-action {:event/type :skylobby.fx.event.chat/send
                         :client-data client-data
                         :channel-name (u/user-channel-name host-username)
                         :focus true
                         :message "!help"
                         :server-key server-key}}
            {:fx/type :menu-item
             :text "!status battle"
             :on-action {:event/type :skylobby.fx.event.chat/send
                         :client-data client-data
                         :channel-name (u/user-channel-name host-username)
                         :focus true
                         :message "!status battle"
                         :server-key server-key}}]
           (if host-ingame
             [{:fx/type :menu-item
               :text "!status game"
               :on-action {:event/type :skylobby.fx.event.chat/send
                           :client-data client-data
                           :channel-name (u/user-channel-name host-username)
                           :focus true
                           :message "!status game"
                           :server-key server-key}}]
             [{:fx/type :menu-item
               :text "!stats"
               :on-action {:event/type :skylobby.fx.event.chat/send
                           :client-data client-data
                           :channel-name (u/user-channel-name host-username)
                           :focus true
                           :message "!stats"
                           :server-key server-key}}])
           [{:fx/type :menu-item
             :text "!listmaps"
             :on-action {:event/type :skylobby.fx.event.chat/send
                         :client-data client-data
                         :channel-name (u/user-channel-name host-username)
                         :focus true
                         :message "!listmaps"
                         :server-key server-key}}]))
       (when host-is-bot
         [{:fx/type :menu-item
           :text "!whois"
           :on-action {:event/type :skylobby.fx.event.chat/send
                       :client-data client-data
                       :channel-name (u/user-channel-name host-username)
                       :focus true
                       :message (str "!whois " username)
                       :server-key server-key}}
          {:fx/type :menu-item
           :text "!boss"
           :on-action {:event/type :skylobby.fx.event.chat/send
                       :client-data client-data
                       :channel-name channel-name
                       :message (str "!boss " username)
                       :server-key server-key}}
          {:fx/type :menu-item
           :text "!spec"
           :on-action {:event/type :skylobby.fx.event.chat/send
                       :client-data client-data
                       :channel-name channel-name
                       :message (str "!spec " username)
                       :server-key server-key}}])
       [
        {:fx/type :menu-item
         :text (str "User ID: " (-> user :user-id))}
        #_
        {:fx/type :menu-item
         :text (str "Copy color")
         :on-action (fn [_event]
                      (let [clipboard (Clipboard/getSystemClipboard)
                            content (ClipboardContent.)
                            color (fx.color/spring-color-to-javafx team-color)]
                        (.putString content (str color))
                        (.setContent clipboard content)))}
        (if (get ignore-users username)
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
                       :username username}}]))}))

(def player-status-width 72)

(defn player-status-tooltip-label
  [{:fx/keys [context]
    :keys [battle-status server-key user username]}]
  (let [
        client-status (:client-status user)
        host-username (fx/sub-ctx context sub/host-username server-key)
        now (or (fx/sub-val context :now) (u/curr-millis))]
    {:fx/type :label
     :text
     (str
       (case (int (or (:sync battle-status) 0))
         1 "Synced"
         2 "Unsynced"
         "Unknown sync")
       "\n"
       (if (u/to-bool (:mode battle-status)) "Playing" "Spectating")
       (when (and host-username username
                  (= host-username username))
         "\nHost")
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
                    (str " " (u/format-duration (java-time/duration (- now away-start-time) :millis)))))))))}))

(defn player-status
  [{:fx/keys [context]
    :keys [player server-key]}]
  (let [{:keys [battle-status user username]} player
        client-status (:client-status user)
        host-username (fx/sub-ctx context sub/host-username server-key)
        am-host (= username host-username)
        singleplayer (or (not server-key) (= :local server-key))]
    {:fx/type :label
     :style {:-fx-min-width player-status-width
             :-fx-pref-width player-status-width
             :-fx-max-width player-status-width}
     :text ""
     :tooltip
     {:fx/type tooltip-nofocus/lifecycle
      :show-delay skylobby.fx/tooltip-show-delay
      :style {:-fx-font-size 16}
      :text ""
      :graphic
      {:fx/type player-status-tooltip-label
       :battle-status battle-status
       :server-key server-key
       :user user
       :username username}}
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
        (when (and am-host
                   (not (u/to-bool (:mode battle-status))))
          [{:fx/type font-icon/lifecycle
            :icon-literal "mdi-crown:16:orange"}])
        (when (:ingame client-status)
          [{:fx/type font-icon/lifecycle
            :icon-literal "mdi-sword:16:red"}])
        (when (:away client-status)
          [{:fx/type font-icon/lifecycle
            :icon-literal "mdi-sleep:16:grey"}]))}}))

(defn player-name-tooltip
  [{:keys [player server-key]}]
  {:fx/type tooltip-nofocus/lifecycle
   :show-delay skylobby.fx/tooltip-show-delay
   :style {:-fx-font-size 16
           :-fx-font-weight "normal"}
   :text ""
   :graphic
   {:fx/type :v-box
    :children
    (concat
      [
       {:fx/type :label
        :style {:-fx-font-size 18}
        :text (str (u/nickname player))}
       {:fx/type :label
        :text ""}]
      (when-let [ai-name (:ai-name player)]
        [{:fx/type :label
          :text (str "AI Type: " ai-name)}])
      (when-let [owner (:owner player)]
        [{:fx/type :label
          :text (str "Owner: " owner)}])
      (when-let [bonus (-> player :battle-status :handicap u/to-number)]
        (when (not= 0 bonus)
          [{:fx/type :label
            :text (str "Bonus: +" bonus "%")}]))
      (when-not (-> player :user :client-status :bot)
        [{:fx/type :label
          :style {:-fx-text-fill (uncertainty-color (:skilluncertainty player))}
          :text
          (str "Skill: "
               (str (:skill player)
                    " "
                    (let [uncertainty (:skilluncertainty player)]
                      (when (number? uncertainty)
                        (apply str (repeat uncertainty "?"))))))}])
      (when-let [country (-> player :user :country)]
        [{:fx/type :h-box
          :children
          [{:fx/type :label
            :text "Country: "}
           {:fx/type flag-icon/flag-icon
            :country-code country}]}])
      (let [{:keys [battle-status user username]} player]
        [
         {:fx/type player-status-tooltip-label
          :battle-status battle-status
          :server-key server-key
          :user user
          :username username}]))}})

(defn add-players-colors [players]
  (let [
        by-allyteam (->> players
                         (filter (comp u/to-bool :mode :battle-status))
                         (group-by (comp :ally :battle-status)))
        teams-by-allyteam (->> by-allyteam
                               (map
                                 (fn [[k vs]]
                                   [k (vec (sort (map (comp :id :battle-status) vs)))]))
                               (into {}))]
    (doall
      (map
        (fn [{:keys [battle-status] :as player}]
          (assoc player :team-color (color/player-color battle-status teams-by-allyteam)))
        players))))


(defn sides-sub [context server-key mod-name]
  (let [
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-root mod-name)
        battle-mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)]
    (spring/mod-sides battle-mod-details)))

(defn side-items-sub [context server-key mod-name]
  (let [
        sides (fx/sub-ctx context sides-sub server-key mod-name)]
    (->> sides seq (sort-by first) (mapv second))))

(defn battle-players-and-bots
  "Returns the sequence of all players and bots for a battle."
  [{:keys [battle users]}]
  (doall
    (concat
      (mapv
        (fn [[k v]] (assoc v :username k :user (get users k)))
        (:users battle))
      (mapv
        (fn [[k v]]
          (assoc v
                 :bot-name k
                 :user {:client-status {:bot true}}))
        (:bots battle)))))

(defn players-sub [context server-key battle-id]
  (let [
        old-battle (fx/sub-val context get-in [:by-server server-key :old-battles battle-id])
        battle-users (or (:users old-battle)
                         (fx/sub-val context get-in [:by-server server-key :battle :users]))
        battle-bots (or (:bots old-battle)
                        (fx/sub-val context get-in [:by-server server-key :battle :bots]))
        players (battle-players-and-bots
                  {:users (fx/sub-val context get-in [:by-server server-key :users])
                   :battle
                   {:bots battle-bots
                    :users battle-users}})]
    players))

(defn sort-players [players]
  (->> players
       (sort-by
         (juxt
           sort-playing
           sort-bot
           sort-ally
           sort-skill
           sort-id))
       doall))

(defn sorted-players-sub [context players server-key battle-id auto-color scripttags]
  (let [players (or players
                    (fx/sub-ctx context players-sub server-key battle-id))
        players-with-color (if auto-color
                             (add-players-colors players)
                             players)
        players-with-skill (mapv (partial add-parsed-skill scripttags) players-with-color)]
    (sort-players players-with-skill)))

(defn players-table-impl
  [{:fx/keys [context]
    :keys [auto-color battle-id mod-name players read-only scripttags server-key]}]
  (let [am-host (fx/sub-ctx context sub/am-host server-key)
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        battle-players-color-type (fx/sub-val context :battle-players-color-type)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        host-ingame (fx/sub-ctx context sub/host-ingame server-key)
        host-username (fx/sub-ctx context sub/host-username server-key)
        host-is-bot (->> players (filter (comp #{host-username} :username)) first :user :client-status :bot)
        increment-ids (fx/sub-val context :increment-ids)
        players-table-columns (fx/sub-val context :players-table-columns)
        ready-on-unspec (fx/sub-val context :ready-on-unspec)
        selected-tab-main (fx/sub-val context get-in [:selected-tab-main server-key])
        scripttags (or scripttags
                       (fx/sub-val context get-in [:by-server server-key :battle :scripttags]))
        mod-name (or mod-name
                     (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname]))
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-root mod-name)
        mod-details-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/mod-details)
        sides (fx/sub-ctx context sides-sub server-key mod-name)
        side-items (fx/sub-ctx context side-items-sub server-key mod-name)
        singleplayer (or (not server-key) (= :local server-key))
        username (fx/sub-val context get-in [:by-server server-key :username])
        sorted-players (fx/sub-ctx context sorted-players-sub players server-key battle-id auto-color scripttags)
        incrementing-cell (fn [id]
                            {:text
                             (if increment-ids
                               (when-let [n (u/to-number id)]
                                 (str (inc n)))
                               (str id))})
        css-class-suffix (cond
                           (not server-key) "replay"
                           singleplayer "singleplayer"
                           :else "multiplayer")
        server-type (u/server-type server-key)
        ignore-users (fx/sub-val context get-in [:ignore-users server-key])]
    {:fx/type ext-recreate-on-key-changed
     :key (pr-str [server-key selected-tab-main battle-id players-table-columns])
     :desc
     {:fx/type ext-table-column-auto-size
      :items sorted-players
      :desc
      {:fx/type :table-view
       :style-class ["table-view" "skylobby-players-" css-class-suffix]
       :column-resize-policy :unconstrained
       :style {:-fx-min-height 200}
       :row-factory
       {:fx/cell-type :table-row
        :describe
        (fn [{:as player}]
          (tufte/profile {:dynamic? true
                          :id :skylobby/player-table}
            (tufte/p :row
              {
               :context-menu
               {:fx/type player-context-menu
                :player player
                :battle-id battle-id
                :host-is-bot host-is-bot
                :host-ingame host-ingame
                :read-only read-only
                :server-key server-key}})))}
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
                        text-color-css (-> text-color-javafx str u/hex-color-to-css)
                        can-kick (and (not read-only)
                                      username
                                      (not= username (:username id))
                                      (or am-host
                                          (= owner username)))]
                    {:text ""
                     :tooltip
                     {:fx/type tooltip-nofocus/lifecycle
                      :show-delay skylobby.fx/tooltip-show-delay
                      :style {:-fx-font-size 16}
                      :text ""
                      :graphic
                      {:fx/type :v-box
                       :children
                       (concat
                         [{:fx/type :label
                           :style {:-fx-font-size 18}
                           :text nickname}
                          {:fx/type :label
                           :text ""}]
                         (when-let [ai-name (:ai-name id)]
                           [{:fx/type :label
                             :text (str "AI Type: " ai-name)}])
                         (when-let [owner (:owner id)]
                           [{:fx/type :label
                             :text (str "Owner: " owner)}]))}}
                     :graphic
                     {:fx/type :h-box
                      :alignment :center
                      :children
                      (concat
                        (when can-kick
                          [
                           {:fx/type :button
                            :tooltip
                            {:fx/type tooltip-nofocus/lifecycle
                             :style {:-fx-font-size 16}
                             :show-delay skylobby.fx/tooltip-show-delay
                             :text "Kick"}
                            :on-action
                            (merge
                              {:event/type :skylobby.fx.event.battle/kick
                               :client-data client-data
                               :singleplayer singleplayer
                               :server-key server-key}
                              (select-keys id [:bot-name :username]))
                            :graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-account-remove:16:white"}}])
                        (when (and can-kick bot-name)
                          [{:fx/type :button
                            :tooltip
                            {:fx/type tooltip-nofocus/lifecycle
                             :style {:-fx-font-size 16}
                             :show-delay skylobby.fx/tooltip-show-delay
                             :text "AI Settings"}
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
                            :style-class ["text" (str "skylobby-players-" css-class-suffix "-nickname")]
                            :effect {:fx/type :drop-shadow
                                     :color (if (color/dark? text-color-javafx)
                                              "#d5d5d5"
                                              "black")
                                     :radius 2
                                     :spread 1}
                            :text nickname
                            :fill text-color-css
                            :strikethrough (boolean (get ignore-users (:username id)))
                            :style
                            (merge
                              {:-fx-font-smoothing-type :gray}
                              (when not-spec
                                {:-fx-font-weight "bold"}))})])}}))))}}]
         (when (:skill players-table-columns)
           [{:fx/type :table-column
             :text "Skill"
             :resizable false
             :pref-width 100
             :cell-value-factory (juxt sort-skill :skilluncertainty :skill)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[_ skilluncertainty skill]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :skill
                    {:alignment :center-left
                     :style {:-fx-text-fill (uncertainty-color skilluncertainty)}
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
              (fn [[_ _ _ player]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :status
                    {:text ""
                     :graphic
                     {:fx/type player-status
                      :player player
                      :server-key server-key}})))}}])
         (when (:team players-table-columns)
           [{:fx/type :table-column
             :text "ID"
             :resizable false
             :pref-width 86
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
                          value (str id)
                          disable (or read-only
                                      (not username)
                                      (not (or am-host
                                               (= (:username i) username)
                                               (= (:owner i) username))))]
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key [battle-id (u/nickname i) increment-ids]
                        :desc
                        (merge
                          {:fx/type :combo-box
                           :value value
                           :items items
                           :button-cell incrementing-cell
                           :cell-factory {:fx/cell-type :list-cell
                                          :describe incrementing-cell}
                           :disable (boolean disable)}
                          (when-not disable
                            {:on-value-changed
                             {:event/type (if (#{:direct-host :direct-client} server-type)
                                            :skylobby.fx.event.battle/team-changed
                                            :spring-lobby/battle-team-changed)
                              :client-data (when-not singleplayer client-data)
                              :is-me (= (:username i) username)
                              :is-bot (-> i :user :client-status :bot)
                              :id i
                              :server-key server-key}}))}}))))}}])
         (when (:ally players-table-columns)
           [{:fx/type :table-column
             :text "Ally"
             :resizable false
             :pref-width 86
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
                    (let [disable (or read-only
                                      (not username)
                                      (not (or am-host
                                               (= (:username i) username)
                                               (= (:owner i) username))))]
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (pr-str [(u/nickname i) increment-ids])
                        :desc
                        (merge
                          {:fx/type :combo-box
                           :value (-> i :battle-status :ally str)
                           :items (map str (take 16 (iterate inc 0)))
                           :button-cell incrementing-cell
                           :cell-factory {:fx/cell-type :list-cell
                                          :describe incrementing-cell}
                           :disable (boolean disable)}
                          (when-not disable
                            {:on-value-changed
                             {:event/type (if (#{:direct-host :direct-client} server-type)
                                            :skylobby.fx.event.battle/ally-changed
                                            :spring-lobby/battle-ally-changed)
                              :client-data (when-not singleplayer client-data)
                              :is-me (= (:username i) username)
                              :is-bot (-> i :user :client-status :bot)
                              :id i
                              :server-key server-key}}))}}))))}}])
         (when (:color players-table-columns)
           [{:fx/type :table-column
             :text "Color"
             :resizable false
             :pref-width 140
             :cell-value-factory (juxt :team-color u/nickname identity)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [[team-color nickname i]]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :color
                    (let [disable (or read-only
                                      (not username)
                                      (not (or am-host
                                               (= (:username i) username)
                                               (= (:owner i) username))))]
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (pr-str [nickname])
                        :desc
                        (merge
                          {:fx/type :color-picker
                           :value (fx.color/spring-color-to-javafx team-color)
                           :disable (boolean disable)}
                          (when-not disable
                            {:on-action
                             {:event/type (if (#{:direct-host :direct-client} server-type)
                                            :skylobby.fx.event.battle/color-changed
                                            :spring-lobby/battle-color-action)
                              :client-data (when-not singleplayer client-data)
                              :is-me (= (:username i) username)
                              :is-bot (-> i :user :client-status :bot)
                              :id i
                              :server-key server-key}}))}}))))}}])
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
                    (let [is-spec (-> i :battle-status :mode u/to-bool not boolean)
                          disable (or read-only
                                      (not username)
                                      (not (or (and am-host (not is-spec))
                                               (= (:username i) username)
                                               (= (:owner i) username))))]
                      {:text ""
                       :alignment :center
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (pr-str [server-key selected-tab-main battle-id (u/nickname i)])
                        :desc
                        (merge
                          {:fx/type :check-box
                           :selected (boolean is-spec)
                           :disable (boolean disable)}
                          (when-not disable
                            {:on-selected-changed
                             {:event/type (if (#{:direct-host :direct-client} server-type)
                                            :skylobby.fx.event.battle/spectate-changed
                                            :spring-lobby/battle-spectate-change)
                              :client-data (when-not singleplayer client-data)
                              :is-me (= (:username i) username)
                              :is-bot (-> i :user :client-status :bot)
                              :id i
                              :ready-on-unspec ready-on-unspec
                              :server-key server-key}}))}}))))}}])
         (when (:faction players-table-columns)
           [{:fx/type :table-column
             :text "Faction"
             :resizable false
             :pref-width 140
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
                     (cond
                       (seq side-items)
                       (let [disable (or read-only
                                         (not username)
                                         (not (or am-host
                                                  (= (:username i) username)
                                                  (= (:owner i) username))))]
                         {:fx/type ext-recreate-on-key-changed
                          :key (pr-str [server-key selected-tab-main battle-id (u/nickname i)])
                          :desc
                          (merge
                            {:fx/type :combo-box
                             :value (->> i :battle-status :side (get sides) str)
                             :items side-items
                             :disable (boolean disable)}
                            (when-not disable
                              {:on-value-changed
                               {:event/type (if (#{:direct-host :direct-client} server-type)
                                              :skylobby.fx.event.battle/side-changed
                                              :spring-lobby/battle-side-changed)
                                :client-data (when-not singleplayer client-data)
                                :is-me (= (:username i) username)
                                :is-bot (-> i :user :client-status :bot)
                                :id i
                                :indexed-mod indexed-mod
                                :server-key server-key
                                :sides sides}}))})
                       (seq mod-details-tasks)
                       {:fx/type :label
                        :text "loading..."}
                       :else
                       {:fx/type :label
                        :text (-> i :battle-status :side str)})})))}}])
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
              :describe
              (fn [[_ rank]]
                {:text (str rank)
                 :alignment :center})}}])
         (when (:country players-table-columns)
           [{:fx/type :table-column
             :text "Country"
             :resizable false
             :pref-width 70
             :cell-value-factory (comp :country :user)
             :cell-factory
             {:fx/cell-type :table-cell
              :describe
              (fn [country]
                (tufte/profile {:dynamic? true
                                :id :skylobby/player-table}
                  (tufte/p :flag
                    {:text ""
                     :alignment :center
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
                    (let [disable (boolean (or read-only
                                               am-spec))]
                      {:text ""
                       :graphic
                       {:fx/type ext-recreate-on-key-changed
                        :key (pr-str [server-key selected-tab-main battle-id (u/nickname i)])
                        :desc
                        {:fx/type :text-field
                         :disable disable
                         :text-formatter
                         (merge
                           {:fx/type :text-formatter
                            :value-converter :integer
                            :value (int (or bonus 0))}
                           (when-not disable
                             {:on-value-changed
                              {:event/type (if (#{:direct-host :direct-client} server-type)
                                             :skylobby.fx.event.battle/handicap-changed
                                             :spring-lobby/battle-handicap-change)
                               :client-data (when-not singleplayer client-data)
                               :is-bot (-> i :user :client-status :bot)
                               :id i
                               :server-key server-key}}))}}}))))}}]))}}}))

(def player-width 340)
(def player-skill-width 84)


(defn playing-by-ally-sub [context players server-key battle-id]
  (let [players (or players
                    (fx/sub-ctx context players-sub server-key battle-id))]
    (->> players
         (filter (comp :mode :battle-status))
         (group-by (comp :ally :battle-status))
         (mapv
           (fn [[ally players]]
             [ally (->> players (sort-by (comp u/to-number :id :battle-status)))]))
        (sort-by first)
        doall)))


(defn spectators-sub [context players server-key battle-id]
  (let [players (or players
                    (fx/sub-ctx context players-sub server-key battle-id))]
    (->> players
         (filter (comp not :mode :battle-status))
         (sort-by
           #(or (:username %) (:bot-name %) "")
           skylobby.fx/case-insensitive-natural-comparator))))

(defn players-not-a-table
  [{:fx/keys [context]
    :keys [auto-color battle-id players read-only scripttags server-key]}]
  (let [
        singleplayer (or (not server-key) (= :local server-key))
        css-class-suffix (cond
                           (not server-key) "replay"
                           singleplayer "singleplayer"
                           :else "multiplayer")
        scripttags (or scripttags
                       (fx/sub-val context get-in [:by-server server-key :battle :scripttags]))
        players (fx/sub-ctx context sorted-players-sub players server-key battle-id auto-color scripttags)
        playing-by-ally (fx/sub-ctx context playing-by-ally-sub players server-key battle-id)
        increment-ids (fx/sub-val context :increment-ids)
        spectators (fx/sub-ctx context spectators-sub players server-key battle-id)
        username (fx/sub-val context get-in [:by-server server-key :username])
        am-host (fx/sub-ctx context sub/am-host server-key)
        host-username (fx/sub-ctx context sub/host-username server-key)
        host-is-bot (->> players (filter (comp #{host-username} :username)) first :user :client-status :bot)
        host-ingame (fx/sub-ctx context sub/host-ingame server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        ignore-users (fx/sub-val context get-in [:ignore-users server-key])
        selected-tab-main (fx/sub-val context get-in [:selected-tab-main server-key])]
    {:fx/type ext-recreate-on-key-changed
     :key (pr-str [server-key selected-tab-main battle-id])
     :desc
     {:fx/type :scroll-pane
      :min-height 160
      :fit-to-width true
      :hbar-policy :never
      :content
      {:fx/type :flow-pane
       :hgap 16
       :vgap 16
       :children
       (concat
         (mapv
           (fn [[ally players]]
             (let [ally-n (u/to-number ally)]
               {:fx/type :v-box
                :pref-width player-width
                :flow-pane/margin {:left 16}
                :children
                [{:fx/type :label
                  :text (str " Team " (if increment-ids
                                        (when ally-n
                                          (str (inc ally-n)))
                                        (str ally)))
                  :pref-width player-width
                  :style {:-fx-font-size 24
                          :-fx-font-weight "bold"
                          :-fx-text-fill (get allyteam-colors ally-n "#ffffff")
                          :-fx-border-color "#aaaaaa"
                          :-fx-border-radius 1
                          :-fx-border-style "solid"}}
                 {:fx/type :v-box
                  :style {
                          :-fx-border-color "#666666"
                          :-fx-border-radius 1
                          :-fx-border-style "solid"}
                  :children
                  (mapv
                    (fn [player]
                      (let [text-color-javafx (or
                                                (-> player :team-color fx.color/spring-color-to-javafx)
                                                Color/WHITE)
                            text-color-css (-> text-color-javafx str u/hex-color-to-css)
                            can-kick (and (not read-only)
                                          username
                                          (not= username (:username player))
                                          (or am-host
                                              (= (:owner player) username)))]
                        {:fx/type ext-with-context-menu
                         :props {:context-menu
                                 {:fx/type player-context-menu
                                  :player player
                                  :battle-id battle-id
                                  :host-is-bot host-is-bot
                                  :host-ingame host-ingame
                                  :read-only read-only
                                  :server-key server-key}}
                         :desc
                         {:fx/type :h-box
                          :alignment :center-left
                          :children
                          (concat
                            (when can-kick
                              [
                               {:fx/type :button
                                :tooltip
                                {:fx/type tooltip-nofocus/lifecycle
                                 :style {:-fx-font-size 16}
                                 :show-delay skylobby.fx/tooltip-show-delay
                                 :text "Kick"}
                                :on-action
                                (merge
                                  {:event/type :skylobby.fx.event.battle/kick
                                   :client-data client-data
                                   :singleplayer singleplayer
                                   :server-key server-key}
                                  (select-keys player [:bot-name :username]))
                                :graphic
                                {:fx/type font-icon/lifecycle
                                 :icon-literal "mdi-account-remove:16:white"}}])
                            (when (and can-kick (:bot-name player))
                              [{:fx/type :button
                                :tooltip
                                {:fx/type tooltip-nofocus/lifecycle
                                 :style {:-fx-font-size 16}
                                 :show-delay skylobby.fx/tooltip-show-delay
                                 :text "AI Settings"}
                                :on-action
                                {:event/type :spring-lobby/show-ai-options-window
                                 :bot-name (:ai-name player)
                                 :bot-version (:ai-version player)
                                 :bot-username (:bot-name player)
                                 :server-key server-key}
                                :graphic
                                {:fx/type font-icon/lifecycle
                                 :icon-literal "mdi-settings:16:white"}}])

                            [
                             {:fx/type fx.ext.node/with-tooltip-props
                              :props
                              {:tooltip
                               {:fx/type player-name-tooltip
                                :player player
                                :server-key server-key}}
                              :desc
                              {:fx/type :text
                               :text (str " " (u/nickname player))
                               :style-class ["text" (str "skylobby-players-" css-class-suffix "-nickname")]
                               :fill text-color-css
                               :style {:-fx-font-smoothing :gray
                                       :-fx-font-weight "bold"}
                               :strikethrough (boolean (get ignore-users (:username player)))
                               :effect {:fx/type :drop-shadow
                                        :color (if (color/dark? text-color-javafx)
                                                 "#d5d5d5"
                                                 "black")
                                        :radius 2
                                        :spread 1}}}
                             (let [bonus (-> player :battle-status :handicap u/to-number)]
                               {:fx/type :label
                                :text (str
                                        (when (and bonus (not= 0 bonus))
                                          (str "+" (int bonus) "%")))})
                             {:fx/type :pane
                              :h-box/hgrow :always}
                             {:fx/type :label
                              :alignment :center-left
                              :style {:-fx-min-width player-skill-width
                                      :-fx-pref-width player-skill-width
                                      :-fx-max-width player-skill-width
                                      :-fx-text-fill (uncertainty-color (:skilluncertainty player))}
                              :text
                              (str (:skill player)
                                   " "
                                   (let [uncertainty (:skilluncertainty player)]
                                     (when (number? uncertainty)
                                       (apply str (repeat uncertainty "?")))))}
                             {:fx/type player-status
                              :player player
                              :server-key server-key}])}}))
                    players)}]}))
           playing-by-ally)
         [{:fx/type :v-box
           :flow-pane/margin {:left 16}
           :children
           [{:fx/type :label
             :text (str "Spectators (" (count spectators) ")")
             :style {:-fx-font-size 24}}
            {:fx/type :flow-pane
             :hgap 16
             :children
             (mapv
               (fn [player]
                 {:fx/type ext-with-context-menu
                  :props {:context-menu
                          {:fx/type player-context-menu
                           :player player
                           :battle-id battle-id
                           :host-is-bot host-is-bot
                           :host-ingame host-ingame
                           :read-only read-only
                           :server-key server-key}}
                  :desc
                  {:fx/type fx.ext.node/with-tooltip-props
                   :props
                   {:tooltip
                    {:fx/type player-name-tooltip
                     :player player
                     :server-key server-key}}
                   :desc
                   {:fx/type :text
                    :text (str (u/nickname player))
                    :fill "#ffffff"
                    :strikethrough (boolean (get ignore-users (:username player)))
                    :style-class ["text" (str "skylobby-players-" css-class-suffix "-nickname")]}}})
               spectators)}]}])}}}))

(defn players-table
  [{:fx/keys [context] :as state}]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :players-table
      (let [display-type (fx/sub-val context :battle-players-display-type)]
        (case display-type
          "group" (players-not-a-table state)
          ; else
          (players-table-impl state))))))
