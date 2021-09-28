(ns skylobby.fx.settings
  (:require
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.channel :as fx.channel]
    [skylobby.fx.import :as fx.import]
    [skylobby.fx.replay :as fx.replay]
    [spring-lobby.fs :as fs]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.sound :as sound]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (java.nio.file Paths)))


(def settings-window-width 1200)
(def settings-window-height 1200)

(def user-agent-placeholder (u/agent-string))

(def default-client-id-type "random")
(def client-id-types
  ["random"
   "hardware"
   "zero"])
(def client-id-types-set
  (set client-id-types))

(def battle-layouts
  [
   "vertical"
   "horizontal"])


(def settings-window-keys
  [:auto-refresh-replays :auto-rejoin-battle :battle-as-tab :battle-layout :battle-players-color-type :chat-font-size :chat-highlight-words :client-id-override :client-id-type :css :disable-tasks :disable-tasks-while-in-game :extra-import-name :extra-import-path :extra-import-sources
   :extra-replay-name :extra-replay-path :extra-replay-recursive :extra-replay-sources :leave-battle-on-close-window :media-player
   :music-dir :music-volume :players-table-columns :ready-on-unspec :ring-sound-file :ring-volume
   :screen-bounds :show-settings-window :spring-isolation-dir :spring-isolation-dir-draft
   :unready-after-game :use-default-ring-sound :use-git-mod-version :user-agent-override :window-states])

