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
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


; https://clojuredocs.org/clojure.core/split-with#example-5e48288ce4b0ca44402ef839
(defn split-by [pred coll]
  (lazy-seq
    (when-let [s (seq coll)]
      (let [!pred (complement pred)
            [xs ys] (split-with !pred s)]
        (if (seq xs)
          (cons xs (split-by pred ys))
          (let [skip (take-while pred s)
                others (drop-while pred s)
                [xs ys] (split-with !pred others)]
            (cons (concat skip xs)
                  (split-by pred ys))))))))

(defn modoptions-table
  [{:keys [am-host am-spec battle channel-name client-data modoptions singleplayer]}]
  (let [first-option (-> modoptions first second)
        is-section (-> first-option :type (= "section"))
        header (when is-section first-option)
        options (if is-section
                  (rest modoptions)
                  modoptions)
        items (->> options
                   (sort-by (comp u/to-number first))
                   (map second)
                   (filter :key)
                   (map #(update % :key (comp keyword string/lower-case))))]
    {:fx/type :v-box
     :children
     [{:fx/type :label
       :text (str (:name header))
       :style {:-fx-font-size 18}}
      {:fx/type :label
       :text (str (:desc header))
       :style {:-fx-font-size 14}}
      {:fx/type :table-view
       :column-resize-policy :constrained
       :items items
       :style {:-fx-pref-height (+ 60 (* 40 (count items)))}
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
                                          :modoption-type (:type i)
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
                                        :modoption-type (:type i)
                                        :singleplayer singleplayer}}}}}}
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
                                       :modoption-key (:key i)
                                       :modoption-type (:type i)
                                       :singleplayer singleplayer}
                    :items (or (map (comp :key second) (:items i))
                               [])}}}}
                {:text (str (:def i))})))}}]}]}))

(defn modoptions-view [{:keys [modoptions] :as state}]
  (let [sorted (sort-by (comp u/to-number first) modoptions)
        by-section (split-by (comp #{"section"} :type second) sorted)]
    {:fx/type :scroll-pane
     :fit-to-width true
     :hbar-policy :never
     :content
     {:fx/type :v-box
      :alignment :top-left
      :children
      (map
        (fn [section]
          (merge
            {:fx/type modoptions-table}
            state
            {:modoptions section}))
        by-section)}}))


(defn battle-buttons
  [{:keys [am-host am-ingame am-spec auto-get-resources auto-launch battle battle-map-details
           battle-mod-details battle-players-color-allyteam battle-map battle-modname bot-name
           bot-names bot-username bot-version bot-versions bots channel-name channels
           chat-auto-scroll client-data downloadables-by-url engine-details engine-file
           engine-filter engine-update-tasks engine-version engines extract-tasks filter-host-replay
           http-download host-ingame import-tasks in-sync indexed-map indexed-mod map-input-prefix
           map-update-tasks maps me message-drafts mod-filter mod-update-tasks
           mods my-battle-status my-client-status my-player parsed-replays-by-path rapid-data-by-id
           rapid-data-by-version rapid-download rapid-tasks-by-id scripttags server-key singleplayer
           spring-isolation-dir tasks-by-type team-counts username]
    :as state}]
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
                   {:fx/type ext-recreate-on-key-changed
                    :key (str bot-name)
                    :desc
                    {:fx/type :combo-box
                     :value bot-version
                     :disable (string/blank? bot-name)
                     :on-value-changed {:event/type :spring-lobby/change-bot-version}
                     :items (or bot-versions [])}}]}]}])
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
                  (let [filter-replay-lc (if filter-host-replay
                                           (string/lower-case filter-host-replay)
                                           "")]
                    [{:fx/type :label
                      :text " Replay: "}
                     {:fx/type :combo-box
                      :prompt-text " < host a replay > "
                      :style {:-fx-max-width 300}
                      :value (-> scripttags :game :demofile)
                      :on-value-changed {:event/type :spring-lobby/assoc-in
                                         :path [:by-server server-key :battle :scripttags :game :demofile]}
                      :on-key-pressed {:event/type :spring-lobby/host-replay-key-pressed}
                      :on-hidden {:event/type :spring-lobby/dissoc
                                  :key :filter-host-replay}
                      :items (->> parsed-replays-by-path
                                  (filter (comp :filename second))
                                  (filter (comp #(string/includes? (string/lower-case %) filter-replay-lc)
                                                :filename
                                                second))
                                  (sort-by (comp :filename second))
                                  reverse
                                  (mapv first))
                      :button-cell (fn [path] {:text (str (some-> path io/file fs/filename))})}]))
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
                     (select-keys state [:copying :downloadables-by-url :file-cache :http-download :importables-by-path :maps :spring-isolation-dir :springfiles-search-results :tasks-by-type :update-maps]))])})])}}
        {:fx/type :pane
         :v-box/vgrow :always}]
       [{:fx/type :h-box
         :alignment :center-left
         :children
         (concat
           (if-not singleplayer
             [{:fx/type :button
               :text (str
                       " "
                       (if (= 1 (:sync my-battle-status))
                         "synced"
                         "unsynced")
                       " ")
               :on-action {:event/type :spring-lobby/clear-map-and-mod-details
                           :map-resource indexed-map
                           :mod-resource indexed-mod}
               :style
               (assoc
                 (dissoc
                   (get severity-styles
                     (if (= 1 (:sync my-battle-status))
                       0 2))
                   :-fx-background-color)
                 :-fx-font-size 14)}]
             [{:fx/type :button
               :text "reload"
               :on-action {:event/type :spring-lobby/clear-map-and-mod-details
                           :map-resource indexed-map
                           :mod-resource indexed-mod}}])
           [{:fx/type :pane
             :h-box/hgrow :always}]
           (when (and (not (:mode my-battle-status))
                      (not singleplayer))
             [{:fx/type :check-box
               :selected (boolean auto-launch)
               :style {:-fx-padding "10px"}
               :on-selected-changed {:event/type :spring-lobby/assoc-in
                                     :path [:by-server server-key :auto-launch]}}
              {:fx/type :label
               :text "Auto Launch "}])
           (when (not singleplayer)
             [(let [am-away (:away my-client-status)]
                (merge
                  {:fx/type :button
                   :text (if am-away "Away" "Here")
                   :on-action {:event/type :spring-lobby/update-client-status
                               :client-data (when-not singleplayer client-data)
                               :client-status (assoc my-client-status :away (not am-away))}}
                  (when am-away
                    {:graphic
                     {:fx/type font-icon/lifecycle
                      :icon-literal "mdi-sleep:16:grey"}})))])
           [{:fx/type :button
             :text (if am-spec
                     "Spectating"
                     "Playing")
             :on-action {:event/type :spring-lobby/battle-spectate-change
                         :client-data (when-not singleplayer client-data)
                         :is-me true
                         :is-bot false
                         :id my-player
                         :value am-spec}}])}]
       [{:fx/type :h-box
         :alignment :center-left
         :style {:-fx-font-size 24}
         :children
         (concat
           (when-not am-spec
             [{:fx/type :check-box
               :selected (-> my-battle-status :ready boolean)
               :style {:-fx-padding "10px"}
               :on-selected-changed (merge me
                                      {:event/type :spring-lobby/battle-ready-change
                                       :client-data (when-not singleplayer client-data)
                                       :username username})}
              {:fx/type :label
               :text " Ready"}])
           [{:fx/type :pane
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
                 :host-ingame host-ingame})}}])}])}
    {:fx/type channel-view
     :h-box/hgrow :always
     :channel-name channel-name
     :channels channels
     :chat-auto-scroll chat-auto-scroll
     :client-data client-data
     :hide-users true
     :message-draft (get message-drafts channel-name)
     :server-key server-key}]})


