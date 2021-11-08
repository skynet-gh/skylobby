(ns skylobby.fx.battle
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [cljfx.lifecycle :as lifecycle]
    [cljfx.mutator :as mutator]
    [cljfx.prop :as prop]
    [clojure.java.io :as io]
    [clojure.string :as string]
    java-time
    [skylobby.discord :as discord]
    [skylobby.fx :refer [monospace-font-family]]
    [skylobby.fx.channel :as fx.channel]
    [skylobby.fx.engine-sync :refer [engine-sync-pane]]
    [skylobby.fx.engines :refer [engines-view]]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.map-sync :refer [map-sync-pane]]
    [skylobby.fx.maps :refer [maps-view]]
    [skylobby.fx.minimap :as fx.minimap]
    [skylobby.fx.mod-sync :refer [mod-sync-pane]]
    [skylobby.fx.mods :refer [mods-view]]
    [skylobby.fx.players-table :refer [players-table]]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.sync :refer [ok-severity warn-severity error-severity]]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.resource :as resource]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.fs :as fs]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.stage Popup)
    (javafx.scene Node)))


(set! *warn-on-reflection* true)


(def minimap-types
  ["minimap" "metalmap" "heightmap"])

(def battle-layouts
  [
   "vertical"
   "horizontal"])

(def font-icon-size 20)


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
  [{:fx/keys [context]
    :keys [modoptions singleplayer server-key]}]
  (let [am-host (fx/sub-ctx context sub/am-host server-key)
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        scripttags (fx/sub-val context get-in [:by-server server-key :battle :scripttags])
        first-option (-> modoptions first second)
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
               {:fx/type tooltip-nofocus/lifecycle
                :show-delay skylobby.fx/tooltip-show-delay
                :text (str (:name i) "\n\n" (:desc i))}}
              :desc
              (merge
                {:fx/type :label
                 :text (or (some-> i :key name str)
                           "")}
                (when-let [v (get-in scripttags ["game" "modoptions" (some-> i :key name str)])]
                  (when (not (spring-script/tag= i v))
                    {:style {:-fx-font-weight :bold}})))}})}}
        {:fx/type :table-column
         :text "Value"
         :cell-value-factory identity
         :cell-factory
         {:fx/cell-type :table-cell
          :describe
          (fn [i]
            (let [v (get-in scripttags ["game" "modoptions" (some-> i :key name str)])]
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
                    {:fx/type tooltip-nofocus/lifecycle
                     :show-delay skylobby.fx/tooltip-show-delay
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
                    {:fx/type tooltip-nofocus/lifecycle
                     :show-delay skylobby.fx/tooltip-show-delay
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
                    {:fx/type tooltip-nofocus/lifecycle
                     :show-delay skylobby.fx/tooltip-show-delay
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

(defn modoptions-view
  [{:keys [modoptions server-key]}]
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
          {:fx/type modoptions-table
           :modoptions section
           :server-key server-key})
        by-section)}}))


(defn sync-button [{:fx/keys [context]
                    :keys [server-key]}]
  (let [
        my-battle-status (fx/sub-ctx context sub/my-battle-status server-key)
        my-sync-status (int (or (:sync my-battle-status) 0))
        sync-button-style (dissoc
                            (case my-sync-status
                              1 ok-severity
                              2 error-severity
                              ; else
                              warn-severity)
                            :-fx-background-color)
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        mod-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
        map-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map])
        indexed-map (fx/sub-ctx context sub/indexed-map spring-root map-name)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-root mod-name)]
    {:fx/type :button
     :text (str
             " "
             (case my-sync-status
               1 "synced"
               2 "unsynced"
               ; else
               "syncing")
             " ")
     :on-action {:event/type :spring-lobby/clear-map-and-mod-details
                 :map-resource indexed-map
                 :mod-resource indexed-mod
                 :spring-root spring-root}
     :style sync-button-style}))

; https://github.com/cljfx/cljfx/blob/ec3c34e619b2408026b9f2e2ff8665bebf70bf56/examples/e35_popup.clj
(def popup-width 300)
(def ext-with-shown-on
  (fx/make-ext-with-props
    {:shown-on (prop/make
                 (mutator/adder-remover
                   (fn [^Popup popup ^Node node]
                     (let [bounds (.getBoundsInLocal node)
                           node-pos (.localToScreen node (* 0.5 (.getWidth bounds)) 0.0)]
                       (.show popup node
                              (- (.getX node-pos) (* 0.5 popup-width))
                              (.getY node-pos))))
                   (fn [^Popup popup _]
                     (.hide popup)))
                 lifecycle/dynamic)}))

