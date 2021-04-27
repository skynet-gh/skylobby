(ns skylobby.fx.battle
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [skylobby.fx.channel :refer [channel-view]]
    [skylobby.fx.engine-sync :refer [engine-sync-pane]]
    [skylobby.fx.engines :refer [engines-view]]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.map-sync :refer [map-sync-pane]]
    [skylobby.fx.maps :refer [maps-view]]
    [skylobby.fx.minimap :refer [minimap-pane]]
    [skylobby.fx.mod-sync :refer [mod-sync-pane]]
    [skylobby.fx.mods :refer [mods-view]]
    [skylobby.fx.players-table :refer [players-table]]
    [skylobby.fx.sync :refer [severity-styles]]
    [skylobby.resource :as resource]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.fs :as fs]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]))


(defn battle-players-and-bots
  "Returns the sequence of all players and bots for a battle."
  [{:keys [battle users]}]
  (concat
    (mapv
      (fn [[k v]] (assoc v :username k :user (get users k)))
      (:users battle))
    (mapv
      (fn [[k v]]
        (assoc v
               :bot-name k
               :user {:client-status {:bot true}}))
      (:bots battle))))

(def battle-view-keys
  [:archiving :auto-get-resources :battles :battle :battle-players-color-allyteam :bot-name
   :bot-username :bot-version :channels :chat-auto-scroll :cleaning :client-data :copying :downloadables-by-url :drag-allyteam :drag-team :engine-filter :engine-version
   :engines :extracting :file-cache :git-clone :gitting :http-download :importables-by-path
   :isolation-type
   :map-input-prefix :map-details :maps :message-drafts :minimap-type :mod-details :mod-filter :mods :parsed-replays-by-path :rapid-data-by-id :rapid-data-by-version
   :rapid-download :rapid-update :server-key :spring-isolation-dir :spring-settings :springfiles-search-results :tasks-by-type :update-engines :update-maps :update-mods :username :users])

