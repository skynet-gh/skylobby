(ns skylobby.fx.replay
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [clojure.java.io :as io]
    [clojure.string :as string]
    clojure.set
    [cljfx.api :as fx]
    java-time
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.battle :refer [minimap-types]]
    [skylobby.fx.color :as fx.color]
    [skylobby.fx.download :refer [springfiles-maps-download-source]]
    [skylobby.fx.engine-sync :refer [engine-sync-pane]]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-table-column-auto-size]]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.map-sync :refer [map-sync-pane]]
    [skylobby.fx.minimap :as fx.minimap]
    [skylobby.fx.mod-sync :refer [mod-sync-pane]]
    [skylobby.fx.players-table :as fx.players-table]
    [skylobby.fx.rich-text :as fx.rich-text]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.fx.virtualized-scroll-pane :as fx.virtualized-scroll-pane]
    [skylobby.http :as http]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [spring-lobby.spring :as spring]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (java.time LocalDateTime)
    (java.util TimeZone)
    (javafx.scene.paint Color)
    (org.fxmisc.richtext.model ReadOnlyStyledDocumentBuilder SegmentOps StyledSegment)))


(set! *warn-on-reflection* true)


(def replay-window-width 1600)
(def replay-window-height 900)


(def replays-window-width 2400)
(def replays-window-height 1200)


(defn replay-sources [{:keys [extra-replay-sources]}]
  (concat
    (fs/builtin-replay-sources)
    extra-replay-sources))


(defn- min-skill [coll]
  (when (seq coll)
    (reduce min Long/MAX_VALUE coll)))

(defn- max-skill [coll]
  (when (seq coll)
    (reduce max 0 coll)))


; https://github.com/Jazcash/sdfz-demo-parser/blob/a4391f14ee4bc08aedb5434d66cf99ad94913597/src/demo-parser.ts#L233
(defn- format-chat-dest [dest-type]
  (when (and (not= :global dest-type)
             (not= :self dest-type))
    (str " ("
         (if (= :ally dest-type)
           "a"
           "s")
         ")")))

(defn- chat-dest-color [dest-type]
  (case dest-type
    :ally "green"
    :spec "yellow"
    :global "white"
    "grey"))


(defn segment
  [text style]
  (StyledSegment.
    (or text "")
    (or style "")))