(defn battle-buttons
  [{:fx/keys [context]
    :keys [server-key my-player team-counts team-skills]}]
  (let [
        singleplayer (= :local server-key)
        bot-name (fx/sub-val context :bot-name)
        bot-username (fx/sub-val context :bot-username)
        bot-username (if (string/blank? bot-username)
                       "bot1"
                       bot-username)
        bot-version (fx/sub-val context :bot-version)
        battle-resource-details (fx/sub-val context :battle-resource-details)
        auto-unspec (fx/sub-val context get-in [:by-server server-key :auto-unspec])
        auto-launch-settings (fx/sub-val context :auto-launch)
        auto-launch (if (contains? auto-launch-settings server-key)
                      (get auto-launch-settings server-key)
                      true)
        battle (fx/sub-val context get-in [:by-server server-key :battle])
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        battles (fx/sub-val context get-in [:by-server server-key :battles])
        users (fx/sub-val context get-in [:by-server server-key :users])
        filter-host-replay (fx/sub-val context :filter-host-replay)
        interleave-ally-player-ids (fx/sub-val context :interleave-ally-player-ids)
        parsed-replays-by-path (fx/sub-val context :parsed-replays-by-path)
        ready-on-unspec (fx/sub-val context :ready-on-unspec)
        show-team-skills (fx/sub-val context :show-team-skills)
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        spring-running (fx/sub-val context get-in [:spring-running server-key battle-id])
        scripttags (fx/sub-val context get-in [:by-server server-key :battle :scripttags])
        engine-version (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-version])
        engine-details (fx/sub-ctx context sub/indexed-engine spring-root engine-version)
        mod-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
        map-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map])
        indexed-map (fx/sub-ctx context sub/indexed-map spring-root map-name)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-root mod-name)
        engine-bots (:engine-bots engine-details)
        battle-map-details (fx/sub-ctx context skylobby.fx/map-details-sub indexed-map)
        battle-mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        bots (concat engine-bots
                     (->> battle-mod-details :luaai
                          (map second)
                          (map (fn [ai]
                                 {:bot-name (:name ai)
                                  :bot-version "<game>"}))))
        bot-names (map :bot-name bots)
        bot-name (some #{bot-name} bot-names)
        bot-versions (map :bot-version
                          (get (group-by :bot-name bots)
                               bot-name))
        bot-version (some #{bot-version} bot-versions)
        sides (spring/mod-sides battle-mod-details)
        am-host (fx/sub-ctx context sub/am-host server-key)
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        host-ingame (fx/sub-ctx context sub/host-ingame server-key)
        username (fx/sub-val context get-in [:by-server server-key :username])
        my-client-status (fx/sub-ctx context sub/my-client-status server-key)
        am-away (:away my-client-status)
        my-battle-status (fx/sub-ctx context sub/my-battle-status server-key)
        my-team-color (fx/sub-ctx context sub/my-team-color server-key)
        my-sync-status (int (or (:sync my-battle-status) 0))
        in-sync (= 1 (:sync my-battle-status))
        ringing-specs (seq (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/ring-specs))
        discord-channel (discord/channel-to-promote {:mod-name mod-name
                                                     :server-url (:server-url client-data)})
        now (fx/sub-val context :now)
        discord-promoted (fx/sub-val context get-in [:discord-promoted discord-channel])
        discord-promoted-diff (when discord-promoted (- now discord-promoted))
        discord-promote-cooldown (boolean (and discord-promoted-diff
                                               (< discord-promoted-diff discord/cooldown)))
        sync-button-style (dissoc
                            (case my-sync-status
                              1 ok-severity
                              2 error-severity
                              ; else
                              warn-severity)
                            :-fx-background-color
                            :-fx-font-size)
        sync-buttons (if-not singleplayer
                       [{:fx/type :h-box
                         :alignment :center-left
                         :style {:-fx-font-size 16}
                         :children
                         [{:fx/type sync-button
                           :server-key server-key}
                          {:fx/type :button
                           :text ""
                           :graphic {:fx/type font-icon/lifecycle
                                     :icon-literal (if battle-resource-details
                                                     (str "mdi-window-maximize:" (+ 5 font-icon-size) ":white")
                                                     (str "mdi-open-in-new:" (+ 5 font-icon-size) ":white"))}
                           :on-action {:event/type :spring-lobby/assoc
                                       :key :battle-resource-details
                                       :value (not (boolean battle-resource-details))}
                           :style sync-button-style}]}]
                       [{:fx/type :button
                         :text "reload"
                         :on-action {:event/type :spring-lobby/clear-map-and-mod-details
                                     :map-resource indexed-map
                                     :mod-resource indexed-mod}}])
        buttons (concat
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
                                :username username}}]
                  (when-not singleplayer
                    [{:fx/type :button
                      :text "Ring Specs"
                      :disable (boolean ringing-specs)
                      :on-action
                      {:event/type :spring-lobby/add-task
                       :task
                       {:spring-lobby/task-type :spring-lobby/ring-specs
                        :battle-users (:users battle)
                        :channel-name channel-name
                        :client-data client-data
                        :users users}}}
                     {:fx/type :button
                      :text (if discord-channel
                              (if discord-promote-cooldown
                                (str "Promote every " (.toMinutesPart (java-time/duration discord/cooldown :millis)) "m")
                                "Promote to Discord")
                              "Promote")
                      :disable discord-promote-cooldown
                      :on-action
                      (if discord-channel
                        {:event/type :spring-lobby/promote-discord
                         :discord-channel discord-channel
                         :data {:battle-title (get-in battles [battle-id :battle-title])
                                :map-name map-name
                                :mod-name mod-name
                                :team-counts team-counts}
                         :server-key server-key}
                        {:event/type :spring-lobby/send-message
                         :channel-name channel-name
                         :client-data client-data
                         :message "!promote"
                         :server-key server-key})}])
                  (when am-host
                    [{:fx/type :h-box
                      :alignment :center-left
                      :children
                      [
                       {:fx/type :pane
                        :style {:-fx-pref-width 8}}
                       {:fx/type :check-box
                        :selected (boolean interleave-ally-player-ids)
                        :on-selected-changed {:event/type :spring-lobby/assoc
                                              :key :interleave-ally-player-ids}}
                       {:fx/type :label
                        :text " Interleave Player IDs "}]}]))]
    {:fx/type :flow-pane
     :style {:-fx-font-size 16}
     :orientation :horizontal
     :children
     (concat
       sync-buttons
       [{:fx/type :pane
         :pref-width 16}]
       buttons
       (when (or singleplayer (not am-spec))
         [{:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type fx/ext-let-refs
             :refs {::add-bot-button
                    {:fx/type :button
                     :text "Add AI"
                     :on-action {:event/type :spring-lobby/toggle
                                 :key :show-add-bot}}}
             :desc {:fx/type fx/ext-let-refs
                    :refs {::add-bot-popup {:fx/type ext-with-shown-on
                                            :props (when (fx/sub-val context :show-add-bot)
                                                     {:shown-on {:fx/type fx/ext-get-ref :ref ::add-bot-button}})
                                            :desc {:fx/type :tooltip
                                                   :anchor-location :window-bottom-left
                                                   :auto-hide true
                                                   :auto-fix true
                                                   :on-hidden {:event/type :spring-lobby/dissoc
                                                               :key :show-add-bot}
                                                   :graphic
                                                   {:fx/type :v-box
                                                    :style {:-fx-font-size 16}
                                                    :children
                                                    [
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
                                                       {:fx/type :label
                                                        :text " Version: "}
                                                       {:fx/type ext-recreate-on-key-changed
                                                        :key (str bot-name)
                                                        :desc
                                                        {:fx/type :combo-box
                                                         :value bot-version
                                                         :disable (string/blank? bot-name)
                                                         :on-value-changed {:event/type :spring-lobby/assoc
                                                                            :key :bot-version}
                                                         :items (or bot-versions [])}}]}
                                                     {:fx/type :h-box
                                                      :alignment :center-left
                                                      :children
                                                      [{:fx/type :label
                                                        :text " Name: "}
                                                       {:fx/type :text-field
                                                        :prompt-text "AI Name"
                                                        :text (str bot-username)
                                                        :on-text-changed {:event/type :spring-lobby/assoc
                                                                          :key :bot-username}}]}
                                                     {:fx/type :button
                                                      :text "Add"
                                                      :disable (or (string/blank? bot-username)
                                                                   (string/blank? bot-name)
                                                                   (string/blank? bot-version))
                                                      :on-action
                                                      {:event/type :spring-lobby/add-bot
                                                       :battle battle
                                                       :bot-username bot-username
                                                       :bot-name bot-name
                                                       :bot-version bot-version
                                                       :client-data client-data
                                                       :side-indices (keys sides)
                                                       :singleplayer singleplayer
                                                       :username username}}]}}}}
                    :desc {:fx/type fx/ext-get-ref :ref ::add-bot-button}}}]}])
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
                 :value (get-in scripttags ["game" "demofile"])
                 :on-value-changed {:event/type :spring-lobby/assoc-in
                                    :path [:by-server server-key :battle :scripttags "game" "demofile"]}
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
           (when (get-in scripttags ["game" "demofile"])
             [{:fx/type :button
               :on-action {:event/type :spring-lobby/dissoc-in
                           :path [:battle :scripttags "game" "demofile"]}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal (str "mdi-close:" font-icon-size ":white")}}]))}
        {:fx/type :pane
         :pref-width 16}]
       (when (and (not (:mode my-battle-status))
                  (not singleplayer))
         [{:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :check-box
             :selected (boolean auto-launch)
             :style {:-fx-padding "10px"}
             :on-selected-changed {:event/type :spring-lobby/assoc-in
                                   :path [:auto-launch server-key]}}
            {:fx/type :label
             :text "Auto Launch "}]}])
       (when-not singleplayer
         [{:fx/type :h-box
           :alignment :center-left
           :style {:-fx-font-size 16}
           :children
           [{:fx/type ext-recreate-on-key-changed
             :key (str [am-spec am-away])
             :desc
             {:fx/type :combo-box
              :value (if am-away "Away" "Here")
              :items ["Away" "Here"]
              :on-value-changed {:event/type :spring-lobby/on-change-away
                                 :client-data (when-not singleplayer client-data)
                                 :client-status (assoc my-client-status :away (not am-away))}}}]}])
       [{:fx/type :h-box
         :alignment :center-left
         :style {:-fx-font-size 16}
         :children
         [{:fx/type ext-recreate-on-key-changed
           :key (str [am-spec am-away])
           :desc
           {:fx/type :combo-box
            :value (if am-spec "Spectating" "Playing")
            :items ["Spectating" "Playing"]
            :on-value-changed {:event/type :spring-lobby/on-change-spectate
                               :client-data (when-not singleplayer client-data)
                               :is-me true
                               :is-bot false
                               :id my-player
                               :ready-on-unspec ready-on-unspec
                               :server-key server-key}}}]}
        {:fx/type :h-box
         :alignment :center-left
         :style {:-fx-font-size 24}
         :children
         (if-not am-spec
           [{:fx/type :check-box
             :selected (-> my-battle-status :ready boolean)
             :style {:-fx-padding "10px"}
             :on-selected-changed {:event/type :spring-lobby/battle-ready-change
                                   :client-data client-data
                                   :username username
                                   :battle-status my-battle-status
                                   :team-color my-team-color}}
            {:fx/type :label
             :text " Ready "}]
           [{:fx/type :check-box
             :selected (boolean auto-unspec)
             :style {:-fx-padding "10px"
                     :-fx-font-size 15}
             :on-selected-changed
             {:event/type :spring-lobby/auto-unspec
              :client-data (when-not singleplayer client-data)
              :is-me true
              :is-bot false
              :id my-player
              :ready-on-unspec ready-on-unspec
              :server-key server-key}}
            {:fx/type :label
             :style {:-fx-font-size 15}
             :text "Auto Unspec "}])}
        {:fx/type :h-box
         :alignment :center-left
         :style {:-fx-font-size 24}
         :children
         [
          {:fx/type fx.ext.node/with-tooltip-props
           :props
           {:tooltip
            {:fx/type tooltip-nofocus/lifecycle
             :show-delay skylobby.fx/tooltip-show-delay
             :style {:-fx-font-size 12}
             :text (cond
                     am-host "You are the host, start the game"
                     host-ingame "Join game in progress"
                     :else (str "Call vote to start the game"))}}
           :desc
           {:fx/type :button
            :text (cond
                    (and spring-running (not singleplayer))
                    "Game running"
                    (and am-spec (not host-ingame) (not singleplayer))
                    "Game not running"
                    :else
                    (str (if (and (not singleplayer) (or host-ingame am-spec))
                           "Join" "Start")
                         " Game"))
            :disable (boolean
                       (or spring-running
                           (and (not singleplayer)
                                (or (and (not host-ingame) am-spec)
                                    (not in-sync)))))
            :on-action
            (merge
              {:event/type :spring-lobby/start-battle}
              (fx/sub-ctx context sub/spring-resources spring-root)
              {:battle battle
               :battles battles
               :users users
               :username username}
              {:am-host am-host
               :am-spec am-spec
               :battle-map-details battle-map-details
               :battle-mod-details battle-mod-details
               :battle-status my-battle-status
               :channel-name channel-name
               :client-data client-data
               :host-ingame host-ingame
               :singleplayer singleplayer
               :spring-isolation-dir spring-root})}}]}
        {:fx/type :label
         :style {:-fx-font-size 24}
         :text (str " "
                    (when (< 1 (count team-counts))
                      (string/join "v" team-counts)))}]
       (when show-team-skills
         [{:fx/type :label
           :style {:-fx-font-size 16}
           :text (str " "
                      (when (< 1 (count team-skills))
                        (str
                          "("
                          (string/join ", " team-skills)
                          ")")))}]))}))