(defn battle-view
  [{:keys [auto-get-resources battle battles battle-players-color-allyteam bot-name bot-username bot-version
           channels chat-auto-scroll client-data downloadables-by-url
           drag-allyteam drag-team engine-filter engines file-cache http-download
           map-input-prefix map-details maps message-drafts minimap-type mod-details mod-filter mods parsed-replays-by-path rapid-data-by-id rapid-data-by-version rapid-download server-key
           spring-isolation-dir spring-settings tasks-by-type users username]
    :as state}]
  (let [{:keys [battle-id scripttags]} battle
        singleplayer (= :singleplayer battle-id)
        {:keys [battle-map battle-modname channel-name host-username]} (get battles battle-id)
        host-user (get users host-username)
        me (-> battle :users (get username))
        my-battle-status (:battle-status me)
        am-host (= username host-username)
        am-ingame (-> users (get username) :client-status :ingame)
        am-spec (-> me :battle-status :mode not)
        host-ingame (-> host-user :client-status :ingame)
        startpostype (->> scripttags
                          :game
                          :startpostype
                          spring/startpostype-name)
        battle-details (spring/battle-details {:battle battle :battles battles :users users})
        engine-version (:battle-version battle-details)
        engine-details (spring/engine-details engines engine-version)
        indexed-map (->> maps (filter (comp #{battle-map} :map-name)) first)
        battle-map-details (get map-details (resource/details-cache-key indexed-map))
        indexed-mod (->> mods (filter (comp #{battle-modname} :mod-name)) first)
        battle-mod-details (get mod-details (resource/details-cache-key indexed-mod))
        in-sync (boolean (and (resource/details? battle-map-details)
                              (resource/details? battle-mod-details)
                              (seq engine-details)))
        engine-file (:file engine-details)
        bots (fs/bots engine-file)
        bots (concat bots
                     (->> battle-mod-details :luaai
                          (map second)
                          (map (fn [ai]
                                 {:bot-name (:name ai)
                                  :bot-version "<game>"}))))
        bot-names (map :bot-name bots)
        bot-versions (map :bot-version
                          (get (group-by :bot-name bots)
                               bot-name))
        bot-name (some #{bot-name} bot-names)
        bot-version (some #{bot-version} bot-versions)
        sides (spring/mod-sides battle-mod-details)
        extract-tasks (->> (get tasks-by-type :spring-lobby/extract-7z)
                           (map (comp fs/canonical-path :file))
                           set)
        import-tasks (->> (get tasks-by-type :spring-lobby/import)
                          (map (comp fs/canonical-path :resource-file :importable))
                          set)
        engine-update-tasks (->> (get tasks-by-type :spring-lobby/reconcile-engines)
                                 set)
        map-update-tasks (->> (get tasks-by-type :spring-lobby/reconcile-maps)
                              set)
        mod-update-tasks (->> (get tasks-by-type :spring-lobby/reconcile-mods)
                              set)
        mod-details-tasks (->> (get tasks-by-type :spring-lobby/mod-details)
                               (filter (comp #{battle-modname} :mod-name))
                               set)
        rapid-tasks-by-id (->> (get tasks-by-type :spring-lobby/rapid-download)
                               (map (juxt :rapid-id identity))
                               (into {}))
        players (battle-players-and-bots state)
        team-counts (->> players
                         (map :battle-status)
                         (filter :mode)
                         (map :ally)
                         frequencies
                         vals
                         sort)]
    {:fx/type :h-box
     :style {:-fx-font-size 15}
     :alignment :top-left
     :children
     [{:fx/type :v-box
       :h-box/hgrow :always
       :children
       [{:fx/type players-table
         :v-box/vgrow :always
         :am-host singleplayer
         :battle-modname battle-modname
         :battle-players-color-allyteam battle-players-color-allyteam
         :channel-name channel-name
         :client-data (when-not singleplayer client-data)
         :host-username host-username
         :players players
         :server-key server-key
         :scripttags scripttags
         :sides sides
         :singleplayer singleplayer
         :username username}
        {:fx/type :h-box
         :style {
                 :-fx-pref-height 400
                 :-fx-max-height 400}
         :children
         [{:fx/type :v-box
           :children
           (concat
             [
              {:fx/type :scroll-pane
               :fit-to-width true
               :hbar-policy :never
               :content
               {:fx/type :v-box
                :children
                (concat
                  [{:fx/type :label
                    :style {:-fx-font-size 20}
                    :text (str " "
                               (when (< 1 (count team-counts))
                                 (string/join "v" team-counts)))}
                   {:fx/type :h-box
                    :alignment :center-left
                    :children
                    [{:fx/type :check-box
                      :selected (boolean battle-players-color-allyteam)
                      :on-selected-changed {:event/type :spring-lobby/assoc
                                            :key :battle-players-color-allyteam}}
                     {:fx/type :label
                      :text " Color player name by allyteam"}]}]
                  (when (or singleplayer (not am-spec))
                    [{:fx/type :flow-pane
                      :children
                      [{:fx/type :button
                        :text "Add Bot"
                        :disable (or (and am-spec (not singleplayer))
                                     (string/blank? bot-username)
                                     (string/blank? bot-name)
                                     (string/blank? bot-version))
                        :on-action {:event/type :spring-lobby/add-bot
                                    :battle battle
                                    :bot-username bot-username
                                    :bot-name bot-name
                                    :bot-version bot-version
                                    :client-data client-data
                                    :singleplayer singleplayer
                                    :username username}}
                       #_
                       {:fx/type :h-box
                        :alignment :center-left
                        :children
                        [{:fx/type :label
                          :text " Bot Name: "}
                         {:fx/type :text-field
                          :prompt-text "Bot Name"
                          :text (str bot-username)
                          :on-text-changed {:event/type :spring-lobby/change-bot-username}}]}
                       {:fx/type :h-box
                        :alignment :center-left
                        :children
                        [
                         {:fx/type :label
                          :text " AI: "}
                         {:fx/type :combo-box
                          :value bot-name
                          :disable (empty? bot-names)
                          :on-value-changed {:event/type :spring-lobby/change-bot-name
                                             :bots bots}
                          :items (sort bot-names)}]}
                       {:fx/type :h-box
                        :alignment :center-left
                        :children
                        [
                         #_
                         {:fx/type :label
                          :text " Version: "}
                         {:fx/type :combo-box
                          :value bot-version
                          :disable (string/blank? bot-name)
                          :on-value-changed {:event/type :spring-lobby/change-bot-version}
                          :items (or bot-versions [])}]}]}])
                  (when-not singleplayer
                    [{:fx/type :h-box
                      :alignment :center-left
                      :children
                      [{:fx/type :check-box
                        :selected (boolean auto-get-resources)
                        :on-selected-changed {:event/type :spring-lobby/assoc
                                              :key :auto-get-resources}}
                       {:fx/type :label
                        :text " Auto import or download resources"}]}])
                  [{:fx/type :h-box
                    :alignment :center-left
                    :children
                    (concat
                      (when (and am-host (not singleplayer))
                        [{:fx/type :label
                          :text " Replay: "}
                         {:fx/type :combo-box
                          :prompt-text " < host a replay > "
                          :style {:-fx-max-width 300}
                          :value (-> scripttags :game :demofile)
                          :on-value-changed {:event/type :spring-lobby/assoc-in
                                             :path [:battle :scripttags :game :demofile]}
                          :items (->> parsed-replays-by-path
                                      (sort-by (comp :filename second))
                                      reverse
                                      (mapv first))
                          :button-cell (fn [path] {:text (str (some-> path io/file fs/filename))})}])
                      (when (-> scripttags :game :demofile)
                        [{:fx/type :button
                          :on-action {:event/type :spring-lobby/dissoc-in
                                      :path [:battle :scripttags :game :demofile]}
                          :graphic
                          {:fx/type font-icon/lifecycle
                           :icon-literal "mdi-close:16:white"}}]))}
                   (if singleplayer
                     {:fx/type :v-box
                      :children
                      [
                       {:fx/type engines-view
                        :downloadables-by-url downloadables-by-url
                        :http-download http-download
                        :engine-filter engine-filter
                        :engine-version engine-version
                        :engines engines
                        :tasks-by-type tasks-by-type
                        :on-value-changed {:event/type :spring-lobby/singleplayer-engine-changed}
                        :spring-isolation-dir spring-isolation-dir}
                       (if (seq engine-details)
                         {:fx/type mods-view
                          :downloadables-by-url downloadables-by-url
                          :http-download http-download
                          :engine-file engine-file
                          :mod-filter mod-filter
                          :mod-name battle-modname
                          :mods mods
                          :rapid-data-by-id rapid-data-by-id
                          :rapid-download rapid-download
                          :tasks-by-type tasks-by-type
                          :on-value-changed {:event/type :spring-lobby/singleplayer-mod-changed}
                          :spring-isolation-dir spring-isolation-dir}
                         {:fx/type :label
                          :text " Game: Get an engine first"})
                       {:fx/type maps-view
                        :downloadables-by-url downloadables-by-url
                        :http-downloads http-download
                        :map-input-prefix map-input-prefix
                        :map-name battle-map
                        :maps maps
                        :tasks-by-type tasks-by-type
                        :on-value-changed {:event/type :spring-lobby/singleplayer-map-changed}
                        :spring-isolation-dir spring-isolation-dir}]}
                     {:fx/type :flow-pane
                      :vgap 5
                      :hgap 5
                      :padding 5
                      :children
                      (if singleplayer
                        (concat
                          [{:fx/type :h-box
                            :alignment :center-left
                            :children
                            [{:fx/type engines-view
                              :downloadables-by-url downloadables-by-url
                              :engine-filter engine-filter
                              :engine-version engine-version
                              :engines engines
                              :spring-isolation-dir spring-isolation-dir
                              :suggest true
                              :on-value-changed {:event/type :spring-lobby/assoc-in
                                                 :path [:by-server :local :battles :singleplayer :battle-version]}}]}]
                          (if (seq engine-details)
                            [{:fx/type mods-view
                              :mod-filter mod-filter
                              :mod-name battle-modname
                              :mods mods
                              :rapid-data-by-version rapid-data-by-version
                              :spring-isolation-dir spring-isolation-dir
                              :suggest true
                              :on-value-changed {:event/type :spring-lobby/assoc-in
                                                 :path [:by-server :local :battles :singleplayer :battle-modname]}}]
                            [{:fx/type :h-box
                              :alignment :center-left
                              :children
                              [{:fx/type :label
                                :text " Game: Get an engine first"}]}])
                          [{:fx/type :h-box
                            :alignment :center-left
                            :children
                            [{:fx/type maps-view
                              :map-input-prefix map-input-prefix
                              :map-name battle-map
                              :maps maps
                              :spring-isolation-dir spring-isolation-dir
                              :suggest true
                              :on-value-changed {:event/type :spring-lobby/assoc-in
                                                 :path [:by-server :local :battles :singleplayer :battle-map]}}]}])
                        [
                         (merge
                           {:fx/type engine-sync-pane
                            :engine-details engine-details
                            :engine-file engine-file
                            :engine-version engine-version
                            :extract-tasks extract-tasks
                            :engine-update-tasks engine-update-tasks}
                           (select-keys state [:copying :downloadables-by-url :extracting :file-cache :http-download :importables-by-path :spring-isolation-dir :springfiles-search-results :tasks-by-type :update-engines]))
                         (merge
                           {:fx/type mod-sync-pane
                            :battle-modname battle-modname
                            :battle-mod-details battle-mod-details
                            :engine-details engine-details
                            :engine-file engine-file
                            :indexed-mod indexed-mod
                            :mod-details-tasks mod-details-tasks
                            :mod-update-tasks mod-update-tasks
                            :rapid-tasks-by-id rapid-tasks-by-id}
                           (select-keys state [:copying :downloadables-by-url :file-cache :gitting :http-download :importables-by-path :mods :rapid-data-by-version :rapid-download :spring-isolation-dir :springfiles-search-results :tasks-by-type :update-mods]))
                         (merge
                           {:fx/type map-sync-pane
                            :battle-map battle-map
                            :battle-map-details battle-map-details
                            :indexed-map indexed-map
                            :import-tasks import-tasks
                            :map-update-tasks map-update-tasks}
                           (select-keys state [:copying :downloadables-by-url :file-cache :http-download :importables-by-path :maps :spring-isolation-dir :tasks-by-type :update-maps]))])})])}}
              {:fx/type :pane
               :v-box/vgrow :always}]
             (when-not singleplayer
               [{:fx/type :label
                 :text (str
                         " "
                         (if (= 1 (:sync my-battle-status))
                           "synced"
                           "unsynced")
                         " ")
                 :style
                 (merge
                   {:-fx-background-radius 3
                    :-fx-border-color "#666666"
                    :-fx-border-radius 3
                    :-fx-border-style "solid"
                    :-fx-border-width 1}
                   (get severity-styles
                     (if (= 1 (:sync my-battle-status))
                       0 2)))}])
             [{:fx/type :h-box
               :alignment :center-left
               :style {:-fx-font-size 24}
               :children
               [{:fx/type :check-box
                 :selected (-> my-battle-status :ready boolean)
                 :style {:-fx-padding "10px"}
                 :on-selected-changed (merge me
                                        {:event/type :spring-lobby/battle-ready-change
                                         :client-data (when-not singleplayer client-data)
                                         :username username})}
                {:fx/type :label
                 :text (if am-spec " Auto Launch" " Ready")}
                {:fx/type :pane
                 :h-box/hgrow :always}
                {:fx/type fx.ext.node/with-tooltip-props
                 :props
                 {:tooltip
                  {:fx/type :tooltip
                   :show-delay [10 :ms]
                   :style {:-fx-font-size 12}
                   :text (cond
                           am-host "You are the host, start the game"
                           host-ingame "Join game in progress"
                           :else (str "Call vote to start the game"))}}
                 :desc
                 {:fx/type :button
                  :text (cond
                          (and am-ingame (not singleplayer))
                          "Game running"
                          (and am-spec (not host-ingame) (not singleplayer))
                          "Game not running"
                          :else
                          (str (if (and (not singleplayer) (or host-ingame am-spec))
                                 "Join" "Start")
                               " Game"))
                  :disable (boolean (and (not singleplayer)
                                         (or (and (not host-ingame) am-spec)
                                             (and (not am-spec) am-ingame)
                                             (not in-sync))))
                  :on-action
                  (merge
                    {:event/type :spring-lobby/start-battle}
                    state
                    {:am-host am-host
                     :am-spec am-spec
                     :battle-status my-battle-status
                     :channel-name channel-name
                     :client-data client-data
                     :host-ingame host-ingame})}}]}])}
          {:fx/type channel-view
           :h-box/hgrow :always
           :channel-name channel-name
           :channels channels
           :chat-auto-scroll chat-auto-scroll
           :client-data client-data
           :hide-users true
           :message-draft (get message-drafts channel-name)
           :server-key server-key}]}]}
      {:fx/type :tab-pane
       :style {:-fx-min-width (+ u/minimap-size 20)
               :-fx-pref-width (+ u/minimap-size 20)
               :-fx-max-width (+ u/minimap-size 20)
               :-fx-pref-height (+ u/minimap-size 164)}
       :tabs
       [{:fx/type :tab
         :graphic {:fx/type :label
                   :text "map"}
         :closable false
         :content
         {:fx/type :scroll-pane
          :fit-to-width true
          :hbar-policy :never
          :vbar-policy :always
          :content
          {:fx/type :v-box
           :alignment :top-left
           :children
           [{:fx/type minimap-pane
             :am-spec am-spec
             :battle-details battle-details
             :client-data client-data
             :drag-allyteam drag-allyteam
             :drag-team drag-team
             :map-name battle-map
             :map-details battle-map-details
             :minimap-type minimap-type
             :minimap-type-key :minimap-type
             :scripttags scripttags
             :singleplayer singleplayer}
            {:fx/type :v-box
             :children
             [{:fx/type :h-box
               :alignment :center-left
               :children
               [{:fx/type :label
                 :text (str " Size: "
                            (when-let [{:keys [map-width map-height]} (-> battle-map-details :smf :header)]
                              (str
                                (when map-width (quot map-width 64))
                                " x "
                                (when map-height (quot map-height 64)))))}
                {:fx/type :pane
                 :h-box/hgrow :always}
                {:fx/type :combo-box
                 :value minimap-type
                 :items u/minimap-types
                 :on-value-changed {:event/type :spring-lobby/assoc
                                    :key :spring-lobby/minimap-type}}]}
              {:fx/type :h-box
               :style {:-fx-max-width u/minimap-size}
               :children
               (let [{:keys [battle-status]} (-> battle :users (get username))]
                 [{:fx/type maps-view
                   :disable (and (not singleplayer) am-spec)
                   :map-name battle-map
                   :maps maps
                   :map-input-prefix map-input-prefix
                   :spring-isolation-dir spring-isolation-dir
                   :on-value-changed
                   (cond
                     singleplayer
                     {:event/type :spring-lobby/assoc-in
                      :path [:by-server :local :battles :singleplayer :battle-map]}
                     am-host
                     {:event/type :spring-lobby/battle-map-change
                      :client-data client-data
                      :maps maps}
                     :else
                     {:event/type :spring-lobby/suggest-battle-map
                      :battle-status battle-status
                      :channel-name channel-name
                      :client-data client-data})}])}
              {:fx/type :h-box
               :alignment :center-left
               :children
               (concat
                 [{:fx/type :label
                   :alignment :center-left
                   :text " Start Positions: "}
                  {:fx/type :combo-box
                   :value startpostype
                   :items (map str (vals spring/startpostypes))
                   :disable (and (not singleplayer) am-spec)
                   :on-value-changed {:event/type :spring-lobby/battle-startpostype-change
                                      :am-host am-host
                                      :channel-name channel-name
                                      :client-data client-data
                                      :singleplayer singleplayer}}]
                 (when (= "Choose before game" startpostype)
                   [{:fx/type :button
                     :text "Reset"
                     :disable (and (not singleplayer) am-spec)
                     :on-action {:event/type :spring-lobby/reset-start-positions
                                 :client-data client-data
                                 :server-key server-key}}])
                 (when (= "Choose in game" startpostype)
                   [{:fx/type :button
                     :text "Clear boxes"
                     :disable (and (not singleplayer) am-spec)
                     :on-action {:event/type :spring-lobby/clear-start-boxes}}]))}
              {:fx/type :label
               :text (str "")}
              {:fx/type :h-box
               :alignment :center-left
               :children
               (concat
                 (when-not am-host
                   [{:fx/type :button
                     :text "Balance"
                     :on-action {:event/type :spring-lobby/battle-balance
                                 :am-host am-host
                                 :battle battle
                                 :client-data (when-not singleplayer client-data)
                                 :users users
                                 :username username}}])
                 [{:fx/type :button
                   :text "Fix Colors"
                   :on-action {:event/type :spring-lobby/battle-fix-colors
                               :am-host am-host
                               :battle battle
                               :channel-name channel-name
                               :client-data (when-not singleplayer client-data)
                               :users users
                               :username username}}])}
              {:fx/type :h-box
               :alignment :center-left
               :children
               (concat
                 (when am-host
                   [{:fx/type :button
                     :text "FFA"
                     :on-action {:event/type :spring-lobby/battle-teams-ffa
                                 :battle battle
                                 :client-data (when-not singleplayer client-data)
                                 :users users
                                 :username username}}
                    {:fx/type :button
                     :text "2 teams"
                     :on-action {:event/type :spring-lobby/battle-teams-2
                                 :battle battle
                                 :client-data (when-not singleplayer client-data)
                                 :users users
                                 :username username}}
                    {:fx/type :button
                     :text "3 teams"
                     :on-action {:event/type :spring-lobby/battle-teams-3
                                 :battle battle
                                 :client-data (when-not singleplayer client-data)
                                 :users users
                                 :username username}}
                    {:fx/type :button
                     :text "4 teams"
                     :on-action {:event/type :spring-lobby/battle-teams-4
                                 :battle battle
                                 :client-data (when-not singleplayer client-data)
                                 :users users
                                 :username username}}
                    {:fx/type :button
                     :text "Humans vs Bots"
                     :on-action {:event/type :spring-lobby/battle-teams-humans-vs-bots
                                 :battle battle
                                 :client-data (when-not singleplayer client-data)
                                 :users users
                                 :username username}}]))}]}]}}}
        {:fx/type :tab
         :graphic {:fx/type :label
                   :text "modoptions"}
         :closable false
         :content
         {:fx/type :v-box
          :alignment :top-left
          :children
          [{:fx/type :table-view
            :v-box/vgrow :always
            :column-resize-policy :constrained
            :items (or (some->> battle-mod-details
                                :modoptions
                                (map second)
                                (filter :key)
                                (map #(update % :key (comp keyword string/lower-case)))
                                (sort-by :key)
                                (remove (comp #{"section"} :type)))
                       [])
            :columns
            [{:fx/type :table-column
              :text "Key"
              :cell-value-factory identity
              :cell-factory
              {:fx/cell-type :table-cell
               :describe
               (fn [i]
                 {:text ""
                  :graphic
                  {:fx/type fx.ext.node/with-tooltip-props
                   :props
                   {:tooltip
                    {:fx/type :tooltip
                     :show-delay [10 :ms]
                     :text (str (:name i) "\n\n" (:desc i))}}
                   :desc
                   (merge
                     {:fx/type :label
                      :text (or (some-> i :key name str)
                                "")}
                     (when-let [v (-> battle :scripttags :game :modoptions (get (:key i)))]
                       (when (not (spring-script/tag= i v))
                         {:style {:-fx-font-weight :bold}})))}})}}
             {:fx/type :table-column
              :text "Value"
              :cell-value-factory identity
              :cell-factory
              {:fx/cell-type :table-cell
               :describe
               (fn [i]
                 (let [v (-> battle :scripttags :game :modoptions (get (:key i)))]
                   (case (:type i)
                     "bool"
                     {:text ""
                      :graphic
                      {:fx/type ext-recreate-on-key-changed
                       :key (str (:key i))
                       :desc
                       {:fx/type fx.ext.node/with-tooltip-props
                        :props
                        {:tooltip
                         {:fx/type :tooltip
                          :show-delay [10 :ms]
                          :text (str (:name i) "\n\n" (:desc i))}}
                        :desc
                        {:fx/type :check-box
                         :selected (u/to-bool (or v (:def i)))
                         :on-selected-changed {:event/type :spring-lobby/modoption-change
                                               :am-host am-host
                                               :channel-name channel-name
                                               :client-data client-data
                                               :modoption-key (:key i)
                                               :singleplayer singleplayer}
                         :disable (and (not singleplayer) am-spec)}}}}
                     "number"
                     {:text ""
                      :graphic
                      {:fx/type ext-recreate-on-key-changed
                       :key (str (:key i))
                       :desc
                       {:fx/type fx.ext.node/with-tooltip-props
                        :props
                        {:tooltip
                         {:fx/type :tooltip
                          :show-delay [10 :ms]
                          :text (str (:name i) "\n\n" (:desc i))}}
                        :desc
                        {:fx/type :text-field
                         :disable (and (not singleplayer) am-spec)
                         :text-formatter
                         {:fx/type :text-formatter
                          :value-converter :number
                          :value (u/to-number (or v (:def i)))
                          :on-value-changed {:event/type :spring-lobby/modoption-change
                                             :am-host am-host
                                             :channel-name channel-name
                                             :client-data client-data
                                             :modoption-key (:key i)
                                             :modoption-type (:type i)}}}}}}
                     "list"
                     {:text ""
                      :graphic
                      {:fx/type ext-recreate-on-key-changed
                       :key (str (:key i))
                       :desc
                       {:fx/type fx.ext.node/with-tooltip-props
                        :props
                        {:tooltip
                         {:fx/type :tooltip
                          :show-delay [10 :ms]
                          :text (str (:name i) "\n\n" (:desc i))}}
                        :desc
                        {:fx/type :combo-box
                         :disable (and (not singleplayer) am-spec)
                         :value (or v (:def i))
                         :on-value-changed {:event/type :spring-lobby/modoption-change
                                            :am-host am-host
                                            :channel-name channel-name
                                            :client-data client-data
                                            :modoption-key (:key i)}
                         :items (or (map (comp :key second) (:items i))
                                    [])}}}}
                     {:text (str (:def i))})))}}]}]}}
        {:fx/type :tab
         :graphic {:fx/type :label
                   :text "Spring settings"}
         :closable false
         :content
         (let [{:keys [auto-backup backup-name confirmed results]} spring-settings
               spring-settings-dir (fs/spring-settings-root)
               dest-dir (when-not (string/blank? backup-name)
                          (fs/file spring-settings-dir backup-name))]
           {:fx/type :v-box
            :children
            [{:fx/type :label
              :text (str spring-isolation-dir)}
             {:fx/type :label
              :text " Includes springsettings.cfg, LuiUI/Config, and uikeys.txt"}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :check-box
                :selected (boolean auto-backup)
                :on-selected-changed {:event/type :spring-lobby/assoc-in
                                      :path [:spring-settings :auto-backup]}}
               {:fx/type :label
                :text " Auto Backup "}]}
             {:fx/type :label
              :wrap-text true
              :text (str " If enabled, will copy these files into a backup folder in "
                         spring-settings-dir
                         " named 'backup-yyyyMMdd-HHmmss' before Spring is run.")}
             {:fx/type :pane
              :style {:-fx-margin-top 8
                      :-fx-margin-bottom 8}}
             {:fx/type :label
              :text " Manual Backup: "}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :label
                :text " Name: "}
               {:fx/type :text-field
                :text (str (:backup-name spring-settings))
                :on-text-changed {:event/type :spring-lobby/assoc-in
                                  :path [:spring-settings :backup-name]}}]}
             {:fx/type :button
              :text " Backup! "
              :disable (boolean (or (string/blank? backup-name)
                                    (and (not confirmed)
                                         (fs/file-exists? file-cache dest-dir))))
              :on-action {:event/type :spring-lobby/spring-settings-copy
                          :confirmed confirmed
                          :dest-dir dest-dir
                          :file-cache file-cache
                          :source-dir spring-isolation-dir}}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :check-box
                :selected (boolean confirmed)
                :on-selected-changed {:event/type :spring-lobby/assoc-in
                                      :path [:spring-settings :confirmed]}}
               {:fx/type :label
                :text " OVERWRITE EXISTING"}]}
             {:fx/type :v-box
              :children
              (map
                (fn [[path result]]
                  {:fx/type :label
                   :text (str " " path " "
                              (case result
                                :copied "was copied"
                                :does-not-exist "does not exist"
                                :error "errored"
                                "unknown"))})
                (get results (fs/canonical-path spring-isolation-dir)))}
             {:fx/type :label
              :text " Restore"
              :style {:-fx-font-size 20}}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :button
                :text ""
                :on-action {:event/type :spring-lobby/spring-settings-refresh}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-refresh:16:white"}}
               {:fx/type :label
                :text (str " From " (fs/spring-settings-root))}]}
             {:fx/type :table-view
              :v-box/vgrow :always
              :column-resize-policy :constrained
              :items (or (some->> (fs/spring-settings-root)
                                  fs/list-files ; TODO IO in render
                                  (filter fs/is-directory?))
                         [])
              :columns
              [{:fx/type :table-column
                :text "Directory"
                :cell-value-factory identity
                :cell-factory
                {:fx/cell-type :table-cell
                 :describe
                 (fn [i]
                   {:text (str (fs/filename i))})}}
               {:fx/type :table-column
                :text "Action"
                :cell-value-factory identity
                :cell-factory
                {:fx/cell-type :table-cell
                 :describe
                 (fn [i]
                   {:text (when (get results (fs/canonical-path i))
                            " copied!")
                    :graphic
                    {:fx/type :button
                     :text "Restore"
                     :on-action
                     {:event/type :spring-lobby/spring-settings-copy
                      :confirmed true ; TODO confirm
                      :dest-dir spring-isolation-dir
                      :file-cache file-cache
                      :source-dir i}}})}}]}]})}]}]}))


(def multi-battle-view-keys
  (concat
    battle-view-keys
    [:singleplayer-battle :singleplayer-battle-map-details :singleplayer-battle-mod-details]))

(defn multi-battle-view
  [{:keys [battle]}]
  (if (< 1 (count battle))
    {:fx/type :tab-pane
     :style {:-fx-font-size 16}
     :tabs
     [{:fx/type :tab
       :on-close-request {:event/type :spring-lobby/leave-battle
                          :client-data (:client-data battle)}
       :graphic {:fx/type :label
                 :text "Multiplayer"}
       :content
       (merge
         {:fx/type battle-view}
         battle)}
      {:fx/type :tab
       :on-close-request {:event/type :spring-lobby/dissoc
                          :key :singleplayer-battle}
       :graphic {:fx/type :label
                 :text "Singleplayer"}
       :content
       (merge
         {:fx/type battle-view}
         battle)}]}
    (merge
      {:fx/type battle-view}
      battle)))
