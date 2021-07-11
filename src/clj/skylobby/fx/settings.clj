(ns skylobby.fx.settings
  (:require
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.channel :as fx.channel]
    [skylobby.fx.import :as fx.import]
    [skylobby.fx.replay :as fx.replay]
    [spring-lobby.fs :as fs]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]
    [taoensso.tufte :as tufte])
  (:import
    (java.nio.file Paths)))


(def settings-window-width 800)
(def settings-window-height 1200)


(def settings-window-keys
  [:auto-refresh-replays :chat-font-size :css :disable-tasks :disable-tasks-while-in-game :extra-import-name :extra-import-path :extra-import-sources
   :extra-replay-name :extra-replay-path :extra-replay-recursive :extra-replay-sources :media-player
   :music-dir :music-volume :players-table-columns
   :screen-bounds :show-settings-window :spring-isolation-dir :spring-isolation-dir-draft
   :use-git-mod-version])

(defn settings-window-impl
  [{:keys [auto-refresh-replays chat-font-size css disable-tasks disable-tasks-while-in-game extra-import-name extra-import-path extra-import-sources
           extra-replay-name extra-replay-path extra-replay-recursive media-player music-dir
           music-volume players-table-columns screen-bounds show-settings-window spring-isolation-dir spring-isolation-dir-draft
           use-git-mod-version]
    :as state}]
  {:fx/type :stage
   :showing (boolean show-settings-window)
   :title (str u/app-name " Settings")
   :icons skylobby.fx/icons
   :on-close-request {:event/type :spring-lobby/dissoc
                      :key :show-settings-window}
   :width ((fnil min settings-window-width) (:width screen-bounds) settings-window-width)
   :height ((fnil min settings-window-height) (:height screen-bounds) settings-window-height)
   :scene
   {:fx/type :scene
    :stylesheets (skylobby.fx/stylesheet-urls css)
    :root
    (if show-settings-window
      {:fx/type :scroll-pane
       :fit-to-width true
       :content
       {:fx/type :v-box
        :style {:-fx-font-size 16}
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
              :style {:-fx-min-width 600}
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
              :text " Use git to version .sdd games"}]}
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
              :text " Disable tasks while in game"}]}
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
                                :key :extra-import-path}}]}
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
                                :key :extra-replay-name}}
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
                                    :key :extra-replay-recursive}}]}
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
            :alignment :center-left
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
                                  :key :chat-font-size}}}]}
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
                  :text " Bonus"}]}])}
           {:fx/type :label
            :text " Music"
            :style {:-fx-font-size 24}}
           {:fx/type :h-box
            :alignment :center-left
            :children
            [
             {:fx/type :text-field
              :disable true
              :text (str (fs/canonical-path music-dir))
              :style {:-fx-min-width 600}}
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
                                 :media-player media-player}}]}])}}
     {:fx/type :pane})}})

(defn settings-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :settings-window
      (settings-window-impl state))))
