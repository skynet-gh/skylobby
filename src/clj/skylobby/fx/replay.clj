(ns skylobby.fx.replay
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [cljfx.ext.table-view :as fx.ext.table-view]
    [clojure.java.io :as io]
    [clojure.string :as string]
    clojure.set
    java-time
    skylobby.fx
    [skylobby.fx.battle :refer [minimap-types]]
    [skylobby.fx.download :refer [springfiles-maps-download-source]]
    [skylobby.fx.engine-sync :refer [engine-sync-pane]]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-table-column-auto-size]]
    [skylobby.fx.map-sync :refer [map-sync-pane]]
    [skylobby.fx.minimap :as fx.minimap]
    [skylobby.fx.mod-sync :refer [mod-sync-pane]]
    [skylobby.fx.players-table :as fx.players-table]
    [skylobby.fx.rich-text :as fx.rich-text]
    [skylobby.fx.virtualized-scroll-pane :as fx.virtualized-scroll-pane]
    [skylobby.resource :as resource]
    [spring-lobby.fs :as fs]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.http :as http]
    [spring-lobby.spring :as spring]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (java.time LocalDateTime)
    (java.util TimeZone)
    (javafx.scene.paint Color)
    (org.fxmisc.richtext.model ReadOnlyStyledDocumentBuilder SegmentOps StyledSegment)))


(def replay-window-width 1600)
(def replay-window-height 900)


(def replays-window-width 2400)
(def replays-window-height 1200)


(defn replay-sources [{:keys [extra-replay-sources]}]
  (concat
    (fs/builtin-replay-sources)
    extra-replay-sources))


(defn- replay-player-count
  [{:keys [player-counts]}]
  (reduce (fnil + 0) 0 player-counts))