(defn chat-log-document [chat-log {:keys [player-name-to-color player-num-to-name]}]
  (let [
        builder (ReadOnlyStyledDocumentBuilder. (SegmentOps/styledTextOps) "")
        chat-log (remove
                   (comp #(string/starts-with? % "SPRINGIE:") :message)
                   chat-log)]
    (doseq [chat chat-log]
      (let [{:keys [from dest message]} chat
            player (get player-num-to-name from)
            color (get player-name-to-color player)
            javafx-color (if color
                           (fx.color/spring-color-to-javafx color)
                           Color/YELLOW)
            css-color (some-> javafx-color str u/hex-color-to-css)
            is-spec (and (not (string/blank? player)) (not color))
            dest-type (case (int dest)
                        252 :ally
                        253 :spec
                        254 :global
                        :self)
            dest-type (if (and is-spec (= :ally dest-type)) :spec dest-type)]
        (.addParagraph builder
          ^java.util.List
          (vec
            (concat
              [
               (segment
                 (str
                   (when is-spec
                     "(s) ")
                   player)
                 (str "-fx-fill: " css-color ";"))
               (segment
                 (format-chat-dest dest-type)
                 (str "-fx-fill: " (chat-dest-color dest-type) ";"))
               (segment
                 (str
                   (if (string/blank? player) "*" ":")
                   " "
                   message)
                 (str "-fx-fill: "
                      (if (string/blank? player)
                        "cyan"
                        (if (= :spec dest-type)
                          "yellow"
                          "white"))
                      ";"))]))
          "")))
    (when (seq chat-log)
      (.build builder))))


(defn replay-view
  [{:fx/keys [context] :keys [show-sync show-sync-left]}]
  (let [spring-isolation-dir (fx/sub-val context :spring-isolation-dir)
        spring-running (fx/sub-val context get-in [:spring-running :replay :replay])
        replay-minimap-type (fx/sub-val context :replay-minimap-type)
        {:keys [engines engines-by-version maps-by-name mods-by-name]} (fx/sub-ctx context skylobby.fx/spring-resources-sub spring-isolation-dir)
        selected-replay (fx/sub-ctx context skylobby.fx/selected-replay-sub)
        full-replay-details (fx/sub-ctx context skylobby.fx/replay-details-sub (fs/canonical-path (:file selected-replay)))
        selected-replay-details (or full-replay-details selected-replay)
        script-data (-> selected-replay-details :body :script-data)
        game (:game script-data)
        selected-engine-version (:replay-engine-version selected-replay)
        selected-matching-engine (get engines-by-version selected-engine-version)
        mod-name (:replay-mod-name selected-replay)
        selected-matching-mod (get mods-by-name mod-name)
        map-name (:replay-map-name selected-replay)
        selected-matching-map (get maps-by-name map-name)
        teams-by-id (->> game
                         (filter (comp #(string/starts-with? % "team") name first))
                         (mapv
                           (fn [[teamid team]]
                             (let [[_all id] (re-find #"team(\d+)" (name teamid))]
                               [id team])))
                         (into {}))
        indexed-mod (get mods-by-name mod-name)
        replay-mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        sides (spring/mod-sides replay-mod-details)
        players (->> game
                     (filter (comp #(string/starts-with? % "player") name first))
                     (mapv
                       (fn [[playerid {:keys [spectator team] :as player}]]
                         (let [[_all id] (re-find #"player(\d+)" (name playerid))
                               {:keys [allyteam handicap rgbcolor side] :as team} (get teams-by-id (str team))
                               team-color (try (u/spring-script-color-to-int rgbcolor)
                                               (catch Exception e
                                                 (log/debug e "Error parsing color")
                                                 0))
                               side-id-by-name (clojure.set/map-invert sides)]
                           (-> player
                               (clojure.set/rename-keys
                                 {:name :username
                                  :countrycode :country})
                               (assoc :battle-status
                                      {:id id
                                       :team team
                                       :mode (not (u/to-bool spectator))
                                       :handicap handicap
                                       :side (get side-id-by-name side)
                                       :ally allyteam}
                                      :team-color team-color))))))
        bots (->> game
                  (filter (comp #(string/starts-with? % "ai") name first))
                  (mapv
                    (fn [[aiid {:keys [team] :as ai}]]
                      (let [[_all id] (re-find #"ai(\d+)" (name aiid))
                            {:keys [allyteam handicap rgbcolor side] :as team} (get teams-by-id (str team))
                            team-color (try (u/spring-script-color-to-int rgbcolor)
                                            (catch Exception e
                                              (log/debug e "Error parsing color")
                                              0))
                            side-id-by-name (clojure.set/map-invert sides)]
                        (-> ai
                            (clojure.set/rename-keys
                              {:name :username})
                            (assoc :battle-status
                                   {:id id
                                    :team team
                                    :mode true
                                    :handicap handicap
                                    :side (get side-id-by-name side)
                                    :ally allyteam}
                                   :team-color team-color))))))
        players-and-bots (concat players bots)
        player-name-to-color (->> players-and-bots
                                  (filter :username)
                                  (filter :team-color)
                                  (filter (comp :mode :battle-status))
                                  (map (juxt (comp string/trim str :username) :team-color))
                                  (into {}))
        players-and-bots (or (seq players-and-bots)
                             (concat
                               (mapcat
                                 (fn [[i players]]
                                   (map
                                     (fn [player]
                                       {:username player
                                        :battle-status
                                        {:mode true
                                         :ally i}})
                                     players))
                                 (map-indexed vector (:replay-allyteam-player-names selected-replay)))
                               (map (fn [spec] {:username spec :battle-status {:mode false}})
                                    (:replay-spec-names selected-replay))))
        indexed-map (get maps-by-name map-name)
        replay-map-details (fx/sub-ctx context skylobby.fx/map-details-sub indexed-map)
        replay-id-downloads (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/download-bar-replay)
                                 (map :id)
                                 set)
        sync-pane
        {:fx/type :v-box
         :children
         [
          {:fx/type :flow-pane
           :vgap 5
           :hgap 5
           :padding 5
           :children
           [(if-let [id (:id selected-replay)]
              (let [in-progress (contains? replay-id-downloads id)]
                {:fx/type :button
                 :style {:-fx-font-size 20}
                 :text (if in-progress
                         "Downloading..."
                         "Download replay")
                 :disable (boolean in-progress)
                 :on-action {:event/type :spring-lobby/add-task
                             :task {:spring-lobby/task-type :spring-lobby/download-bar-replay
                                    :id id
                                    :spring-isolation-dir spring-isolation-dir}}})
              {:fx/type engine-sync-pane
               :engine-version selected-engine-version
               :spring-isolation-dir spring-isolation-dir})
            {:fx/type mod-sync-pane
             :engine-version selected-engine-version
             :mod-name mod-name
             :spring-isolation-dir spring-isolation-dir}
            {:fx/type map-sync-pane
             :map-name map-name
             :spring-isolation-dir spring-isolation-dir}]}
          {:fx/type :button
           :text " Refresh "
           :on-action {:event/type :spring-lobby/clear-map-and-mod-details
                       :map-resource (fx/sub-ctx context sub/indexed-map spring-isolation-dir map-name)
                       :mod-resource (fx/sub-ctx context sub/indexed-mod spring-isolation-dir mod-name)}}]}]
    {:fx/type :h-box
     :style {:-fx-font-size 16}
     :alignment :center-left
     :children
     [{:fx/type :split-pane
       :h-box/hgrow :always
       :divider-positions [0.60]
       :items
       [
        {:fx/type :v-box
         :children
         (concat
           [{:fx/type ext-recreate-on-key-changed
             :v-box/vgrow :always
             :key (str (not= selected-replay selected-replay-details))
             :desc
             {:fx/type fx.players-table/players-table
              :am-host false
              :mod-name mod-name
              :players players-and-bots
              :sides sides
              :singleplayer true}}
            {:fx/type :h-box
             :alignment :center-left
             :children
             [
              {:fx/type :label
               :text " Color player name: "}
              {:fx/type :combo-box
               :value (or (fx/sub-val context :battle-players-color-type)
                          (first u/player-name-color-types))
               :items u/player-name-color-types
               :on-value-changed {:event/type :spring-lobby/assoc
                                  :key :battle-players-color-type}}]}]
           (when (and selected-matching-engine selected-matching-mod selected-matching-map)
             (let [watch-button {:fx/type :button
                                 :style {:-fx-font-size 24}
                                 :text (if spring-running
                                         " Watching a replay"
                                         " Watch")
                                 :disable (boolean spring-running)
                                 :on-action
                                 {:event/type :spring-lobby/watch-replay
                                  :engines engines
                                  :engine-version selected-engine-version
                                  :replay selected-replay
                                  :spring-isolation-dir spring-isolation-dir}
                                 :graphic
                                 {:fx/type font-icon/lifecycle
                                  :icon-literal "mdi-movie:24:white"}}]
               [{:fx/type :h-box
                 :children
                 [watch-button
                  {:fx/type :pane
                   :h-box/hgrow :always}
                  watch-button]}])))}
        {:fx/type :v-box
         :children
         (concat
           (when show-sync-left
             [sync-pane])
           (if full-replay-details
             (let [{:keys [chat-log player-num-to-name]} (-> full-replay-details :body :demo-stream)]
               [{:fx/type :label
                 :text "Chat log"
                 :style {:-fx-font-size 20}}
                {:fx/type fx.virtualized-scroll-pane/lifecycle
                 :v-box/vgrow :always
                 :content
                 {:fx/type fx.rich-text/lifecycle-inline
                  :editable false
                  :style {:-fx-font-family skylobby.fx/monospace-font-family
                          :-fx-font-size 18}
                  :wrap-text true
                  :document (chat-log-document
                              chat-log
                              {:player-name-to-color player-name-to-color
                               :player-num-to-name player-num-to-name})}}])
             [{:fx/type :label
               :text "Loading replay stream..."}]))}]}
      {:fx/type :v-box
       :children
       (concat
         [
          {:fx/type fx.minimap/minimap-pane
           :map-name map-name
           :server-key :local
           :minimap-type-key :replay-minimap-type
           :players players-and-bots
           :scripttags script-data}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :text (str " Size: "
                        (when-let [{:keys [map-width map-height]} (-> replay-map-details :smf :header)]
                          (str
                            (when map-width (quot map-width 64))
                            " x "
                            (when map-height (quot map-height 64)))))}
            {:fx/type :pane
             :h-box/hgrow :always}
            {:fx/type :combo-box
             :value replay-minimap-type
             :items minimap-types
             :on-value-changed {:event/type :spring-lobby/assoc
                                :key :replay-minimap-type}}]}]
         (when show-sync
           [sync-pane]))}]}))


(def time-zone-id
  (.toZoneId (TimeZone/getDefault)))


(defn replays-table
  [{:fx/keys [context]}]
  (let [replay-downloads-by-engine (fx/sub-val context :replay-downloads-by-engine)
        replay-downloads-by-map (fx/sub-val context :replay-downloads-by-map)
        replay-downloads-by-mod (fx/sub-val context :replay-downloads-by-mod)
        replay-imports-by-map (fx/sub-val context :replay-imports-by-map)
        replay-imports-by-mod (fx/sub-val context :replay-imports-by-mod)
        spring-running (fx/sub-val context get-in [:spring-running :replay :replay])
        selected-replay (fx/sub-ctx context skylobby.fx/selected-replay-sub)
        replays (fx/sub-val context :filtered-replays)
        sorted-replays (->> replays
                            (sort-by :replay-unix-time-str)
                            reverse
                            doall)
        spring-isolation-dir (fx/sub-val context :spring-isolation-dir)
        {:keys [engines engines-by-version maps-by-name mods-by-name]} (fx/sub-ctx context skylobby.fx/spring-resources-sub spring-isolation-dir)
        copying (fx/sub-val context :copying)
        extracting (fx/sub-val context :extracting)
        file-cache (fx/sub-val context :file-cache)
        http-download (fx/sub-val context :http-download)
        spring-root-path (fs/canonical-path spring-isolation-dir)
        rapid-data-by-version (fx/sub-val context get-in [:rapid-by-spring-root spring-root-path :rapid-data-by-version])
        rapid-download (fx/sub-val context :rapid-download)
        replays-tags (fx/sub-val context :replays-tags)
        replays-watched (fx/sub-val context :replays-watched)
        replays-window-details (fx/sub-val context :replays-window-details)
        tasks-by-type (fx/sub-ctx context skylobby.fx/tasks-by-type-sub)
        map-update-tasks (->> tasks-by-type
                              (filter (comp #{:spring-lobby/refresh-maps} first))
                              (mapcat second)
                              seq)
        mod-update-tasks (->> tasks-by-type
                              (filter (comp #{:spring-lobby/refresh-mods} first))
                              (mapcat second)
                              seq)
        replay-id-downloads (->> (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/download-bar-replay)
                                 (map :id)
                                 set)
        engine-update-tasks (->> (get tasks-by-type :spring-lobby/refresh-engines)
                                 seq)
        extract-tasks (->> (get tasks-by-type :spring-lobby/extract-7z)
                           (map (comp fs/canonical-path :file))
                           set)
        http-download-tasks (->> (get tasks-by-type :spring-lobby/http-downloadable)
                                 (map (comp :download-url :downloadable))
                                 set)
        import-tasks (->> (get tasks-by-type :spring-lobby/import)
                          (map (comp fs/canonical-path :resource-file :importable))
                          set)
        rapid-tasks (->> (get tasks-by-type :spring-lobby/rapid-download)
                         (map :rapid-id)
                         set)
        rapid-update-tasks (->> (get tasks-by-type :spring-lobby/update-rapid)
                                seq)]
    {:fx/type fx.ext.table-view/with-selection-props
     :v-box/vgrow :always
     :props {:selection-mode :single
             :on-selected-item-changed {:event/type :spring-lobby/select-replay}
             :selected-item selected-replay}
     :desc
     {:fx/type ext-recreate-on-key-changed
      :key replays-window-details
      :desc
      {:fx/type ext-table-column-auto-size
       :items sorted-replays
       :desc
       {:fx/type :table-view
        :column-resize-policy :constrained
        :columns
        (concat
          (when replays-window-details
            [
             #_
             {:fx/type :table-column
              :text "ID"
              :resizable true
              :pref-width 80
              :cell-value-factory :replay-id
              :cell-factory
              {:fx/cell-type :table-cell
               :describe
               (fn [id]
                 {:text (str id)})}}
             {:fx/type :table-column
              :text "Source"
              :resizable true
              :pref-width 80
              :cell-value-factory :source-name
              :cell-factory
              {:fx/cell-type :table-cell
               :describe
               (fn [source]
                 {:text (str source)})}}
             {:fx/type :table-column
              :text "Filename"
              :resizable true
              :pref-width 450
              :cell-value-factory #(-> % :file fs/filename)
              :cell-factory
              {:fx/cell-type :table-cell
               :describe
               (fn [filename]
                 {:text (str filename)})}}])
          [
           {:fx/type :table-column
            :text "Map"
            :resizable true
            :pref-width 200
            :cell-value-factory :replay-map-name
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [map-name]
               {:text (str map-name)})}}
           {:fx/type :table-column
            :text "Game"
            :resizable true
            :pref-width 300
            :cell-value-factory :replay-mod-name
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [mod-name]
               {:text (str mod-name)})}}
           {:fx/type :table-column
            :text "Timestamp"
            :resizable false
            :pref-width 160
            :cell-value-factory :replay-timestamp
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [unix-time]
               (let [ts (when unix-time
                          (java-time/format
                            (LocalDateTime/ofInstant
                              (java-time/instant unix-time)
                              time-zone-id)))]
                 {:text (str ts)}))}}
           {:fx/type :table-column
            :text "Type"
            :resizable false
            :pref-width 56
            :cell-value-factory #(some-> % :game-type name)
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [game-type]
               {:text (str game-type)})}}
           {:fx/type :table-column
            :text "Player Counts"
            :resizable true
            :pref-width 120
            :cell-value-factory :player-counts
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [player-counts]
               {:text (->> player-counts (string/join "v"))})}}
           {:fx/type :table-column
            :text "Skill Min"
            :resizable false
            :pref-width 80
            :cell-value-factory (comp min-skill :replay-skills)
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [min-skill]
               {:text (str min-skill)})}}
           {:fx/type :table-column
            :text "Skill Avg"
            :resizable false
            :pref-width 80
            :cell-value-factory :replay-average-skill
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [avg-skill]
               {:text (str avg-skill)})}}
           {:fx/type :table-column
            :text "Skill Max"
            :resizable false
            :pref-width 80
            :cell-value-factory (comp max-skill :replay-skills)
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [max-skill]
               {:text (str max-skill)})}}]
          (when replays-window-details
            [{:fx/type :table-column
              :text "Engine"
              :resizable true
              :pref-width 220
              :cell-value-factory :replay-engine-version
              :cell-factory
              {:fx/cell-type :table-cell
               :describe
               (fn [engine-version]
                 {:text (str engine-version)})}}
             {:fx/type :table-column
              :text "Size"
              :resizable false
              :pref-width 80
              :cell-value-factory :file-size
              :cell-factory
              {:fx/cell-type :table-cell
               :describe
               (fn [file-size]
                 {:text (u/format-bytes file-size)})}}])
          [{:fx/type :table-column
            :text "Duration"
            :resizable false
            :pref-width 80
            :cell-value-factory :replay-game-time
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [game-time]
               (let [duration (when game-time (java-time/duration game-time :seconds))
                     ; https://stackoverflow.com/a/44343699/984393
                     formatted (u/format-duration duration)]
                 {:text (str formatted)}))}}
           {:fx/type :table-column
            :text "Tag"
            :sortable false
            :resizable false
            :pref-width 140
            :cell-value-factory identity
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [replay]
               (let [id (:replay-id replay)]
                 {:text ""
                  :graphic
                  {:fx/type ext-recreate-on-key-changed
                   :key (str id)
                   :desc
                   {:fx/type :text-field
                    :text (str (get replays-tags id))
                    :on-text-changed {:event/type :spring-lobby/assoc-in
                                      :path [:replays-tags id]}}}}))}}
           {:fx/type :table-column
            :text "Watched"
            :sortable false
            :resizable false
            :pref-width 80
            :cell-value-factory identity
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [{:keys [file]}]
               (let [path (fs/canonical-path file)]
                 {:text ""
                  :graphic
                  {:fx/type ext-recreate-on-key-changed
                   :key (str path)
                   :desc
                   {:fx/type :check-box
                    :selected (boolean (get replays-watched path))
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:replays-watched path]}}}}))}}
           {:fx/type :table-column
            :text "Actions"
            :sortable false
            :resizable true
            :min-width 200
            :pref-width 200
            :cell-value-factory identity
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [i]
               {:text ""
                :graphic
                {:fx/type :h-box
                 :children
                 (concat
                   (when-let [file (:file i)]
                     [{:fx/type :button
                       :on-action {:event/type :spring-lobby/desktop-browse-dir
                                   :file file}
                       :graphic
                       {:fx/type font-icon/lifecycle
                        :icon-literal "mdi-folder:16:white"}}])
                   (when-let [id (:replay-id i)]
                     (let [mod-version (:replay-mod-name i)]
                       (when (and (not (string/blank? mod-version))
                                  (string/starts-with? mod-version "Beyond All Reason"))
                         [{:fx/type :button
                           :on-action {:event/type :spring-lobby/desktop-browse-url
                                       :url (str "https://bar-rts.com/replays/" id)}
                           :graphic
                           {:fx/type font-icon/lifecycle
                            :icon-literal "mdi-web:16:white"}}])))
                   [{:fx/type :pane
                     :h-box/hgrow :always}
                    (let [engine-version (:replay-engine-version i)
                          matching-engine (get engines-by-version engine-version)
                          engine-downloadable (get replay-downloads-by-engine engine-version)
                          mod-version (:replay-mod-name i)
                          matching-mod (get mods-by-name mod-version)
                          mod-downloadable (get replay-downloads-by-mod mod-version)
                          mod-importable (get replay-imports-by-mod mod-version)
                          mod-rapid (get rapid-data-by-version mod-version)
                          map-name (:replay-map-name i)
                          matching-map (get maps-by-name map-name)
                          map-downloadable (get replay-downloads-by-map map-name)
                          map-importable (get replay-imports-by-map map-name)
                          mod-rapid-download (get rapid-download (:id mod-rapid))]
                      (cond
                        (:id i) ; BAR online replay
                        (let [fileName (:fileName i)
                              download-url (when fileName (http/bar-replay-download-url fileName))
                              {:keys [running] :as download} (get http-download download-url)
                              in-progress (or running
                                              (contains? replay-id-downloads (:id i)))]
                          {:fx/type :button
                           :text
                           (if in-progress
                             (str (u/download-progress download))
                             " Download replay")
                           :disable (boolean in-progress)
                           :on-action {:event/type :spring-lobby/add-task
                                       :task {:spring-lobby/task-type :spring-lobby/download-bar-replay
                                              :id (:id i)
                                              :spring-isolation-dir spring-isolation-dir}}
                           :graphic
                           {:fx/type font-icon/lifecycle
                            :icon-literal "mdi-download:16:white"}})
                        (and matching-engine matching-mod matching-map)
                        {:fx/type :button
                         :text (if spring-running
                                 " Watching a replay"
                                 " Watch")
                         :disable (boolean spring-running)
                         :on-action
                         {:event/type :spring-lobby/watch-replay
                          :engines engines
                          :engine-version engine-version
                          :replay i
                          :spring-isolation-dir spring-isolation-dir}
                         :graphic
                         {:fx/type font-icon/lifecycle
                          :icon-literal "mdi-movie:16:white"}}
                        (and (not matching-engine) engine-update-tasks)
                        {:fx/type :button
                         :text " Engines updating..."
                         :disable true}
                        (and (not matching-engine) engine-downloadable)
                        (let [source (resource/resource-dest spring-isolation-dir engine-downloadable)]
                          (if (fs/file-exists? file-cache source)
                            (let [dest (io/file spring-isolation-dir "engine"
                                                (fs/without-extension
                                                  (:resource-filename engine-downloadable)))
                                  in-progress (boolean
                                                (or (get extracting (fs/canonical-path source))
                                                    (contains? extract-tasks (fs/canonical-path source))))]
                              {:fx/type :button
                               :text (if in-progress "Extracting..." " Extract engine")
                               :disable in-progress
                               :graphic
                               {:fx/type font-icon/lifecycle
                                :icon-literal "mdi-archive:16:white"}
                               :on-action
                               {:event/type :spring-lobby/add-task
                                :task
                                {:spring-lobby/task-type :spring-lobby/extract-7z
                                 :file source
                                 :dest dest}}})
                            (let [{:keys [download-url]} engine-downloadable
                                  {:keys [running] :as download} (get http-download download-url)
                                  in-progress (or running
                                                  (contains? http-download-tasks download-url))]
                              {:fx/type :button
                               :text (if in-progress
                                       (str (u/download-progress download))
                                       " Download engine")
                               :disable (boolean in-progress)
                               :on-action {:event/type :spring-lobby/add-task
                                           :task {:spring-lobby/task-type :spring-lobby/download-and-extract
                                                  :downloadable engine-downloadable
                                                  :spring-isolation-dir spring-isolation-dir}}
                               :graphic
                               {:fx/type font-icon/lifecycle
                                :icon-literal "mdi-download:16:white"}})))
                        (and (not matching-mod) mod-update-tasks)
                        {:fx/type :button
                         :text " Games updating..."
                         :disable true}
                        (and (not matching-mod) mod-importable)
                        (let [{:keys [resource-file]} mod-importable
                              resource-path (fs/canonical-path resource-file)
                              in-progress (boolean
                                            (or (-> copying (get resource-path) :status boolean)
                                                (contains? import-tasks resource-path)))]
                          {:fx/type :button
                           :text (if in-progress
                                   " Importing..."
                                   " Import game")
                           :disable in-progress
                           :on-action {:event/type :spring-lobby/add-task
                                       :task
                                       {:spring-lobby/task-type :spring-lobby/import
                                        :importable mod-importable
                                        :spring-isolation-dir spring-isolation-dir}}
                           :graphic
                           {:fx/type font-icon/lifecycle
                            :icon-literal "mdi-content-copy:16:white"}})
                        (and (not matching-mod) mod-rapid matching-engine)
                        (let [in-progress (contains? rapid-tasks (:id mod-rapid))]
                          {:fx/type :button
                           :text (if in-progress
                                   (u/download-progress mod-rapid-download)
                                   (str " Download game"))
                           :disable in-progress
                           :on-action {:event/type :spring-lobby/add-task
                                       :task
                                       {:spring-lobby/task-type :spring-lobby/rapid-download
                                        :engine-file (:file matching-engine)
                                        :rapid-id (:id mod-rapid)
                                        :spring-isolation-dir spring-isolation-dir}}
                           :graphic
                           {:fx/type font-icon/lifecycle
                            :icon-literal "mdi-download:16:white"}})
                        (and (not matching-mod) mod-downloadable)
                        (let [{:keys [download-url]} mod-downloadable
                              {:keys [running] :as download} (get http-download download-url)
                              in-progress (or running
                                              (contains? http-download-tasks download-url))]
                          {:fx/type :button
                           :text (if in-progress
                                   (str (u/download-progress download))
                                   " Download game")
                           :disable (boolean in-progress)
                           :on-action {:event/type :spring-lobby/add-task
                                       :task {:spring-lobby/task-type :spring-lobby/http-downloadable
                                              :downloadable mod-downloadable
                                              :spring-isolation-dir spring-isolation-dir}}
                           :graphic
                           {:fx/type font-icon/lifecycle
                            :icon-literal "mdi-download:16:white"}})
                        (and (not matching-map) map-update-tasks)
                        {:fx/type :button
                         :text " Maps updating..."
                         :disable true}
                        (and (not matching-map) map-importable)
                        (let [{:keys [resource-file]} map-importable
                              resource-path (fs/canonical-path resource-file)
                              in-progress (boolean
                                            (or (-> copying (get resource-path) :status boolean)
                                                (contains? import-tasks resource-path)))]
                          {:fx/type :button
                           :text (if in-progress
                                   " Importing..."
                                   " Import map")
                           :tooltip
                           {:fx/type tooltip-nofocus/lifecycle
                            :show-delay skylobby.fx/tooltip-show-delay
                            :text (str (:resource-file map-importable))}
                           :disable in-progress
                           :on-action
                           {:event/type :spring-lobby/add-task
                            :task
                            {:spring-lobby/task-type :spring-lobby/import
                             :importable map-importable
                             :spring-isolation-dir spring-isolation-dir}}
                           :graphic
                           {:fx/type font-icon/lifecycle
                            :icon-literal "mdi-content-copy:16:white"}})
                        (and (not matching-map) map-downloadable)
                        (let [{:keys [download-url]} map-downloadable
                              {:keys [running] :as download} (get http-download download-url)
                              in-progress (or running
                                              (contains? http-download-tasks download-url))]
                          {:fx/type :button
                           :text (if in-progress
                                   (str (u/download-progress download))
                                   " Download map")
                           :disable (boolean in-progress)
                           :on-action
                           {:event/type :spring-lobby/add-task
                            :task
                            {:spring-lobby/task-type :spring-lobby/http-downloadable
                             :downloadable map-downloadable
                             :spring-isolation-dir spring-isolation-dir}}
                           :graphic
                           {:fx/type font-icon/lifecycle
                            :icon-literal "mdi-download:16:white"}})
                        (not matching-engine)
                        {:fx/type :label
                         :text " No engine"}
                        (not matching-mod)
                        (if (string/ends-with? mod-version "$VERSION")
                          {:fx/type :label
                           :text " Unknown game version "}
                          {:fx/type :button
                           :text (if rapid-update-tasks
                                   " Updating rapid..."
                                   " Update rapid")
                           :disable (boolean rapid-update-tasks)
                           :on-action {:event/type :spring-lobby/add-task
                                       :task {:spring-lobby/task-type :spring-lobby/update-rapid
                                              :force true
                                              :engine-version engine-version
                                              :mod-name mod-version
                                              :spring-isolation-dir spring-isolation-dir}}
                           :graphic
                           {:fx/type font-icon/lifecycle
                            :icon-literal "mdi-refresh:16:white"}})
                        (not matching-map)
                        {:fx/type :button
                         :text " No map, update downloads"
                         :on-action
                         {:event/type :spring-lobby/add-task
                          :task
                          (merge
                            {:spring-lobby/task-type :spring-lobby/update-downloadables
                             :force true}
                            springfiles-maps-download-source)}}))])}})}}])}}}}))