(defn battle-tabs
  [{:keys [am-host am-spec battle battle-details channel-name client-data drag-allyteam drag-team
           battle-map battle-map-details battle-mod-details file-cache map-input-prefix maps
           minimap-type scripttags server-key singleplayer spring-isolation-dir spring-settings
           startpostype username users]}]
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
                                :key :minimap-type}}]}
          {:fx/type :label
           :text (str
                   (when-let [description (-> battle-map-details :mapinfo :description)]
                     description))}
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
                 :on-action {:event/type :spring-lobby/clear-start-boxes
                             :allyteam-ids (->> scripttags
                                                :game
                                                (filter (comp #(string/starts-with? % "allyteam") name first))
                                                (map
                                                  (fn [[teamid _team]]
                                                    (let [[_all id] (re-find #"allyteam(\d+)" (name teamid))]
                                                      id))))
                             :client-data client-data
                             :server-key server-key}}]))}
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
                             :channel-name channel-name
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
      [{:fx/type modoptions-view
        :am-host am-host
        :am-spec am-spec
        :battle battle
        :channel-name channel-name
        :client-data (when-not singleplayer client-data)
        :modoptions (:modoptions battle-mod-details)
        :singleplayer singleplayer}]}}
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
                              (filter fs/is-directory?)
                              reverse)
                     [])
          :columns
          [{:fx/type :table-column
            :text "Directory"
            :cell-value-factory fs/filename
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [filename]
               {:text (str filename)})}}
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
                  :source-dir i}}})}}]}]})}
    {:fx/type :tab
     :graphic {:fx/type :label
               :text "uikeys"}
     :closable false
     :content
     {:fx/type :v-box
      :children
      [{:fx/type :button
        :text "show window"
        :on-action {:event/type :spring-lobby/assoc
                    :key :show-uikeys-window}}]}}]})


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