(defn battle-tabs
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        am-host (fx/sub-ctx context sub/am-host server-key)
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        {:keys [battle-id] :as battle} (fx/sub-val context get-in [:by-server server-key :battle])
        mod-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
        map-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map])
        spring-isolation-dir (fx/sub-ctx context sub/spring-root server-key)
        indexed-map (fx/sub-ctx context sub/indexed-map spring-isolation-dir map-name)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-isolation-dir mod-name)
        battle-map-details (fx/sub-ctx context skylobby.fx/map-details-sub indexed-map)
        battle-mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        file-cache (fx/sub-val context :file-cache)
        interleave-ally-player-ids (fx/sub-val context :interleave-ally-player-ids)
        scripttags (fx/sub-val context get-in [:by-server server-key :battle :scripttags])
        singleplayer (= :local server-key)
        spring-settings (fx/sub-val context :spring-settings)
        startpostype (fx/sub-ctx context sub/startpostype server-key)
        username (fx/sub-val context get-in [:by-server server-key :username])
        users (fx/sub-val context get-in [:by-server server-key :users])
        minimap-size (fx/sub-val context :minimap-size)
        minimap-size (or (u/to-number minimap-size)
                         fx.minimap/default-minimap-size)]
    {:fx/type :tab-pane
     :style {:-fx-min-width (+ minimap-size 20)
             :-fx-pref-width (+ minimap-size 20)
             :-fx-max-width (+ minimap-size 20)
             :-fx-pref-height (+ minimap-size 164)}
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
         [{:fx/type fx.minimap/minimap-pane
           :server-key server-key
           :minimap-type-key :minimap-type}
          {:fx/type :v-box
           :children
           [
            {:fx/type :label
             :text (str
                     (when-let [description (-> battle-map-details :mapinfo :description)]
                       description))}
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
               :value (fx/sub-val context :minimap-type)
               :items minimap-types
               :on-value-changed {:event/type :spring-lobby/assoc
                                  :key :minimap-type}}
              {:fx/type :label
               :text (str " Map size: "
                          (when-let [{:keys [map-width map-height]} (-> battle-map-details :smf :header)]
                            (str
                              (when map-width (quot map-width 64))
                              " x "
                              (when map-height (quot map-height 64)))))}]}
            (let [{:keys [battle-status]} (-> battle :users (get username))]
              {:fx/type maps-view
               :action-disable-rotate {:event/type :spring-lobby/send-message
                                       :channel-name channel-name
                                       :client-data client-data
                                       :message "!rotationEndGame off"
                                       :server-key server-key}
               :disable (and (not singleplayer) am-spec)
               :flow true
               :map-name map-name
               :spring-isolation-dir spring-isolation-dir
               :on-value-changed
               (cond
                 singleplayer
                 {:event/type :spring-lobby/assoc-in
                  :path [:by-server :local :battles :singleplayer :battle-map]}
                 am-host
                 {:event/type :spring-lobby/battle-map-change
                  :client-data client-data}
                 :else
                 {:event/type :spring-lobby/suggest-battle-map
                  :battle-status battle-status
                  :channel-name channel-name
                  :client-data client-data})})
            {:fx/type :flow-pane
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
                               :allyteam-ids (->> (get scripttags "game")
                                                  (filter (comp #(string/starts-with? % "allyteam") name first))
                                                  (map
                                                    (fn [[teamid _team]]
                                                      (let [[_all id] (re-find #"allyteam(\d+)" (name teamid))]
                                                        id))))
                               :client-data client-data
                               :server-key server-key}}]))}
            {:fx/type :label
             :text (str "")}
            {:fx/type :flow-pane
             ;:alignment :center-left
             :children
             (concat
               (when am-host
                 [{:fx/type :button
                   :text "FFA"
                   :on-action {:event/type :spring-lobby/battle-teams-ffa
                               :am-host am-host
                               :battle battle
                               :client-data (when-not singleplayer client-data)
                               :interleave-ally-player-ids interleave-ally-player-ids
                               :users users
                               :username username}}
                  {:fx/type :button
                   :text "2 teams"
                   :on-action {:event/type :spring-lobby/battle-teams-2
                               :am-host am-host
                               :battle battle
                               :client-data (when-not singleplayer client-data)
                               :interleave-ally-player-ids interleave-ally-player-ids
                               :users users
                               :username username}}
                  {:fx/type :button
                   :text "3 teams"
                   :on-action {:event/type :spring-lobby/battle-teams-3
                               :am-host am-host
                               :battle battle
                               :client-data (when-not singleplayer client-data)
                               :interleave-ally-player-ids interleave-ally-player-ids
                               :users users
                               :username username}}
                  {:fx/type :button
                   :text "4 teams"
                   :on-action {:event/type :spring-lobby/battle-teams-4
                               :am-host am-host
                               :battle battle
                               :client-data (when-not singleplayer client-data)
                               :interleave-ally-player-ids interleave-ally-player-ids
                               :users users
                               :username username}}
                  {:fx/type :button
                   :text "5 teams"
                   :on-action {:event/type :spring-lobby/battle-teams-5
                               :am-host am-host
                               :battle battle
                               :client-data (when-not singleplayer client-data)
                               :interleave-ally-player-ids interleave-ally-player-ids
                               :users users
                               :username username}}
                  {:fx/type :button
                   :text "Humans vs Bots"
                   :on-action {:event/type :spring-lobby/battle-teams-humans-vs-bots
                               :am-host am-host
                               :battle battle
                               :client-data (when-not singleplayer client-data)
                               :interleave-ally-player-ids interleave-ally-player-ids
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
          :modoptions (:modoptions battle-mod-details)
          :server-key server-key
          :singleplayer singleplayer}]}}
      {:fx/type :tab
       :graphic {:fx/type :label
                 :text "Spring settings"}
       :closable false
       :content
       (let [{:keys [auto-backup backup-name confirmed game-specific results]} spring-settings
             spring-settings-dir (fs/spring-settings-root)
             dest-dir (when-not (string/blank? backup-name)
                        (fs/file spring-settings-dir backup-name))]
         {:fx/type :v-box
          :children
          [
           {:fx/type :label
            :text " Auto Manage "
            :style {:-fx-font-size 20}}
           {:fx/type :pane
            :style {:-fx-min-height 20
                    :-fx-pref-height 20}}
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
            :min-height :use-pref-size
            :wrap-text true
            :text (str " If enabled, will copy these files into a backup folder in "
                       spring-settings-dir
                       " named 'backup-yyyyMMdd-HHmmss' before Spring is run.")}
           {:fx/type :pane
            :style {:-fx-margin-top 8
                    :-fx-margin-bottom 8}}
           {:fx/type :h-box
            :alignment :center-left
            :children
            [
             {:fx/type :check-box
              :selected (boolean game-specific)
              :on-selected-changed {:event/type :spring-lobby/assoc-in
                                    :path [:spring-settings :game-specific]}}
             {:fx/type :label
              :text " Game specific settings"}]}
           {:fx/type :label
            :min-height :use-pref-size
            :wrap-text true
            :text (str " If enabled, will copy these files from a game specific folder in "
                       spring-settings-dir
                       " before Spring is run, and save them there after Spring exits."
                       " May cause issues running multiple instances of Spring at once.")}
           {:fx/type :pane
            :style {:-fx-min-height 40
                    :-fx-pref-height 40}}
           {:fx/type :label
            :text " Manual Backup "
            :style {:-fx-font-size 20}}
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
           {:fx/type :pane
            :style {:-fx-min-height 40
                    :-fx-pref-height 40}}
           {:fx/type :label
            :text " Restore "
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
                      :key :show-uikeys-window}}]}}
      #_
      {:fx/type :tab
       :graphic {:fx/type :label
                 :text "script.txt"}
       :closable false
       :content
       {:fx/type :v-box
        :children
        [{:fx/type :text-area
          :v-box/vgrow :always
          :style {:-fx-font-family "Monospace"}
          :text (str (spring/battle-script-txt
                       (assoc
                         (fx/sub-val context get-in [:by-server server-key])
                         :battle-map-details battle-map-details
                         :battle-mod-details battle-mod-details)))}]}}]}))