(defn download-replays-window [{:fx/keys [context]}]
  (let [
        show (boolean (fx/sub-val context :show-download-replays))
        index-downloads-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/download-bar-replays)
        downloading (boolean (seq index-downloads-tasks))
        bar-replays-page (fx/sub-val context :bar-replays-page)
        page (u/to-number bar-replays-page)
        new-online-replays-count (fx/sub-val context :new-online-replays-count)]
    {:fx/type :stage
     :showing show
     :title (str u/app-name " Download Replays")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-download-replays}
     :height 480
     :width 600
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      {:fx/type :h-box
       :children
       [{:fx/type :pane
         :h-box/hgrow :always}
        {:fx/type :v-box
         :children
         [{:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :h-box
           :alignment :center-left
           :style {:-fx-font-size 16}
           :children
           [
            {:fx/type :button
             :style {:-fx-font-size 16}
             :text (if downloading
                     " Getting Online BAR Replays... "
                     " Get Online BAR Replays")
             :on-action {:event/type :spring-lobby/add-task
                         :task {:spring-lobby/task-type :spring-lobby/download-bar-replays
                                :page page}}
             :disable downloading
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-download:16:white"}}
            {:fx/type :label
             :text " Page: "}
            {:fx/type :text-field
             :text (str page)
             :style {:-fx-max-width 56}
             :on-text-changed {:event/type :spring-lobby/assoc
                               :key :bar-replays-page}}]}
          {:fx/type :label
           :style {:-fx-font-size 16}
           :text (str (when new-online-replays-count
                        (str " Got " new-online-replays-count " new")))}
          {:fx/type :label
           :style {:-fx-font-size 20}
           :text "Spring Official Replays"}
          (let [url "https://replays.springrts.com/"]
            {:fx/type :hyperlink
             :style {:-fx-font-size 18}
             :text url
             :on-action {:event/type :spring-lobby/desktop-browse-url
                         :url url}})
          {:fx/type :label
           :style {:-fx-font-size 20}
           :text "BAR Replays"}
          (let [url "https://bar-rts.com/replays"]
            {:fx/type :hyperlink
             :style {:-fx-font-size 18}
             :text url
             :on-action {:event/type :spring-lobby/desktop-browse-url
                         :url url}})
          {:fx/type :label
           :style {:-fx-font-size 20}
           :text "Spring Fight Club Replays"}
          (let [url "http://replays.springfightclub.com/"]
            {:fx/type :hyperlink
             :style {:-fx-font-size 18}
             :text url
             :on-action {:event/type :spring-lobby/desktop-browse-url
                         :url url}})
          {:fx/type :pane
           :v-box/vgrow :always}]}
        {:fx/type :pane
         :h-box/hgrow :always}]}}}))