(defn settings-window-impl
  [{:keys [auto-get-resources auto-refresh-replays auto-rejoin-battle battle-as-tab battle-layout battle-players-color-type
           chat-font-size chat-highlight-username chat-highlight-words client-id-override client-id-type css
           disable-tasks disable-tasks-while-in-game extra-import-name extra-import-path extra-import-sources
           extra-replay-name extra-replay-path extra-replay-recursive leave-battle-on-close-window media-player music-dir
           music-volume players-table-columns ready-on-unspec ring-sound-file ring-volume screen-bounds show-settings-window spring-isolation-dir spring-isolation-dir-draft
           unready-after-game use-default-ring-sound use-git-mod-version user-agent-override window-states]
    :as state}]
  {:fx/type :stage
   :showing (boolean show-settings-window)
   :title (str u/app-name " Settings")
   :icons skylobby.fx/icons
   :on-close-request {:event/type :spring-lobby/dissoc
                      :key :show-settings-window}
   :x (get-in window-states [:settings :x] 0)
   :y (get-in window-states [:settings :y] 0)
   :width ((fnil min settings-window-width) (:width screen-bounds) (get-in window-states [:settings :width] settings-window-width))
   :height ((fnil min settings-window-height) (:height screen-bounds) (get-in window-states [:settings :height] settings-window-height))
   :on-width-changed (partial skylobby.fx/window-changed :settings :width)
   :on-height-changed (partial skylobby.fx/window-changed :settings :height)
   :on-x-changed (partial skylobby.fx/window-changed :settings :x)
   :on-y-changed (partial skylobby.fx/window-changed :settings :y)
   :scene
   {:fx/type :scene
    :stylesheets (skylobby.fx/stylesheet-urls css)
    :root
    (if show-settings-window
      {:fx/type :scroll-pane
       :fit-to-width true
       :content
       {:fx/type :flow-pane
        :vgap 5
        :hgap 5
        :padding 5
        :style {:-fx-font-size 16}
        :children
        [{:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          (concat
            [
             {:fx/type :label
              :text " Default Spring Dir"
              :style {:-fx-font-size 24}}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :text-field
                :on-focused-changed {:event/type :spring-lobby/spring-root-focused-changed}
                :text (str
                        (or
                          spring-isolation-dir-draft
                          (fs/canonical-path spring-isolation-dir)
                          spring-isolation-dir))
                :style {:-fx-max-width 480}
                :on-text-changed {:event/type :spring-lobby/assoc
                                  :key :spring-isolation-dir-draft}}
               {:fx/type :button
                :style-class ["button" "skylobby-normal"]
                :on-action {:event/type :spring-lobby/file-chooser-spring-root}
                :text ""
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-file-find:16"}}]}]
            (when-not (string/blank? spring-isolation-dir-draft)
              (let [valid (try
                            (Paths/get (some-> spring-isolation-dir-draft fs/file .toURI))
                            (catch Exception e
                              (log/trace e "Invalid spring path" spring-isolation-dir-draft)))]
                [{:fx/type :button
                  :on-action {:event/type :spring-lobby/save-spring-isolation-dir}
                  :disable (boolean (not valid))
                  :text (if valid
                          "Save new spring dir"
                          "Invalid spring dir")
                  :graphic
                  {:fx/type font-icon/lifecycle
                   :icon-literal "mdi-content-save:16:white"}}]))
            [{:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :label
                :text " Preset: "}
               {:fx/type :button
                :on-action {:event/type :spring-lobby/assoc
                            :key :spring-isolation-dir
                            :value (fs/default-isolation-dir)}
                :text "Skylobby"}
               {:fx/type :button
                :on-action {:event/type :spring-lobby/assoc
                            :key :spring-isolation-dir
                            :value (fs/bar-root)}
                :text "Beyond All Reason"}
               {:fx/type :button
                :on-action {:event/type :spring-lobby/assoc
                            :key :spring-isolation-dir
                            :value (fs/spring-root)}
                :text "Spring"}]}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean use-git-mod-version)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :use-git-mod-version}}
               {:fx/type :label
                :text " Use git to version .sdd games"}]}])}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          [
             {:fx/type :label
              :text " Chat"
              :style {:-fx-font-size 24}}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean chat-highlight-username)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :chat-highlight-username}}
               {:fx/type :label
                :text " Highlight username"}]}
             {:fx/type :label
              :text "Highlight words (comma or space separated): "}
             {:fx/type :text-field
              :text (str chat-highlight-words)
              :style {
                      :-fx-max-width 480}
              :on-text-changed {:event/type :spring-lobby/assoc
                                :key :chat-highlight-words}}]}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          [
             {:fx/type :label
              :text " Battle"
              :style {:-fx-font-size 24}}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean battle-as-tab)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :battle-as-tab}}
               {:fx/type :label
                :text " Show battle view as a tab"}]}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean unready-after-game)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :unready-after-game}}
               {:fx/type :label
                :text " Unready after game"}]}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean ready-on-unspec)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :ready-on-unspec}}
               {:fx/type :label
                :text " Ready on unspec"}]}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean auto-rejoin-battle)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :auto-rejoin-battle}}
               {:fx/type :label
                :text " Rejoin battle on rehost or reconnect"}]}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean leave-battle-on-close-window)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :leave-battle-on-close-window}}
               {:fx/type :label
                :text " Leave battle on close window"}]}
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
                                   :key :battle-players-color-type}}]}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :label
                :text " Layout: "}
               {:fx/type :combo-box
                :value battle-layout
                :items battle-layouts
                :on-value-changed {:event/type :spring-lobby/assoc
                                   :key :battle-layout}}]}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :check-box
                :selected (boolean auto-get-resources)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :auto-get-resources}}
               {:fx/type :label
                :text " Auto import or download resources"}]}]}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          [
             {:fx/type :label
              :text " Performance"
              :style {:-fx-font-size 24}}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean disable-tasks)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :disable-tasks}}
               {:fx/type :label
                :text " Disable tasks"}]}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean disable-tasks-while-in-game)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :disable-tasks-while-in-game}}
               {:fx/type :label
                :text " Disable tasks while in game"}]}]}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          [
             {:fx/type :label
              :text " Import Sources"
              :style {:-fx-font-size 24}}
             {:fx/type :v-box
              :children
              (map
                (fn [{:keys [builtin file import-source-name]}]
                  {:fx/type :h-box
                   :alignment :center-left
                   :children
                   [{:fx/type :button
                     :style-class ["button" "skylobby-normal"]
                     :on-action {:event/type :spring-lobby/delete-extra-import-source
                                 :file file}
                     :disable (boolean builtin)
                     :text ""
                     :graphic
                     {:fx/type font-icon/lifecycle
                      :icon-literal "mdi-delete:16"}}
                    {:fx/type :v-box
                     :children
                     [{:fx/type :label
                       :text (str " " import-source-name)}
                      {:fx/type :label
                       :text (str " " (fs/canonical-path file))
                       :style {:-fx-font-size 14}}]}]})
                (fx.import/import-sources extra-import-sources))}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :button
                :style-class ["button" "skylobby-normal"]
                :text ""
                :disable (or (string/blank? extra-import-name)
                             (string/blank? extra-import-path))
                :on-action {:event/type :spring-lobby/add-extra-import-source
                            :extra-import-path extra-import-path
                            :extra-import-name extra-import-name}
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-plus:16"}}
               {:fx/type :label
                :text " Name: "}
               {:fx/type :text-field
                :text (str extra-import-name)
                :on-text-changed {:event/type :spring-lobby/assoc
                                  :key :extra-import-name}}
               {:fx/type :label
                :text " Path: "}
               {:fx/type :text-field
                :text (str extra-import-path)
                :on-text-changed {:event/type :spring-lobby/assoc
                                  :key :extra-import-path}}]}]}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          [
             {:fx/type :label
              :text " Replay Sources"
              :style {:-fx-font-size 24}}
             {:fx/type :h-box
              :style {:-fx-font-size 18}
              :children
              [
               {:fx/type :check-box
                :selected (boolean auto-refresh-replays)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :auto-refresh-replays}}
               {:fx/type :label
                :text " Auto refresh replays"}]}
             {:fx/type :v-box
              :children
              (map
                (fn [{:keys [builtin file recursive replay-source-name]}]
                  {:fx/type :h-box
                   :alignment :center-left
                   :children
                   [{:fx/type :button
                     :style-class ["button" "skylobby-normal"]
                     :on-action {:event/type :spring-lobby/delete-extra-replay-source
                                 :file file}
                     :disable (boolean builtin)
                     :text ""
                     :graphic
                     {:fx/type font-icon/lifecycle
                      :icon-literal "mdi-delete:16"}}
                    {:fx/type :v-box
                     :children
                     [{:fx/type :h-box
                       :children
                       (concat
                         [{:fx/type :label
                           :text (str " " replay-source-name)
                           :style {:-fx-font-size 18}}]
                         (when recursive
                           [{:fx/type :label
                             :text " (recursive)"
                             :style {:-fx-text-fill :red}}]))}
                      {:fx/type :label
                       :text (str " " (fs/canonical-path file))
                       :style {:-fx-font-size 14}}]}]})
                (fx.replay/replay-sources state))}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :button
                :style-class ["button" "skylobby-normal"]
                :disable (or (string/blank? extra-replay-name)
                             (string/blank? extra-replay-path))
                :on-action {:event/type :spring-lobby/add-extra-replay-source
                            :extra-replay-path extra-replay-path
                            :extra-replay-name extra-replay-name
                            :extra-replay-recursive extra-replay-recursive}
                :text ""
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-plus:16"}}
               {:fx/type :label
                :text " Name: "}
               {:fx/type :text-field
                :text (str extra-replay-name)
                :on-text-changed {:event/type :spring-lobby/assoc
                                  :key :extra-replay-name}}]}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :label
                :text " Path: "}
               {:fx/type :text-field
                :text (str extra-replay-path)
                :on-text-changed {:event/type :spring-lobby/assoc
                                  :key :extra-replay-path}}
               {:fx/type :label
                :text " Recursive: "}
               {:fx/type :check-box
                :selected (boolean extra-replay-recursive)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :extra-replay-recursive}}]}]}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          [
             {:fx/type :label
              :text " Appearance"
              :style {:-fx-font-size 24}}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :label
                :text " Preset: "}
               {:fx/type :button
                :on-action {:event/type :spring-lobby/update-css
                            :css skylobby.fx/default-style-data}
                :text "Default"}
               {:fx/type :button
                :on-action {:event/type :spring-lobby/update-css
                            :css skylobby.fx/black-style-data}
                :text "Black"}
               {:fx/type :button
                :on-action {:event/type :spring-lobby/update-css
                            :css skylobby.fx/javafx-style-data}
                :text "JavaFX"}]}
             (let [custom-file (fs/file (fs/app-root) "custom-css.edn")]
               {:fx/type :button
                :on-action {:event/type :spring-lobby/load-custom-css
                            :file custom-file}
                :text (str "Custom from " custom-file)})
             (let [custom-css-file (fs/file (fs/app-root) "custom.css")]
               {:fx/type :button
                :on-action {:event/type :spring-lobby/assoc
                            :key :css
                            :value {:cljfx.css/url (-> custom-css-file .toURI .toURL)}}
                :text (str "Custom from " custom-css-file)})
             {:fx/type :h-box
              :children
              [{:fx/type :label
                :text " Chat font size: "}
               {:fx/type :text-field
                :text-formatter
                {:fx/type :text-formatter
                 :value-converter :integer
                 :value (int (or (when (number? chat-font-size) chat-font-size)
                                 fx.channel/default-font-size))
                 :on-value-changed {:event/type :spring-lobby/assoc
                                    :key :chat-font-size}}}]}]}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          [
             {:fx/type :label
              :text " Battle Players Columns"
              :style {:-fx-font-size 24}}
             {:fx/type :v-box
              :children
              (let [{:keys [skill ally team color status spectator faction rank country bonus]} players-table-columns]
                [{:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean skill)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :skill]}}
                   {:fx/type :label
                    :text " Skill"}]}
                 {:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean ally)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :ally]}}
                   {:fx/type :label
                    :text " Ally"}]}
                 {:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean team)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :team]}}
                   {:fx/type :label
                    :text " Team"}]}
                 {:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean color)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :color]}}
                   {:fx/type :label
                    :text " Color"}]}
                 {:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean status)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :status]}}
                   {:fx/type :label
                    :text " Status"}]}
                 {:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean spectator)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :spectator]}}
                   {:fx/type :label
                    :text " Spectator"}]}
                 {:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean faction)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :faction]}}
                   {:fx/type :label
                    :text " Faction"}]}
                 {:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean rank)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :rank]}}
                   {:fx/type :label
                    :text " Rank"}]}
                 {:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean country)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :country]}}
                   {:fx/type :label
                    :text " Country"}]}
                 {:fx/type :h-box
                  :children
                  [
                   {:fx/type :check-box
                    :selected (boolean bonus)
                    :on-selected-changed {:event/type :spring-lobby/assoc-in
                                          :path [:players-table-columns :bonus]}}
                   {:fx/type :label
                    :text " Bonus"}]}])}]}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          (concat
            [
               {:fx/type :label
                :text " Sound"
                :style {:-fx-font-size 24}}
               {:fx/type :h-box
                :style {:-fx-font-size 18}
                :children
                [
                 {:fx/type :check-box
                  :selected (boolean use-default-ring-sound)
                  :on-selected-changed {:event/type :spring-lobby/assoc
                                        :key :use-default-ring-sound}}
                 {:fx/type :label
                  :text " Use default ring sound"}]}]
            (when-not use-default-ring-sound
              [
               {:fx/type :label
                :text " Ring Sound File: "
                :style {:-fx-font-size 18}}
               {:fx/type :h-box
                :alignment :center-left
                :children
                [
                 {:fx/type :text-field
                  :disable true
                  :text (str (fs/canonical-path ring-sound-file))
                  :style {:-fx-maxwidth 500}}
                 {:fx/type :button
                  :style-class ["button" "skylobby-normal"]
                  :on-action {:event/type :spring-lobby/file-chooser-ring-sound}
                  :text ""
                  :graphic
                  {:fx/type font-icon/lifecycle
                   :icon-literal "mdi-file-find:16"}}]}])
            [{:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :label
                :text " Ring Volume: "
                :style {:-fx-font-size 18}}
               {:fx/type :slider
                :min 0.0
                :max 1.0
                :value (if (number? ring-volume)
                         ring-volume
                         1.0)
                :on-value-changed {:event/type :spring-lobby/assoc
                                   :key :ring-volume}}]}
             {:fx/type :button
              :on-action (fn [_event]
                           (sound/play-ring state))
              :text "Test Ring"}])}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          [
             {:fx/type :label
              :text " Music"
              :style {:-fx-font-size 24}}
             {:fx/type :label
              :text " Music Folder: "
              :style {:-fx-font-size 18}}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :text-field
                :disable true
                :text (str (fs/canonical-path music-dir))
                :style {:-fx-max-width 480}}
               {:fx/type :button
                :style-class ["button" "skylobby-normal"]
                :on-action {:event/type :spring-lobby/file-chooser-spring-root
                            :target [:music-dir]}
                :text ""
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-file-find:16"}}]}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :label
                :text " Music Volume: "
                :style {:-fx-font-size 18}}
               {:fx/type :slider
                :min 0.0
                :max 1.0
                :value (if (number? music-volume)
                         music-volume
                         1.0)
                :on-value-changed {:event/type :spring-lobby/on-change-music-volume
                                   :media-player media-player}}]}]}
         {:fx/type :v-box
          :min-width 580
          :max-width 580
          :children
          [
           {:fx/type :label
            :text " User Agent Override"
            :style {:-fx-font-size 24}}
           {:fx/type :text-field
            :text (str user-agent-override)
            :prompt-text (str user-agent-placeholder)
            :on-text-changed {:event/type :spring-lobby/assoc
                              :key :user-agent-override}}]}
         (let [client-id-type (get client-id-types-set client-id-type default-client-id-type)]
           {:fx/type :v-box
            :min-width 580
            :max-width 580
            :children
            [
             {:fx/type :label
              :text " Client ID"
              :style {:-fx-font-size 24}}
             {:fx/type :label
              :text " Type: "}
             {:fx/type :combo-box
              :value client-id-type
              :items client-id-types
              :on-value-changed {:event/type :spring-lobby/assoc
                                 :key :client-id-type}}
             {:fx/type :label
              :text " ID: "}
             {:fx/type :text-field
              :text (str (case client-id-type
                           "zero" 0
                           "hardware" (u/hardware-client-id)
                           client-id-override))
              :style {:-fx-max-width 240}
              :disable true}
             {:fx/type :button
              :text "Generate"
              :on-action {:event/type :spring-lobby/randomize-client-id}}]})]}}
     {:fx/type :pane})}})

(defn settings-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :settings-window
      (settings-window-impl state))))