(def battle-view-state-keys
  [:archiving :auto-get-resources :battle-players-color-allyteam :bot-name
   :bot-username :bot-version :chat-auto-scroll :cleaning :copying :downloadables-by-url :drag-allyteam
   :drag-team :engine-filter :engine-version
   :extracting :file-cache :filter-host-replay :git-clone :gitting :http-download :importables-by-path
   :map-input-prefix :map-details :media-player :message-drafts :minimap-type :mod-details :mod-filter
   :music-paused
   :parsed-replays-by-path :rapid-data-by-id :rapid-data-by-version
   :rapid-download :rapid-update :spring-isolation-dir :spring-settings :springfiles-search-results
   :tasks-by-type :username])

(def battle-view-keys
  (concat
    battle-view-state-keys
    [:auto-launch :battles :battle :channels :client-data :engines :maps :mods
     :server-key :spring-isolation-dir :update-engines :update-maps :update-mods :users]))

(defn battle-view-impl
  [{:keys [battle battles battle-players-color-allyteam bot-name bot-username bot-version
           client-data drag-allyteam drag-team engines file-cache map-input-prefix map-details
           maps minimap-type mod-details mods server-key spring-isolation-dir spring-settings
           tasks-by-type users username]
    :as state}]
  (let [{:keys [battle-id scripttags]} battle
        bot-username (if (string/blank? bot-username)
                       "bot1"
                       bot-username)
        singleplayer (= :singleplayer battle-id)
        {:keys [battle-map battle-modname channel-name host-username]} (get battles battle-id)
        channel-name (or channel-name
                         (str "__battle__" battle-id))
        host-user (get users host-username)
        me (-> battle :users (get username))
        my-battle-status (:battle-status me)
        am-host (= username host-username)
        my-client-status (-> users (get username) :client-status)
        am-ingame (:ingame my-client-status)
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
        battle-map-details (resource/cached-details map-details indexed-map)
        indexed-mod (->> mods (filter (comp #{battle-modname} :mod-name)) first)
        battle-mod-details (resource/cached-details mod-details indexed-mod)
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
        map-update-tasks (->> tasks-by-type
                              (filter (comp #{:spring-lobby/map-details :spring-lobby/reconcile-maps} first))
                              (mapcat second)
                              set)
        mod-update-tasks (->> tasks-by-type
                              (filter (comp #{:spring-lobby/mod-details :spring-lobby/reconcile-mods} first))
                              (mapcat second)
                              set)
        rapid-tasks-by-id (->> (get tasks-by-type :spring-lobby/rapid-download)
                               (map (juxt :rapid-id identity))
                               (into {}))
        players (battle-players-and-bots state)
        my-player (->> players
                       (filter (comp #{username} :username))
                       first)
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
         :host-ingame host-ingame
         :host-username host-username
         :players players
         :server-key server-key
         :scripttags scripttags
         :sides sides
         :singleplayer singleplayer
         :username username}
        (merge
          {:fx/type battle-buttons}
          state
          {
           :am-host am-host
           :am-ingame am-ingame
           :am-spec am-spec
           :battle-map battle-map
           :battle-map-details battle-map-details
           :battle-mod-details battle-mod-details
           :battle-modname battle-modname
           :bot-name bot-name
           :bot-names bot-names
           :bot-username bot-username
           :bot-version bot-version
           :bot-versions bot-versions
           :bots bots
           :channel-name channel-name
           :client-data (when-not singleplayer client-data)
           :engine-details engine-details
           :engine-file engine-file
           :engine-update-tasks engine-update-tasks
           :engine-version engine-version
           :extract-tasks extract-tasks
           :host-ingame host-ingame
           :import-tasks import-tasks
           :in-sync in-sync
           :indexed-map indexed-map
           :indexed-mod indexed-mod
           :map-update-tasks map-update-tasks
           :me me
           :mod-update-tasks mod-update-tasks
           :my-battle-status my-battle-status
           :my-client-status my-client-status
           :my-player my-player
           :rapid-tasks-by-id rapid-tasks-by-id
           :scripttags scripttags
           :singleplayer singleplayer
           :tasks-by-type tasks-by-type
           :team-counts team-counts})]}
      {:fx/type battle-tabs
       :am-host am-host
       :am-spec am-spec
       :battle battle
       :battle-details battle-details
       :battle-map battle-map
       :battle-map-details battle-map-details
       :battle-mod-details battle-mod-details
       :channel-name channel-name
       :client-data client-data
       :drag-allyteam drag-allyteam
       :drag-team drag-team
       :file-cache file-cache
       :map-input-prefix map-input-prefix
       :maps maps
       :minimap-type minimap-type
       :scripttags scripttags
       :server-key server-key
       :singleplayer singleplayer
       :spring-isolation-dir spring-isolation-dir
       :spring-settings spring-settings
       :startpostype startpostype
       :username username}]}))

(defn battle-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battle-view
      (battle-view-impl state))))


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