(defn replays-window-impl
  [{:fx/keys [context]
    :keys [on-close-request screen-bounds title]}]
  (let [
        extra-replay-sources (fx/sub-val context :extra-replay-sources)
        filter-replay (fx/sub-val context :filter-replay)
        filter-replay-max-players (fx/sub-val context :filter-replay-max-players)
        filter-replay-min-players (fx/sub-val context :filter-replay-min-players)
        filter-replay-min-skill (fx/sub-val context :filter-replay-min-skill)
        filter-replay-source (fx/sub-val context :filter-replay-source)
        filter-replay-type (fx/sub-val context :filter-replay-type)
        filtered-replays (fx/sub-val context :filtered-replays)
        online-bar-replays (fx/sub-val context :online-bar-replays)
        parsed-replays-by-path (fx/sub-val context :parsed-replays-by-path)
        replays-filter-specs (fx/sub-val context :replays-filter-specs)
        replays-window-dedupe (fx/sub-val context :replays-window-dedupe)
        selected-replay-file (fx/sub-val context :selected-replay-file)
        selected-replay-id (fx/sub-val context :selected-replay-id)
        window-states (fx/sub-val context :window-states)
        show (boolean (fx/sub-val context :show-replays))]
    {:fx/type :stage
     :showing show
     :title (or title (str u/app-name " Replays"))
     :icons skylobby.fx/icons
     :on-close-request (or
                         on-close-request
                         {:event/type :spring-lobby/dissoc
                          :key :show-replays})
     :x (skylobby.fx/fitx screen-bounds (get-in window-states [:replays :x]))
     :y (skylobby.fx/fity screen-bounds (get-in window-states [:replays :y]))
     :width (skylobby.fx/fitwidth screen-bounds (get-in window-states [:replays :width]) replays-window-width)
     :height (skylobby.fx/fitheight screen-bounds (get-in window-states [:replays :height]) replays-window-height)
     :on-width-changed (partial skylobby.fx/window-changed :replays :width)
     :on-height-changed (partial skylobby.fx/window-changed :replays :height)
     :on-x-changed (partial skylobby.fx/window-changed :replays :x)
     :on-y-changed (partial skylobby.fx/window-changed :replays :y)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show
        (let [
              local-filenames (->> parsed-replays-by-path
                                   vals
                                   (map :filename)
                                   (filter some?)
                                   set)
              online-only-replays (->> online-bar-replays
                                       vals
                                       (remove (comp local-filenames :filename)))
              all-replays (->> parsed-replays-by-path
                               vals
                               (concat online-only-replays)
                               doall)
              replay-types (set (map :game-type all-replays))
              num-players (->> all-replays
                               (map :replay-player-count)
                               set
                               sort)
              selected-replay (fx/sub-ctx context skylobby.fx/selected-replay-sub)
              refresh-tasks (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/refresh-replays)
              sources (replay-sources {:extra-replay-sources extra-replay-sources})]
          {:fx/type :v-box
           :style {:-fx-font-size 14}
           :children
           (concat
             [{:fx/type :h-box
               :alignment :top-left
               :style {:-fx-font-size 16}
               :children
               [
                {:fx/type :flow-pane
                 :h-box/hgrow :always
                 :style {:-fx-pref-width 200}
                 :children
                 (concat
                   [{:fx/type :label
                     :text " Filter: "}
                    {:fx/type :text-field
                     :style {:-fx-min-width 400}
                     :text (str filter-replay)
                     :prompt-text "Filter by filename, engine, map, game, player, or tag"
                     :on-text-changed {:event/type :spring-lobby/assoc
                                       :key :filter-replay}}]
                   (when-not (string/blank? filter-replay)
                     [{:fx/type fx.ext.node/with-tooltip-props
                       :props
                       {:tooltip
                        {:fx/type tooltip-nofocus/lifecycle
                         :show-delay skylobby.fx/tooltip-show-delay
                         :text "Clear filter"}}
                       :desc
                       {:fx/type :button
                        :on-action {:event/type :spring-lobby/dissoc
                                    :key :filter-replay}
                        :graphic
                        {:fx/type font-icon/lifecycle
                         :icon-literal "mdi-close:16:white"}}}])
                   [{:fx/type :h-box
                     :alignment :center-left
                     :children
                     [
                      {:fx/type :label
                       :text " Filter specs:"}
                      {:fx/type :check-box
                       :selected (boolean replays-filter-specs)
                       :h-box/margin 8
                       :on-selected-changed {:event/type :spring-lobby/assoc
                                             :key :replays-filter-specs}}]}
                    {:fx/type :h-box
                     :alignment :center-left
                     :children
                     [
                      {:fx/type :label
                       :text " Dedupe: "}
                      {:fx/type :check-box
                       :selected (boolean replays-window-dedupe)
                       :h-box/margin 8
                       :on-selected-changed {:event/type :spring-lobby/assoc
                                             :key :replays-window-dedupe}}]}
                    {:fx/type :h-box
                     :alignment :center-left
                     :children
                     (concat
                       [{:fx/type :label
                         :text " Source: "}
                        {:fx/type :combo-box
                         :value filter-replay-source
                         :on-value-changed {:event/type :spring-lobby/assoc
                                            :key :filter-replay-source}
                         :items (concat [nil] (sort (map :replay-source-name sources)))}]
                       (when filter-replay-source
                         [{:fx/type fx.ext.node/with-tooltip-props
                           :props
                           {:tooltip
                            {:fx/type tooltip-nofocus/lifecycle
                             :show-delay skylobby.fx/tooltip-show-delay
                             :text "Clear source"}}
                           :desc
                           {:fx/type :button
                            :on-action {:event/type :spring-lobby/dissoc
                                        :key :filter-replay-source}
                            :graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-close:16:white"}}}]))}
                    {:fx/type :h-box
                     :alignment :center-left
                     :children
                     (concat
                       [{:fx/type :label
                         :text " Type: "}
                        {:fx/type :combo-box
                         :value filter-replay-type
                         :on-value-changed {:event/type :spring-lobby/assoc
                                            :key :filter-replay-type}
                         :items (concat [nil] replay-types)}]
                       (when filter-replay-type
                         [{:fx/type fx.ext.node/with-tooltip-props
                           :props
                           {:tooltip
                            {:fx/type tooltip-nofocus/lifecycle
                             :show-delay skylobby.fx/tooltip-show-delay
                             :text "Clear type"}}
                           :desc
                           {:fx/type :button
                            :on-action {:event/type :spring-lobby/dissoc
                                        :key :filter-replay-type}
                            :graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-close:16:white"}}}]))}
                    {:fx/type :h-box
                     :alignment :center-left
                     :children
                     (concat
                       [{:fx/type :label
                         :text " Min Players: "}
                        {:fx/type :combo-box
                         :value filter-replay-min-players
                         :on-value-changed {:event/type :spring-lobby/assoc
                                            :key :filter-replay-min-players}
                         :items (concat [nil] num-players)}]
                       (when filter-replay-min-players
                         [{:fx/type fx.ext.node/with-tooltip-props
                           :props
                           {:tooltip
                            {:fx/type tooltip-nofocus/lifecycle
                             :show-delay skylobby.fx/tooltip-show-delay
                             :text "Clear min players"}}
                           :desc
                           {:fx/type :button
                            :on-action {:event/type :spring-lobby/dissoc
                                        :key :filter-replay-min-players}
                            :graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-close:16:white"}}}]))}
                    {:fx/type :h-box
                     :alignment :center-left
                     :children
                     (concat
                       [{:fx/type :label
                         :text " Max Players: "}
                        {:fx/type :combo-box
                         :value filter-replay-max-players
                         :on-value-changed {:event/type :spring-lobby/assoc
                                            :key :filter-replay-max-players}
                         :items (concat [nil] num-players)}]
                       [{:fx/type :label
                         :text " Min Avg Skill: "}
                        {:fx/type :text-field
                         :style {:-fx-max-width 60}
                         :text-formatter
                         {:fx/type :text-formatter
                          :value-converter :integer
                          :value (int (or filter-replay-min-skill 0))
                          :on-value-changed {:event/type :spring-lobby/assoc
                                             :key :filter-replay-min-skill}}}]
                       (when filter-replay-max-players
                         [{:fx/type fx.ext.node/with-tooltip-props
                           :props
                           {:tooltip
                            {:fx/type tooltip-nofocus/lifecycle
                             :show-delay skylobby.fx/tooltip-show-delay
                             :text "Clear max players"}}
                           :desc
                           {:fx/type :button
                            :on-action {:event/type :spring-lobby/dissoc
                                        :key :filter-replay-max-players}
                            :graphic
                            {:fx/type font-icon/lifecycle
                             :icon-literal "mdi-close:16:white"}}}]))}
                    {:fx/type :h-box
                     :alignment :center-left
                     :children
                     [{:fx/type :check-box
                       :selected (boolean (fx/sub-val context :replays-window-details))
                       :h-box/margin 8
                       :on-selected-changed {:event/type :spring-lobby/assoc
                                             :key :replays-window-details}}
                      {:fx/type :label
                       :text "Detailed table "}]}
                    (let [refreshing (boolean (seq refresh-tasks))]
                      {:fx/type :button
                       :text (if refreshing
                               " Refreshing... "
                               " Refresh ")
                       :on-action {:event/type :spring-lobby/add-task
                                   :task {:spring-lobby/task-type :spring-lobby/refresh-replays}}
                       :disable refreshing
                       :graphic
                       {:fx/type font-icon/lifecycle
                        :icon-literal "mdi-refresh:16:white"}})]
                  [{:fx/type :button
                    :text " Download"
                    :on-action {:event/type :spring-lobby/assoc
                                :key :show-download-replays}
                    :graphic
                    {:fx/type font-icon/lifecycle
                     :icon-literal "mdi-download:16:white"}}])}
                {:fx/type :pane
                 :h-box/hgrow :sometimes}
                {:fx/type :button
                 :text "Settings"
                 :on-action {:event/type :spring-lobby/toggle
                             :key :show-settings-window}
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-settings:16:white"}}]}
              (if all-replays
                (if (empty? all-replays)
                  {:fx/type :label
                   :style {:-fx-font-size 24}
                   :text " No replays"}
                  {:fx/type :v-box
                   :v-box/vgrow :always
                   :children
                   [{:fx/type :label
                     :text
                     (let [ac (count all-replays)
                           fc (count filtered-replays)]
                       (str ac " replays" (when (not= ac fc) (str ", " fc " match filters"))))}
                    {:fx/type replays-table
                     :v-box/vgrow :always}]})
                {:fx/type :label
                 :style {:-fx-font-size 24}
                 :text " Loading replays..."})]
             (when selected-replay
               [{:fx/type ext-recreate-on-key-changed
                 :key (str (or (fs/canonical-path selected-replay-file)
                               selected-replay-id))
                 :desc
                 {:fx/type replay-view
                  :show-sync-left true}}]))})
       {:fx/type :pane
        :pref-width replays-window-width
        :pref-height replays-window-height})}}))

(defn replays-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :replays-window
      (replays-window-impl state))))


(defn standalone-replay-window
  [{:fx/keys [context]}]
  (let [{:keys [width height]} (skylobby.fx/get-screen-bounds)]
    {:fx/type :stage
     :showing true
     :title (str "skyreplays " u/app-version)
     :icons skylobby.fx/icons
     :x 100
     :y 100
     :width (min replay-window-width width)
     :height (min replay-window-height height)
     :on-close-request {:event/type :spring-lobby/main-window-on-close-request
                        :standalone true}
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root {:fx/type replay-view
             :show-sync true}}}))