(defn battle-votes-impl
  [{:fx/keys [context]
    :keys [battle-layout server-key]}]
  (let [show-vote-log (fx/sub-val context :show-vote-log)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        messages (fx/sub-val context get-in [:by-server server-key :channels channel-name :messages])
        host-username (fx/sub-ctx context sub/host-username server-key)
        host-messages (->> messages
                           (filter (comp #{host-username} :username)))
        host-ex-messages (->> host-messages
                              (filter (comp #{:ex} :message-type)))
        spads-messages (->> host-ex-messages
                            (filter :spads))
        vote-messages (->> spads-messages
                           (filter (comp #{:called-vote :cancelling-vote :game-starting-cancel :no-vote :vote-cancelled
                                           :vote-cancelled-game-launch :vote-failed :vote-passed :vote-progress}
                                         :spads-message-type
                                         :spads))
                           (map
                             (fn [{:keys [spads] :as message}]
                               (let [{:keys [spads-message-type spads-parsed]} spads]
                                 (assoc-in message [:spads :vote-data]
                                   (if (= :called-vote spads-message-type)
                                     {:command (nth spads-parsed 2)
                                      :caller (second spads-parsed)}
                                     {:command (second spads-parsed)}))))))
        current-vote (reduce
                       (fn [prev {:keys [spads] :as curr}]
                         (let [{:keys [spads-message-type spads-parsed]} spads]
                           (cond
                             (= :called-vote spads-message-type) curr
                             (= :vote-progress spads-message-type)
                             (let [[_all _command y yt n nt _ remaining] spads-parsed]
                               (assoc-in prev [:spads :vote-progress] {:y y
                                                                       :yt yt
                                                                       :n n
                                                                       :nt nt
                                                                       :remaining remaining}))
                             (#{:cancelling-vote :game-starting-cancel :no-vote :vote-cancelled
                                :vote-cancelled-game-launch :vote-failed :vote-passed}
                               spads-message-type) nil
                             :else prev)))
                       nil
                       (reverse vote-messages))]
    {:fx/type :v-box
     :children
     (concat
       (when-let [{:keys [spads]} current-vote]
         (let [{:keys [vote-data vote-progress]} spads
               {:keys [y yt n nt remaining]} vote-progress]
           [{:fx/type :v-box
             :style {:-fx-font-size 18}
             :children
             [{:fx/type :label
               :style {:-fx-font-size 24}
               :text "Current Vote"}
              {:fx/type :label
               :text (str " " (:command vote-data))}
              {:fx/type :label
               :text (str " by " (:caller vote-data))}
              {:fx/type :h-box
               :children
               (concat
                 [
                  {:fx/type :label
                   :text (str " "
                              (when vote-progress
                                (str "Y: " y " / " yt "  N: " n " / " nt)))}]
                 (when remaining (str remaining " left")
                   [{:fx/type :pane
                     :h-box/hgrow :always}
                    {:fx/type :label
                     :text (str remaining " left")}]))}
              {:fx/type :h-box
               :children
               [{:fx/type :button
                 :style (assoc (dissoc ok-severity :-fx-background-color)
                               :-fx-font-size 20)
                 :text "Yes"
                 :on-action {:event/type :spring-lobby/send-message
                             :channel-name channel-name
                             :client-data client-data
                             :message "!vote y"
                             :server-key server-key}}
                {:fx/type :pane
                 :h-box/hgrow :always}
                {:fx/type :button
                 :style (assoc (dissoc error-severity :-fx-background-color)
                               :-fx-font-size 20)
                 :text "No"
                 :on-action {:event/type :spring-lobby/send-message
                             :channel-name channel-name
                             :client-data client-data
                             :message "!vote n"
                             :server-key server-key}}
                {:fx/type :pane
                 :h-box/hgrow :always}
                {:fx/type :button
                 :text "Present"
                 :style {:-fx-font-size 20}
                 :on-action {:event/type :spring-lobby/send-message
                             :channel-name channel-name
                             :client-data client-data
                             :message "!vote b"
                             :server-key server-key}}]}]}]))
       [{:fx/type :h-box
         :children
         (concat
           (when show-vote-log
             [{:fx/type :label
               :text "Vote Log (newest to oldest)"
               :style {:-fx-font-size 24}}])
           [{:fx/type :pane
             :h-box/hgrow :always}
            {:fx/type :button
             :text ""
             :on-action {:event/type (if show-vote-log :spring-lobby/dissoc :spring-lobby/assoc)
                         :key :show-vote-log}
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal (if show-vote-log
                              (if (= "vertical" battle-layout)
                                "mdi-arrow-down:16:white"
                                "mdi-arrow-right:16:white")
                              (if (= "vertical" battle-layout)
                                "mdi-arrow-up:16:white"
                                "mdi-arrow-left:16:white"))}}])}]
       (when show-vote-log
         [{:fx/type :scroll-pane
           :pref-width 400
           :v-box/vgrow :always
           :content
           {:fx/type :v-box
            :style {:-fx-font-size 18}
            :children
            (->> vote-messages
                 (filter (comp #{:called-vote :cancelling-vote :game-starting-cancel :vote-cancelled :vote-failed :vote-passed} :spads-message-type :spads))
                 (map
                   (fn [{:keys [spads timestamp]}]
                     (let [{:keys [spads-message-type vote-data]} spads]
                       {:fx/type :h-box
                        :alignment :center-left
                        :children
                        (concat
                          [{:fx/type :label
                            :style {:-fx-font-family monospace-font-family}
                            :text (str (u/format-hours timestamp) " ")}
                           {:fx/type font-icon/lifecycle
                            :icon-literal (case spads-message-type
                                            :called-vote "mdi-phone:16:blue"
                                            :vote-passed "mdi-phone-incoming:16:green"
                                            :vote-failed "mdi-phone-missed:16:red"
                                            :cancelling-vote "mdi-phone-minus:16:gold"
                                            :vote-cancelled "mdi-phone-minus:16:gold"
                                            :vote-cancelled-game-launch "mdi-phone-minus:16:gold"
                                            :game-starting-cancel "mdi-phone-minus:16:gold"
                                            ; else
                                            "mdi-phone:16:white")}]
                          (when (= :called-vote spads-message-type)
                            [
                             {:fx/type :label
                              :style {:-fx-font-family monospace-font-family}
                              :text (str " " (:caller vote-data) ":")}])
                          [{:fx/type :label
                            :style {:-fx-font-family monospace-font-family}
                            :text (str " " (:command vote-data))}])}))))}}]))}))

(defn battle-votes
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battle-votes
      (battle-votes-impl state))))


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


(defn battle-view-impl
  [{:fx/keys [context]
    :keys [battle-id server-key]}]
  (let [
        battle-layout (fx/sub-val context :battle-layout)
        divider-positions (fx/sub-val context :divider-positions)
        pop-out-chat (fx/sub-val context :pop-out-chat)
        server-url (fx/sub-val context get-in [:by-server server-key :client-data :server-url])
        old-battle (fx/sub-val context get-in [:by-server server-key :old-battles battle-id])
        battle-id (or battle-id
                      (fx/sub-val context get-in [:by-server server-key :battle :battle-id]))
        scripttags (or (:scripttags old-battle)
                       (fx/sub-val context get-in [:by-server server-key :battle :scripttags]))
        engine-version (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-version])
        mod-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
        map-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map])
        spring-root (fx/sub-ctx context skylobby.fx/spring-root-sub server-url)
        {:keys [engines-by-version mods-by-name]} (fx/sub-ctx context sub/spring-resources spring-root)
        engine-details (get engines-by-version engine-version)
        mod-dependencies (->> mod-name
                              resource/mod-dependencies
                              (map (fn [mod-name]
                                     (let [indexed-mod (get mods-by-name mod-name)]
                                       {:mod-name mod-name
                                        :indexed indexed-mod
                                        :details indexed-mod}))))
        engine-file (:file engine-details)
        battle-users (or (:users old-battle)
                         (fx/sub-val context get-in [:by-server server-key :battle :users]))
        battle-bots (or (:bots old-battle)
                        (fx/sub-val context get-in [:by-server server-key :battle :bots]))
        players (battle-players-and-bots
                  {:users (fx/sub-val context get-in [:by-server server-key :users])
                   :battle
                   {:bots battle-bots
                    :users battle-users}})
        username (fx/sub-val context get-in [:by-server server-key :username])
        my-player (->> players
                       (filter (comp #{username} :username))
                       first)
        team-counts (->> players
                         (filter (comp :mode :battle-status))
                         (group-by (comp :ally :battle-status))
                         (sort-by first)
                         (map (comp count second)))
        team-skills (->> players
                         (filter (comp :mode :battle-status))
                         (group-by (comp :ally :battle-status))
                         (sort-by first)
                         (map (fn [[_ally players]]
                                (reduce
                                  (fnil + 0 0)
                                  0
                                  (mapv
                                    (fn [{:keys [username]}]
                                      (let [username-lc (when username (string/lower-case username))
                                            skill (some-> (get-in scripttags ["game" "players" username-lc "skill"])
                                                          u/parse-skill
                                                          u/round)]
                                        skill))
                                    players)))))
        players-table {:fx/type players-table
                       :server-key server-key
                       :v-box/vgrow :always
                       :players players}
        battle-layout (if (contains? (set battle-layouts) battle-layout)
                        battle-layout
                        (first battle-layouts))
        show-vote-log (fx/sub-val context :show-vote-log)
        battle-buttons (if-not old-battle
                         {:fx/type battle-buttons
                          :server-key server-key
                          :my-player my-player
                          :team-counts team-counts
                          :team-skills team-skills}
                         {:fx/type :pane})
        battle-chat {:fx/type :h-box
                     :children
                     (concat
                       [
                        {:fx/type fx.channel/channel-view
                         :h-box/hgrow :always
                         :channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key battle-id)
                         :disable old-battle
                         :hide-users true
                         :server-key server-key
                         :usernames (keys (or battle-users {}))}]
                       (when (not= "vertical" battle-layout)
                         [{:fx/type battle-votes
                           :battle-layout battle-layout
                           :server-key server-key}]))}
        battle-tabs
        (if (and (not= "vertical" battle-layout) (not pop-out-chat))
          {:fx/type battle-tabs
           :server-key server-key}
          (if show-vote-log
            {:fx/type :split-pane
             :orientation :vertical
             :divider-positions [0.5]
             :items
             [
              {:fx/type battle-tabs
               :server-key server-key}
              {:fx/type battle-votes
               :battle-layout battle-layout
               :server-key server-key}]}
            {:fx/type :v-box
             :children
             [
              {:fx/type battle-tabs
               :v-box/vgrow :always
               :server-key server-key}
              {:fx/type battle-votes
               :battle-layout battle-layout
               :server-key server-key}]}))
        resources-buttons {:fx/type :h-box
                           :alignment :center-left
                           :children
                           [
                            {:fx/type :label
                             :text " Resources: "}
                            {:fx/type :button
                             :text "Import"
                             :on-action {:event/type :spring-lobby/toggle
                                         :key :show-importer}
                             :graphic
                             {:fx/type font-icon/lifecycle
                              :icon-literal (str "mdi-file-import:16:white")}}
                            {:fx/type :button
                             :text "HTTP"
                             :on-action {:event/type :spring-lobby/toggle
                                         :key :show-downloader}
                             :graphic
                             {:fx/type font-icon/lifecycle
                              :icon-literal (str "mdi-download:16:white")}}
                            {:fx/type :button
                             :text "Rapid"
                             :on-action {:event/type :spring-lobby/toggle
                                         :key :show-rapid-downloader}
                             :graphic
                             {:fx/type font-icon/lifecycle
                              :icon-literal (str "mdi-download:16:white")}}]}
        singleplayer (= :local server-key)
        resources-pane (if singleplayer
                         {:fx/type :v-box
                          :children
                          [
                           {:fx/type engines-view
                            :engine-version engine-version
                            :on-value-changed {:event/type :spring-lobby/singleplayer-engine-changed}
                            :spring-isolation-dir spring-root}
                           (if (seq engine-details)
                             {:fx/type mods-view
                              :engine-file engine-file
                              :mod-name mod-name
                              :on-value-changed {:event/type :spring-lobby/singleplayer-mod-changed}
                              :spring-isolation-dir spring-root}
                             {:fx/type :label
                              :text " Game: Get an engine first"})
                           {:fx/type maps-view
                            :map-name map-name
                            :on-value-changed {:event/type :spring-lobby/singleplayer-map-changed}
                            :spring-isolation-dir spring-root}
                           resources-buttons]}
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
                                  :engine-version engine-version
                                  :spring-isolation-dir spring-root
                                  :suggest true
                                  :on-value-changed {:event/type :spring-lobby/assoc-in
                                                     :path [:by-server :local :battles :singleplayer :battle-version]}}]}]
                              (if (seq engine-details)
                                [{:fx/type mods-view
                                  :spring-isolation-dir spring-root
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
                                  :spring-isolation-dir spring-root
                                  :suggest true
                                  :on-value-changed {:event/type :spring-lobby/assoc-in
                                                     :path [:by-server :local :battles :singleplayer :battle-map]}}]}])
                            (concat
                              [
                               {:fx/type engine-sync-pane
                                :engine-version engine-version
                                :spring-isolation-dir spring-root}
                               {:fx/type mod-sync-pane
                                :engine-version engine-version
                                :mod-name mod-name
                                :spring-isolation-dir spring-root}]
                              (map
                                (fn [{:keys [mod-name]}]
                                  {:fx/type mod-sync-pane
                                   :dependency true
                                   :engine-version engine-version
                                   :mod-name mod-name
                                   :spring-isolation-dir spring-root})
                                mod-dependencies)
                              [{:fx/type map-sync-pane
                                :map-name map-name
                                :spring-isolation-dir spring-root}
                               resources-buttons
                               {:fx/type :h-box
                                :alignment :center-left
                                :children
                                [{:fx/type :check-box
                                  :selected (boolean (fx/sub-val context :auto-get-resources))
                                  :on-selected-changed {:event/type :spring-lobby/assoc
                                                        :key :auto-get-resources}}
                                 {:fx/type :label
                                  :text " Auto import or download resources"}]}
                               {:fx/type :h-box
                                :alignment :center-left
                                :children
                                [{:fx/type :label
                                  :text "Force sync check: "}
                                 {:fx/type sync-button
                                  :server-key server-key}]}]))})
        resources-pane (if-not old-battle
                         resources-pane
                         {:fx/type :pane})]
    {:fx/type :v-box
     :children
     [
      {:fx/type :h-box
       :alignment :center-left
       :style {:-fx-font-size 16}
       :children
       (concat
         (when battle-id
           (concat
             [{:fx/type :button
               :text "Leave Battle"
               :on-action {:event/type :spring-lobby/leave-battle
                           :client-data (fx/sub-val context get-in [:by-server server-key :client-data])
                           :server-key server-key}}
              {:fx/type :pane
               :h-box/margin 4}]
             (when-not singleplayer
               [(if (fx/sub-val context :pop-out-battle)
                  {:fx/type :button
                   :text "Pop In Battle "
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-window-maximize:16:white"}
                   :on-action {:event/type :spring-lobby/dissoc
                               :key :pop-out-battle}}
                  {:fx/type :button
                   :text "Pop Out Battle "
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-open-in-new:16:white"}
                   :on-action {:event/type :spring-lobby/assoc
                               :key :pop-out-battle
                               :value true}})])
             [{:fx/type :pane
               :h-box/margin 4}]
             (when-not singleplayer
               [(if pop-out-chat
                  {:fx/type :button
                   :text "Pop In Chat "
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-window-maximize:16:white"}
                   :on-action {:event/type :spring-lobby/dissoc
                               :key :pop-out-chat}}
                  {:fx/type :button
                   :text "Pop Out Chat "
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-open-in-new:16:white"}
                   :on-action {:event/type :spring-lobby/assoc
                               :key :pop-out-chat
                               :value true}})]))))}
      {:fx/type :h-box
       :v-box/vgrow :always
       :style {:-fx-font-size 15}
       :alignment :top-left
       :children
       (if singleplayer
         [
          {:fx/type :v-box
           :h-box/hgrow :always
           :children
           [players-table
            resources-pane
            battle-buttons]}
          battle-tabs]
         (case battle-layout
           "vertical"
           [
            {:fx/type fx/ext-on-instance-lifecycle
             :on-created (fn [^javafx.scene.control.SplitPane node]
                           (let [dividers (.getDividers node)
                                 ^javafx.scene.control.SplitPane$Divider divider (first dividers)
                                 position-property (.positionProperty divider)]
                             (.addListener position-property
                               (reify javafx.beans.value.ChangeListener
                                 (changed [this _observable _old-value new-value]
                                   (swap! skylobby.fx/divider-positions assoc :battle-vertical new-value))))))
             :h-box/hgrow :always
             :desc
             {:fx/type :split-pane
              :divider-positions [(or (:battle-vertical divider-positions) 0.35)]
              :items
              (concat
                [
                 {:fx/type :v-box
                  :children
                  (concat
                    [(assoc players-table :v-box/vgrow :always)]
                    (when (fx/sub-val context :battle-resource-details)
                      [resources-pane])
                    [battle-buttons])}]
                (when-not pop-out-chat
                  [battle-chat]))}}
            battle-tabs]
           ; else
           [
            {:fx/type fx/ext-on-instance-lifecycle
             :on-created (fn [^javafx.scene.control.SplitPane node]
                           (let [dividers (.getDividers node)]
                             (when-let [^javafx.scene.control.SplitPane$Divider divider (first dividers)]
                               (let [position-property (.positionProperty divider)]
                                 (.addListener position-property
                                   (reify javafx.beans.value.ChangeListener
                                     (changed [this _observable _old-value new-value]
                                       (swap! skylobby.fx/divider-positions assoc :battle-horizontal new-value))))))))
             :h-box/hgrow :always
             :desc
             {:fx/type :split-pane
              :orientation :vertical
              :divider-positions [(or (:battle-horizontal divider-positions) 0.6)]
              :items
              (concat
                [{:fx/type :h-box
                  :children
                  [
                   {:fx/type :v-box
                    :h-box/hgrow :always
                    :children
                    [{:fx/type :h-box
                      :v-box/vgrow :always
                      :children
                      (concat
                        [(assoc players-table :h-box/hgrow :always)]
                        (when (fx/sub-val context :battle-resource-details)
                          [resources-pane]))}
                     battle-buttons]}
                   battle-tabs]}]
                (when-not pop-out-chat
                  [battle-chat]))}}]))}]}))

(defn battle-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battle-view
      (battle-view-impl state))))


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