(defn- replay-skills
  [{:keys [body]}]
  (let [skills (some->> body :script-data :game
                        (filter (comp #(string/starts-with? % "player") name first))
                        (filter (comp #{0 "0"} :spectator second))
                        (map (comp :skill second))
                        (map u/parse-skill)
                        (filter some?))]
    skills))

(defn- min-skill [coll]
  (when (seq coll)
    (reduce min Long/MAX_VALUE coll)))

(defn- average-skill [coll]
  (when (seq coll)
    (with-precision 3
      (/ (bigdec (reduce + coll))
         (bigdec (count coll))))))

(defn- max-skill [coll]
  (when (seq coll)
    (reduce max 0 coll)))


(defn- sanitize-replay-filter [s]
  (-> s (string/replace #"[^\p{Alnum}]" "") string/lower-case))

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
        builder (ReadOnlyStyledDocumentBuilder. (SegmentOps/styledTextOps) "")]
    (doseq [chat chat-log]
      (let [{:keys [from dest message]} chat
            player (get player-num-to-name from)
            color (get player-name-to-color player)
            javafx-color (if color
                           (u/spring-color-to-javafx color)
                           Color/YELLOW)
            css-color (some-> javafx-color str u/hex-color-to-css)
            is-spec (and (not (string/blank? player)) (not color))
            dest-type (case dest
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


(def replay-view-keys
  [:battle-players-color-type :copying :downloadables-by-url :extracting :file-cache :gitting
   :http-download :importables-by-path :map-details :mod-details :players-table-columns :rapid-data-by-version
   :rapid-download :replay-details :replay-minimap-type :spring-isolation-dir
   :springfiles-search-results :tasks-by-type :update-engines :update-maps :update-mods])

(defn replay-view
  [{:keys [battle-players-color-type download-tasks engines engines-by-version file-cache import-tasks
           maps-by-version map-details mods-by-version mod-details mod-update-tasks players-table-columns replay-details
           replay-minimap-type selected-replay show-sync show-sync-left spring-isolation-dir
           tasks-by-type]
    :as state}]
  (let [script-data (-> selected-replay :body :script-data)
        {:keys [gametype mapname] :as game} (:game script-data)
        selected-engine-version (-> selected-replay :header :engine-version)
        selected-matching-engine (get engines-by-version selected-engine-version)
        mod-name gametype
        selected-matching-mod (get mods-by-version mod-name)
        map-name mapname
        selected-matching-map (get maps-by-version map-name)
        teams-by-id (->> game
                         (filter (comp #(string/starts-with? % "team") name first))
                         (mapv
                           (fn [[teamid team]]
                             (let [[_all id] (re-find #"team(\d+)" (name teamid))]
                               [id team])))
                         (into {}))
        indexed-mod (get mods-by-version gametype)
        replay-mod-details (resource/cached-details mod-details indexed-mod)
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
                                  (map (juxt (comp string/trim :username) :team-color))
                                  (into {}))
        indexed-map (get maps-by-version mapname)
        replay-map-details (resource/cached-details map-details indexed-map)
        engine-update-tasks (->> (get tasks-by-type :spring-lobby/reconcile-engines)
                                 set)
        extract-tasks (->> (get tasks-by-type :spring-lobby/extract-7z)
                           (map (comp fs/canonical-path :file))
                           set)
        map-update-tasks (->> tasks-by-type
                              (filter (comp #{:spring-lobby/map-details :spring-lobby/reconcile-maps} first))
                              (mapcat second)
                              set)
        rapid-tasks-by-id (->> (get tasks-by-type :spring-lobby/rapid-download)
                               (map (juxt :rapid-id identity))
                               (into {}))
        sync-pane {:fx/type :flow-pane
                   :vgap 5
                   :hgap 5
                   :padding 5
                   :children
                   [(if-let [id (:id selected-replay)]
                      (let [in-progress (contains? download-tasks id)]
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
                      (merge
                        {:fx/type engine-sync-pane
                         :engine-details selected-matching-engine
                         :engine-file (:file selected-matching-engine)
                         :engine-update-tasks engine-update-tasks
                         :engine-version selected-engine-version
                         :extract-tasks extract-tasks
                         :tasks-by-type tasks-by-type}
                        state))
                    (merge
                      {:fx/type mod-sync-pane
                       :battle-modname mod-name
                       :battle-mod-details replay-mod-details
                       :engine-details selected-matching-engine
                       :engine-file (:file selected-matching-engine)
                       :file-cache file-cache
                       :import-tasks import-tasks
                       :indexed-mod indexed-mod
                       :mod-update-tasks mod-update-tasks
                       :rapid-tasks-by-id rapid-tasks-by-id
                       :tasks-by-type tasks-by-type}
                      state)
                    (merge
                      {:fx/type map-sync-pane
                       :battle-map map-name
                       :battle-map-details replay-map-details
                       :import-tasks import-tasks
                       :indexed-map indexed-map
                       :map-update-tasks map-update-tasks
                       :tasks-by-type tasks-by-type}
                      state)]}]
    {:fx/type :h-box
     :alignment :center-left
     :children
     [{:fx/type :split-pane
       :h-box/hgrow :always
       :divider-positions [0.70]
       :items
       [
        {:fx/type :v-box
         :children
         (concat
           [{:fx/type fx.players-table/players-table
             :v-box/vgrow :always
             :am-host false
             :battle-modname gametype
             :battle-players-color-type battle-players-color-type
             :players players-and-bots
             :players-table-columns players-table-columns
             :sides sides
             :singleplayer true}
            {:fx/type :h-box
             :alignment :center-left
             :children
             [
              {:fx/type :label
               :text " Color player name: "}
              {:fx/type :combo-box
               :value (or battle-players-color-type (first u/player-name-color-types))
               :items u/player-name-color-types
               :on-value-changed {:event/type :spring-lobby/assoc
                                  :key :battle-players-color-type}}]}]
           (when (and selected-matching-engine selected-matching-mod selected-matching-map)
             (let [watch-button {:fx/type :button
                                 :style {:-fx-font-size 24}
                                 :text " Watch"
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
           (if-let [details (get replay-details (fs/canonical-path (:file selected-replay)))]
             (let [player-num-to-name (->> details
                                           :body
                                           :demo-stream
                                           (map (comp :demo-stream-chunk :body))
                                           (filter (comp #{6} :command :header))
                                           (map :body)
                                           (map (juxt :player-num (comp u/remove-nonprintable :player-name)))
                                           (into {}))]
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
                              (->> details
                                   :body
                                   :demo-stream
                                   (map (comp :demo-stream-chunk :body))
                                   (filter (comp #{7} :command :header))
                                   (map :body)
                                   (remove (comp #(string/starts-with? % "My player ID is") :message)))
                              {:player-name-to-color player-name-to-color
                               :player-num-to-name player-num-to-name})}}])
             [{:fx/type :label
               :text "Loading replay stream..."}]))}]}
      {:fx/type :v-box
       :children
       (concat
         [
          {:fx/type fx.minimap/minimap-pane
           :map-name mapname
           :map-details replay-map-details
           :minimap-type replay-minimap-type
           :minimap-type-key :replay-minimap-type
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


(def replay-id
  (some-fn :id (comp :game-id :header)))


(defn replays-table
  [{:keys [copying download-tasks engines engines-by-version engine-update-tasks extract-tasks
           extracting file-cache http-download http-download-tasks import-tasks
           maps-by-version mods-by-version rapid-data-by-version rapid-download
           rapid-update-tasks replay-downloads-by-engine replay-downloads-by-map
           replay-downloads-by-mod rapid-tasks replay-imports-by-map replay-imports-by-mod
           replays-window-details replays replays-tags replays-watched selected-replay spring-isolation-dir
           tasks-by-type
           time-zone-id]}]
  (let [
        map-update-tasks (->> tasks-by-type
                              (filter (comp #{:spring-lobby/reconcile-maps} first))
                              (mapcat second)
                              seq)
        mod-update-tasks (->> tasks-by-type
                              (filter (comp #{:spring-lobby/reconcile-mods} first))
                              (mapcat second)
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
       :items replays
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
              :cell-value-factory replay-id
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
            :cell-value-factory #(-> % :body :script-data :game :mapname)
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [map-name]
               {:text (str map-name)})}}
           {:fx/type :table-column
            :text "Game"
            :resizable true
            :pref-width 300
            :cell-value-factory #(-> % :body :script-data :game :gametype)
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [mod-name]
               {:text (str mod-name)})}}
           {:fx/type :table-column
            :text "Timestamp"
            :resizable false
            :pref-width 160
            :cell-value-factory #(some-> % :header :unix-time (* 1000))
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
            :cell-value-factory (comp min-skill replay-skills)
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [min-skill]
               {:text (str min-skill)})}}
           {:fx/type :table-column
            :text "Skill Avg"
            :resizable false
            :pref-width 80
            :cell-value-factory (comp average-skill replay-skills)
            :cell-factory
            {:fx/cell-type :table-cell
             :describe
             (fn [avg-skill]
               {:text (str avg-skill)})}}
           {:fx/type :table-column
            :text "Skill Max"
            :resizable false
            :pref-width 80
            :cell-value-factory (comp max-skill replay-skills)
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
              :cell-value-factory #(-> % :header :engine-version)
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
            :cell-value-factory #(-> % :header :game-time)
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
               (let [id (replay-id replay)]
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
                   (when-let [id (replay-id i)]
                     (let [mod-version (-> i :body :script-data :game :gametype)]
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
                    (let [engine-version (-> i :header :engine-version)
                          matching-engine (get engines-by-version engine-version)
                          engine-downloadable (get replay-downloads-by-engine engine-version)
                          mod-version (-> i :body :script-data :game :gametype)
                          matching-mod (get mods-by-version mod-version)
                          mod-downloadable (get replay-downloads-by-mod mod-version)
                          mod-importable (get replay-imports-by-mod mod-version)
                          mod-rapid (get rapid-data-by-version mod-version)
                          map-name (-> i :body :script-data :game :mapname)
                          matching-map (get maps-by-version map-name)
                          map-downloadable (get replay-downloads-by-map map-name)
                          map-importable (get replay-imports-by-map map-name)
                          mod-rapid-download (get rapid-download (:id mod-rapid))]
                      (cond
                        (:id i) ; BAR online replay
                        (let [fileName (:fileName i)
                              download-url (when fileName (http/bar-replay-download-url fileName))
                              {:keys [running] :as download} (get http-download download-url)
                              in-progress (or running
                                              (contains? download-tasks (:id i)))]
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
                         :text " Watch"
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
                           {:fx/type :tooltip
                            :show-delay [10 :ms]
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


(def replays-window-keys
  (concat
    replay-view-keys
    [:bar-replays-page :by-spring-root :copying :css :downloadables-by-url
     :extra-replay-sources :extracting :file-cache :filter-replay :filter-replay-max-players
     :filter-replay-min-players :filter-replay-min-skill :filter-replay-source :filter-replay-type
     :http-download :importables-by-path :map-details :mod-details :new-online-replays-count :on-close-request
     :online-bar-replays :parsed-replays-by-path :rapid-data-by-version :rapid-download :replay-details
     :replay-downloads-by-engine :replay-downloads-by-map :replay-downloads-by-mod
     :replay-imports-by-map :replay-imports-by-mod :replay-minimap-type :replays-filter-specs :replays-tags
     :replays-watched :replays-window-dedupe :replays-window-details :selected-replay-file :selected-replay-id
     :settings-button :show-replays :spring-isolation-dir :springfiles-search-results]))

(defn replays-window-impl
  [{:keys [bar-replays-page by-spring-root css extra-replay-sources file-cache
           filter-replay filter-replay-max-players filter-replay-min-players filter-replay-min-skill
           filter-replay-source filter-replay-type map-details mod-details new-online-replays-count
           on-close-request online-bar-replays replays-filter-specs replays-tags replays-window-dedupe
           replays-window-details parsed-replays-by-path screen-bounds selected-replay-file
           selected-replay-id show-replays spring-isolation-dir tasks-by-type title]
    :as state}]
  {:fx/type :stage
   :showing (boolean show-replays)
   :title (or title (str u/app-name " Replays"))
   :icons skylobby.fx/icons
   :on-close-request
   (or
     on-close-request
     {:event/type :spring-lobby/dissoc
      :key :show-replays})
   :width ((fnil min replays-window-width) (:width screen-bounds) replays-window-width)
   :height ((fnil min replays-window-height) (:height screen-bounds) replays-window-height)
   :scene
   {:fx/type :scene
    :stylesheets (skylobby.fx/stylesheet-urls css)
    :root
    (if show-replays
      (let [{:keys [engines maps mods]} (get by-spring-root (fs/canonical-path spring-isolation-dir))
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
                             (sort-by (comp str :unix-time :header))
                             reverse
                             doall)
            replay-types (set (map :game-type all-replays))
            num-players (->> all-replays
                             (map replay-player-count)
                             set
                             sort)
            filter-terms (->> (string/split (or filter-replay "") #"\s+")
                              (remove string/blank?)
                              (map string/lower-case))
            includes-term? (fn [s term]
                             (let [lc (string/lower-case (or s ""))]
                               (string/includes? lc term)))
            replays (->> all-replays
                         (filter
                           (fn [replay]
                             (if filter-replay-source
                               (= filter-replay-source (:source-name replay))
                               true)))
                         (filter
                           (fn [replay]
                             (if filter-replay-type
                               (= filter-replay-type (:game-type replay))
                               true)))
                         (filter
                           (fn [replay]
                             (if filter-replay-min-players
                               (<= filter-replay-min-players (replay-player-count replay))
                               true)))
                         (filter
                           (fn [replay]
                             (if filter-replay-max-players
                               (<= (replay-player-count replay) filter-replay-max-players)
                               true)))
                         (filter
                           (fn [replay]
                             (if filter-replay-min-skill
                               (if-let [avg (average-skill (replay-skills replay))]
                                 (<= filter-replay-min-skill avg)
                                 false)
                               true)))
                         (filter
                           (fn [replay]
                             (if (empty? filter-terms)
                               true
                               (every?
                                 (some-fn
                                   (partial includes-term? (replay-id replay))
                                   (partial includes-term? (get replays-tags (replay-id replay)))
                                   (partial includes-term? (-> replay :header :engine-version))
                                   (partial includes-term? (-> replay :body :script-data :game :gametype))
                                   (partial includes-term? (-> replay :body :script-data :game :mapname))
                                   (fn [term]
                                     (let [players (some->> replay :body :script-data :game
                                                            (filter (comp #(string/starts-with? % "player") name first))
                                                            (filter
                                                              (if replays-filter-specs
                                                                (constantly true)
                                                                (some-fn
                                                                  (comp #{0 "0"} :spectator second)
                                                                  (comp not #(contains? % :spectator) second))))
                                                            (map (comp #(some % [:name :username]) second))
                                                            (map sanitize-replay-filter))]
                                       (some #(includes-term? % term) players))))
                                 filter-terms)))))
            replays (if replays-window-dedupe
                      (:replays
                        (reduce ; dedupe by id
                          (fn [agg curr]
                            (let [id (replay-id curr)]
                              (cond
                                (not id)
                                (update agg :replays conj curr)
                                (contains? (:seen-ids agg) id) agg
                                :else
                                (-> agg
                                    (update :replays conj curr)
                                    (update :seen-ids conj id)))))
                          {:replays []
                           :seen-ids #{}}
                          replays))
                      replays)
            selected-replay (or (get parsed-replays-by-path (fs/canonical-path selected-replay-file))
                                (get online-bar-replays selected-replay-id))
            engines-by-version (into {} (map (juxt :engine-version identity) engines))
            mods-by-version (into {} (map (juxt :mod-name identity) mods))
            maps-by-version (into {} (map (juxt :map-name identity) maps))
            extract-tasks (->> (get tasks-by-type :spring-lobby/extract-7z)
                               (map (comp fs/canonical-path :file))
                               set)
            import-tasks (->> (get tasks-by-type :spring-lobby/import)
                              (map (comp fs/canonical-path :resource-file :importable))
                              set)
            refresh-tasks (get tasks-by-type :spring-lobby/refresh-replays)
            index-downloads-tasks (get tasks-by-type :spring-lobby/download-bar-replays)
            download-tasks (->> (get tasks-by-type :spring-lobby/download-bar-replay)
                                (map :id)
                                set)
            http-download-tasks (->> (get tasks-by-type :spring-lobby/http-downloadable)
                                     (map (comp :download-url :downloadable))
                                     set)
            rapid-tasks (->> (get tasks-by-type :spring-lobby/rapid-download)
                             (map :rapid-id)
                             set)
            rapid-update-tasks (->> (get tasks-by-type :spring-lobby/update-rapid)
                                    seq)
            engine-update-tasks (->> (get tasks-by-type :spring-lobby/reconcile-engines)
                                     seq)
            map-update-tasks (->> tasks-by-type
                                  (filter (comp #{:spring-lobby/map-details :spring-lobby/reconcile-maps} first))
                                  (mapcat second)
                                  seq)
            mod-update-tasks (->> tasks-by-type
                                  (filter (comp #{:spring-lobby/mod-details :spring-lobby/reconcile-mods} first))
                                  (mapcat second)
                                  seq)
            time-zone-id (.toZoneId (TimeZone/getDefault))
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
                      {:fx/type :tooltip
                       :show-delay [10 :ms]
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
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
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
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
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
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
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
                          {:fx/type :tooltip
                           :show-delay [10 :ms]
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
                     :selected (boolean replays-window-details)
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
                (let [downloading (boolean (seq index-downloads-tasks))
                      page (u/to-number bar-replays-page)]
                  [{:fx/type :button
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
                                      :key :bar-replays-page}}
                   {:fx/type :label
                    :text (str (when new-online-replays-count
                                 (str " Got " new-online-replays-count " new")))}]))}
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
                (merge
                  {:fx/type replays-table
                   :v-box/vgrow :always}
                  state
                  {:download-tasks download-tasks
                   :engine-update-tasks engine-update-tasks
                   :engines-by-version engines-by-version
                   :engines engines
                   :extract-tasks extract-tasks
                   :http-download-tasks http-download-tasks
                   :import-tasks import-tasks
                   :map-update-tasks map-update-tasks
                   :maps-by-version maps-by-version
                   :mod-update-tasks mod-update-tasks
                   :mods-by-version mods-by-version
                   :rapid-update-tasks rapid-update-tasks
                   :rapid-tasks rapid-tasks
                   :replays replays
                   :selected-replay selected-replay
                   :tasks-by-type tasks-by-type
                   :time-zone-id time-zone-id}))
             {:fx/type :label
              :style {:-fx-font-size 24}
              :text " Loading replays..."})]
           (when selected-replay
             [{:fx/type ext-recreate-on-key-changed
               :key (str (or (fs/canonical-path selected-replay-file)
                             selected-replay-id))
               :desc
               (merge
                 {:fx/type replay-view}
                 state
                 {:download-tasks download-tasks
                  :engines-by-version engines-by-version
                  :engines engines
                  :file-cache file-cache
                  :import-tasks import-tasks
                  :maps-by-version maps-by-version
                  :map-details map-details
                  :mod-details mod-details
                  :mod-update-tasks mod-update-tasks
                  :mods-by-version mods-by-version
                  :selected-replay selected-replay
                  :show-sync-left true
                  :tasks-by-type tasks-by-type})}]))})
      {:fx/type :pane})}})

(defn replays-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :replays-window
      (replays-window-impl state))))


(def app-version (u/app-version))


(defn standalone-replay-window
  [{{:keys [by-spring-root css selected-replay spring-isolation-dir]
     :as state}
    :state}]
  (let [{:keys [width height]} (skylobby.fx/screen-bounds)
        {:keys [engines maps mods] :as spring-data} (get by-spring-root (fs/canonical-path spring-isolation-dir))
        engines-by-version (into {} (map (juxt :engine-version identity) engines))
        mods-by-version (into {} (map (juxt :mod-name identity) mods))
        maps-by-version (into {} (map (juxt :map-name identity) maps))]
    {:fx/type :stage
     :showing true
     :title (str "skyreplays " app-version)
     :icons skylobby.fx/icons
     :x 100
     :y 100
     :width (min replay-window-width width)
     :height (min replay-window-height height)
     :on-close-request {:event/type :spring-lobby/main-window-on-close-request
                        :standalone true}
     :scene
     {:fx/type :scene
      :stylesheets (skylobby.fx/stylesheet-urls css)
      :root (merge
              {:fx/type replay-view}
              (select-keys state replay-view-keys)
              spring-data
              {
               :engines-by-version engines-by-version
               :maps-by-version maps-by-version
               :mods-by-version mods-by-version
               :selected-replay selected-replay
               :show-sync true})}}))
