(ns skylobby.fx.settings
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.bottom-bar :refer [app-update-button]]
    [skylobby.fx.channel :as fx.channel]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.sub :as sub]
    [skylobby.import :as import]
    [skylobby.spads :as spads]
    [skylobby.util :as u]
    [spring-lobby.sound :as sound]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def settings-window-width 1200)
(def settings-window-height 1200)

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


(defn highlighted-label [{:keys [title search]}]
  (let [search-lc (if search
                    (string/lower-case search)
                    "")
        title-lc (if title
                   (string/lower-case title)
                   "")
        i (when-not (string/blank? search-lc)
            (string/index-of title-lc search-lc))]
    {:fx/type :h-box
     :children
     (if i
       [{:fx/type :label
         :text (str (subs title 0 i))}
        {:fx/type :label
         :text (str (subs title i (+ i (count search-lc))))
         :style {:-fx-font-weight "bold"}}
        {:fx/type :label
         :text (str (subs title (+ i (count search-lc))))}]
       [{:fx/type :label
         :text (str title)}])}))

(defn filterable-checkbox-setting [{:keys [check-box search title]}]
  (let [search-lc (string/lower-case (string/trim (or search "")))
        title-lc (string/lower-case (or title ""))]
    (if (string/includes? title-lc search-lc)
      {:fx/type :h-box
       :style {:-fx-font-size 18}
       :alignment :center-left
       :children
       [check-box
        {:fx/type highlighted-label
         :search search
         :title title}]}
      {:fx/type :pane})))

(defn filterable-section [{:keys [children search title]}]
  (let [search-lc (string/lower-case (string/trim (or search "")))
        title-lc (string/lower-case title)
        children-titles (->> children
                             (map :title)
                             (filter some?)
                             (map string/lower-case))]
    (if (or (string/includes? title-lc search-lc)
            (some #(string/includes? % search-lc) children-titles))
      {:fx/type :v-box
       :pref-width 580
       :min-width 580
       :max-width 580
       :children
       (concat
         [{:fx/type :pane
           :style {:-fx-font-size 24}
           :children
           [
            {:fx/type highlighted-label
             :search search
             :title title}]}]
         children)}
      {:fx/type :pane})))

(defn battle-settings
  [{:fx/keys [context]
    :keys [settings-search]}]
  (let [
        debug-spring (fx/sub-val context :debug-spring)
        increment-ids (fx/sub-val context :increment-ids)
        join-battle-as-player (fx/sub-val context :join-battle-as-player)
        leave-battle-on-close-window (fx/sub-val context :leave-battle-on-close-window)
        ready-on-unspec (fx/sub-val context :ready-on-unspec)
        ring-on-auto-unspec (fx/sub-val context :ring-on-auto-unspec)
        ring-on-spec-change (fx/sub-val context :ring-on-spec-change)
        ring-when-game-starts (fx/sub-val context :ring-when-game-starts)
        ring-when-game-ends (fx/sub-val context :ring-when-game-ends)
        show-accolades (fx/sub-val context :show-accolades)
        show-closed-battles (fx/sub-val context :show-closed-battles)
        show-hidden-modoptions (fx/sub-val context :show-hidden-modoptions)
        show-team-skills (fx/sub-val context :show-team-skills)
        unready-after-game (fx/sub-val context :unready-after-game)]
    {:fx/type filterable-section
     :search settings-search
     :title " Battle"
     :children
     [
      {:fx/type filterable-checkbox-setting
       :title " Join battles as a player (not spec)"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean join-battle-as-player)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :join-battle-as-player}}}
      {:fx/type filterable-checkbox-setting
       :title " Show battle view as a tab"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean (fx/sub-val context :battle-as-tab))
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :battle-as-tab}}}
      {:fx/type filterable-checkbox-setting
       :title " Unready after game"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean unready-after-game)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :unready-after-game}}}
      {:fx/type filterable-checkbox-setting
       :title " Ready on unspec"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean ready-on-unspec)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :ready-on-unspec}}}
      {:fx/type filterable-checkbox-setting
       :title " Rejoin battle on rehost or reconnect"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean (fx/sub-val context :auto-rejoin-battle))
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :auto-rejoin-battle}}}
      {:fx/type filterable-checkbox-setting
       :title " Leave battle on close window"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean leave-battle-on-close-window)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :leave-battle-on-close-window}}}
      {:fx/type :h-box
       :alignment :center-left
       :children
       [
        {:fx/type :label
         :text " Players display type: "}
        {:fx/type :combo-box
         :value (or (fx/sub-val context :battle-players-display-type)
                    "table")
         :items ["group" "table"]
         :on-value-changed {:event/type :spring-lobby/assoc
                            :key :battle-players-display-type}}]}
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
                            :key :battle-players-color-type}}]}
      {:fx/type :h-box
       :alignment :center-left
       :children
       [
        {:fx/type :label
         :text " Layout: "}
        {:fx/type :combo-box
         :value (fx/sub-val context :battle-layout)
         :items battle-layouts
         :on-value-changed {:event/type :spring-lobby/assoc
                            :key :battle-layout}}]}
      {:fx/type filterable-checkbox-setting
       :title " Auto import or download resources"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean (fx/sub-val context :auto-get-resources))
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :auto-get-resources}}}
      {:fx/type filterable-checkbox-setting
       :title " Show accolades panel"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean show-accolades)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :show-accolades}}}
      {:fx/type filterable-checkbox-setting
       :title " Show team skills"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean show-team-skills)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :show-team-skills}}}
      {:fx/type filterable-checkbox-setting
       :title " Number team and player ids starting at one"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean increment-ids)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :increment-ids}}}
      {:fx/type filterable-checkbox-setting
       :title " Show closed battles as tabs"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean show-closed-battles)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :show-closed-battles}}}
      {:fx/type filterable-checkbox-setting
       :title " Ring when game starts"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean ring-when-game-starts)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :ring-when-game-starts}}}
      {:fx/type filterable-checkbox-setting
       :title " Ring when game ends"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean ring-when-game-ends)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :ring-when-game-ends}}}
      {:fx/type filterable-checkbox-setting
       :title " Ring when spec state changes"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean ring-on-spec-change)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :ring-on-spec-change}}}
      {:fx/type filterable-checkbox-setting
       :title " Ring on auto unspec"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean ring-on-auto-unspec)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :ring-on-auto-unspec}}}
      {:fx/type filterable-checkbox-setting
       :title " Show hidden modoptions"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean show-hidden-modoptions)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :show-hidden-modoptions}}}
      {:fx/type filterable-checkbox-setting
       :title " Debug spring mode (write script and show command)"
       :search settings-search
       :check-box
       {:fx/type :check-box
        :selected (boolean debug-spring)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :debug-spring}}}]}))

(defn settings-root
  [{:fx/keys [context]}]
  (let [
        battles-table-images (fx/sub-val context :battles-table-images)
        chat-auto-complete (fx/sub-val context :chat-auto-complete)
        chat-font-size (fx/sub-val context :chat-font-size)
        extra-import-name (fx/sub-val context :extra-import-name)
        extra-import-path (fx/sub-val context :extra-import-path)
        extra-import-sources (fx/sub-val context :extra-import-sources)
        extra-replay-name (fx/sub-val context :extra-replay-name)
        extra-replay-path (fx/sub-val context :extra-replay-path)
        extra-replay-recursive (fx/sub-val context :extra-replay-recursive)
        focus-chat-on-message (fx/sub-val context :focus-chat-on-message)
        focus-on-incoming-direct-message (fx/sub-val context :focus-on-incoming-direct-message)
        focus-on-incoming-battle-message (fx/sub-val context :focus-on-incoming-battle-message)
        hide-barmanager-messages (fx/sub-val context :hide-barmanager-messages)
        hide-joinas-spec (fx/sub-val context :hide-joinas-spec)
        hide-spads-messages (fx/sub-val context :hide-spads-messages)
        hide-vote-messages (fx/sub-val context :hide-vote-messages)
        highlight-tabs-with-new-battle-messages (fx/sub-val context :highlight-tabs-with-new-battle-messages)
        highlight-tabs-with-new-chat-messages (fx/sub-val context :highlight-tabs-with-new-chat-messages)
        ipc-server (fx/sub-val context :ipc-server)
        ipc-server-enabled (fx/sub-val context :ipc-server-enabled)
        ipc-server-port (fx/sub-val context :ipc-server-port)
        media-player (fx/sub-val context :media-player)
        music-dir (fx/sub-val context :music-dir)
        music-volume (fx/sub-val context :music-volume)
        notify-on-incoming-direct-message (fx/sub-val context :notify-on-incoming-direct-message)
        notify-on-incoming-battle-message (fx/sub-val context :notify-on-incoming-battle-message)
        notify-when-in-game (fx/sub-val context :notify-when-in-game)
        notify-when-tab-selected (fx/sub-val context :notify-when-tab-selected)
        players-table-columns (fx/sub-val context :players-table-columns)
        prevent-non-host-rings (fx/sub-val context :prevent-non-host-rings)
        replay-source-enabled (fx/sub-val context :replay-source-enabled)
        replay-sources (fx/sub-ctx context sub/replay-sources)
        ring-sound-file (fx/sub-val context :ring-sound-file)
        ring-volume (fx/sub-val context :ring-volume)
        settings-search (fx/sub-val context :settings-search)
        show-battle-preview (fx/sub-val context :show-battle-preview)
        ;use-db-for-downloadables (fx/sub-val context :use-db-for-downloadables)
        ;use-db-for-importables (fx/sub-val context :use-db-for-importables)
        use-db-for-rapid (fx/sub-val context :use-db-for-rapid)
        use-db-for-replays (fx/sub-val context :use-db-for-replays)
        use-default-ring-sound (fx/sub-val context :use-default-ring-sound)
        use-git-mod-version (fx/sub-val context :use-git-mod-version)
        user-agent-override (fx/sub-val context :user-agent-override)
        windows-as-tabs (fx/sub-val context :windows-as-tabs)]
    {:fx/type :scroll-pane
     :fit-to-width true
     :content
     {:fx/type :v-box
      :children
      [{:fx/type :h-box
        :alignment :center-left
        :style {:-fx-font-size 20}
        :children
        [{:fx/type :label
          :text " Search: "}
         {:fx/type :text-field
          :text (str settings-search)
          :prompt-text "setting name..."
          :pref-width 800
          :on-text-changed {:event/type :spring-lobby/assoc
                            :key :settings-search}}]}
       {:fx/type :flow-pane
        :vgap 5
        :hgap 5
        :padding 5
        :style {:-fx-font-size 16}
        :children
        [
         {:fx/type filterable-section
          :search settings-search
          :title "General"
          :children
          [
           {:fx/type filterable-checkbox-setting
            :title " Use tabs instead of some windows"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean windows-as-tabs)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :windows-as-tabs}}}
           {:fx/type filterable-checkbox-setting
            :title " Images view of battles"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean battles-table-images)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :battles-table-images}}}
           {:fx/type filterable-checkbox-setting
            :title " Preview battles on click"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean show-battle-preview)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :show-battle-preview}}}
           {:fx/type filterable-checkbox-setting
            :title " Use git to version .sdd games"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean use-git-mod-version)
             :on-selected-changed {:event/type :spring-lobby/on-change-git-version}}}]}
         {:fx/type filterable-section
          :search settings-search
          :title "Database (experimental)"
          :children
          [{:fx/type :label
            :text "Experimental! Use database for certain indexes to lower memory usage"}
           #_
           {:fx/type :h-box
            :style {:-fx-font-size 18}
            :children
            [
             {:fx/type :check-box
              :selected (boolean use-db-for-downloadables)
              :on-selected-changed {:event/type :spring-lobby/assoc
                                    :key :use-db-for-downloadables}}
             {:fx/type :label
              :text " Use database for downloads index"}]}
           #_
           {:fx/type :h-box
            :style {:-fx-font-size 18}
            :children
            [
             {:fx/type :check-box
              :selected (boolean use-db-for-importables)
              :on-selected-changed {:event/type :spring-lobby/assoc
                                    :key :use-db-for-importables}}
             {:fx/type :label
              :text " Use database for imports index"}]}
           {:fx/type :h-box
            :style {:-fx-font-size 18}
            :children
            [
             {:fx/type :check-box
              :selected (boolean use-db-for-rapid)
              :on-selected-changed {:event/type :spring-lobby/assoc
                                    :key :use-db-for-rapid}}
             {:fx/type :label
              :text " Use database for rapid index"}]}
           {:fx/type :h-box
            :style {:-fx-font-size 18}
            :children
            [
             {:fx/type :check-box
              :selected (boolean use-db-for-replays)
              :on-selected-changed {:event/type :spring-lobby/assoc
                                    :key :use-db-for-replays}}
             {:fx/type :label
              :text " Use database for replays index"}]}]}
         {:fx/type filterable-section
          :search settings-search
          :title "Spring"
          :children
          [
           {:fx/type :button
            :style-class ["button" "skylobby-normal"]
            :style {:-fx-font-size 18}
            :text "Configure Spring directory"
            :on-action {:event/type :spring-lobby/toggle-window
                        :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                        :key :show-spring-picker}}]}
         (let [
               starting (seq (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/start-ipc-server))
               stopping (seq (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/stop-ipc-server))
               stopping-or-starting (boolean (or starting stopping))]
           {:fx/type filterable-section
            :search settings-search
            :title "Server"
            :children
            [
             {:fx/type :label
              :text "HTTP server used for Web UI and replay file association"}
             {:fx/type filterable-checkbox-setting
              :title " Run server on start"
              :search settings-search
              :check-box
              {:fx/type :check-box
               :selected (boolean ipc-server-enabled)
               :on-selected-changed {:event/type :spring-lobby/assoc
                                     :key :ipc-server-enabled}}}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :label
                :text " Port: "}
               {:fx/type :text-field
                :text-formatter
                {:fx/type :text-formatter
                 :value-converter :integer
                 :value (int (or (when (integer? ipc-server-port) ipc-server-port)
                                 u/default-ipc-port))
                 :on-value-changed {:event/type :spring-lobby/assoc
                                    :key :ipc-server-port}}}]}
             {:fx/type :label
              :text (cond
                      starting "Server starting..."
                      stopping "Server stopping..."
                      (fn? ipc-server) "Server running"
                      :else "Server not running")}
             {:fx/type :h-box
              :alignment :center-left
              :children
              (if (fn? ipc-server)
                [{:fx/type :button
                  :text "Restart server"
                  :disable stopping-or-starting
                  :on-action
                  {:event/type :spring-lobby/add-task
                   :task {:spring-lobby/task-type :spring-lobby/start-ipc-server}}}
                 {:fx/type :button
                  :text "Stop server"
                  :disable stopping-or-starting
                  :on-action
                  {:event/type :spring-lobby/add-task
                   :task {:spring-lobby/task-type :spring-lobby/stop-ipc-server}}}]
                [{:fx/type :button
                  :text "Start server"
                  :disable stopping-or-starting
                  :on-action
                  {:event/type :spring-lobby/add-task
                   :task {:spring-lobby/task-type :spring-lobby/start-ipc-server}}}])}]})
         {:fx/type filterable-section
          :search settings-search
          :title " Chat"
          :children
          [
           {:fx/type filterable-checkbox-setting
            :title " Notification for incoming direct chat message"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean notify-on-incoming-direct-message)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :notify-on-incoming-direct-message}}}
           {:fx/type filterable-checkbox-setting
            :title " Notification for battle chat message"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean notify-on-incoming-battle-message)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :notify-on-incoming-battle-message}}}
           {:fx/type filterable-checkbox-setting
            :title " Notifications when in game"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean notify-when-in-game)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :notify-when-in-game}}}
           {:fx/type filterable-checkbox-setting
            :title " Notifications when chat tab is selected"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean notify-when-tab-selected)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :notify-when-tab-selected}}}
           {:fx/type filterable-checkbox-setting
            :title " Focus main window for incoming direct chat message"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean focus-on-incoming-direct-message)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :focus-on-incoming-direct-message}}}
           {:fx/type filterable-checkbox-setting
            :title " Focus main window for battle chat message"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean focus-on-incoming-battle-message)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :focus-on-incoming-battle-message}}}
           {:fx/type filterable-checkbox-setting
            :title " Auto complete suggestions"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean chat-auto-complete)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :chat-auto-complete}}}
           {:fx/type filterable-checkbox-setting
            :title " Focus chat on incoming message"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean focus-chat-on-message)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :focus-chat-on-message}}}
           {:fx/type filterable-checkbox-setting
            :title " Highlight tabs with new chat messages"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean highlight-tabs-with-new-chat-messages)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :highlight-tabs-with-new-chat-messages}}}
           {:fx/type filterable-checkbox-setting
            :title " Highlight tabs with new battle messages"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean highlight-tabs-with-new-battle-messages)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :highlight-tabs-with-new-battle-messages}}}
           {:fx/type filterable-checkbox-setting
            :title " Color my username"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean (fx/sub-val context :chat-color-username))
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :chat-color-username}}}
           {:fx/type filterable-checkbox-setting
            :title " Highlight my username in messages"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean (fx/sub-val context :chat-highlight-username))
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :chat-highlight-username}}}
           {:fx/type :label
            :text "Highlight words (comma or space separated): "}
           {:fx/type :text-field
            :text (str (fx/sub-val context :chat-highlight-words))
            :style {
                    :-fx-max-width 480}
            :on-text-changed {:event/type :spring-lobby/assoc
                              :key :chat-highlight-words}}]}
         {:fx/type battle-settings
          :settings-search settings-search}
         {:fx/type filterable-section
          :search settings-search
          :title " Performance"
          :children
          [
           {:fx/type filterable-checkbox-setting
            :title " Disable tasks"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean (fx/sub-val context :disable-tasks))
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :disable-tasks}}}
           {:fx/type filterable-checkbox-setting
            :title " Disable tasks while in game"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean (fx/sub-val context :disable-tasks-while-in-game))
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :disable-tasks-while-in-game}}}]}
         {:fx/type filterable-section
          :search settings-search
          :title " Import Sources"
          :children
          [
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
                (import/import-sources extra-import-sources))}
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
         {:fx/type filterable-section
          :search settings-search
          :title " Replays"
          :children
          [
             {:fx/type filterable-checkbox-setting
              :title " Auto refresh replays"
              :search settings-search
              :check-box
              {:fx/type :check-box
               :selected (boolean (fx/sub-val context :auto-refresh-replays))
               :on-selected-changed {:event/type :spring-lobby/assoc
                                     :key :auto-refresh-replays}}}
             {:fx/type filterable-checkbox-setting
              :title " Refresh replays after game"
              :search settings-search
              :check-box
              {:fx/type :check-box
               :selected (boolean (fx/sub-val context :refresh-replays-after-game))
               :on-selected-changed {:event/type :spring-lobby/assoc
                                     :key :refresh-replays-after-game}}}
             {:fx/type filterable-checkbox-setting
              :title " Auto get selected replay resources"
              :search settings-search
              :check-box
              {:fx/type :check-box
               :selected (boolean (fx/sub-val context :auto-get-replay-resources))
               :on-selected-changed {:event/type :spring-lobby/assoc
                                     :key :auto-get-replay-resources}}}
             {:fx/type :label
              :style {:-fx-font-size 20}
              :text " Replay sources from Spring roots:"}
             {:fx/type :v-box
              :children
              (map
                (fn [{:keys [file]}]
                  (let [path (fs/canonical-path file)]
                    {:fx/type :h-box
                     :alignment :center-left
                     :children
                     [{:fx/type :check-box
                       :on-action {:event/type :spring-lobby/assoc-in
                                   :path [:replay-source-enabled path]}
                       :selected (or (not (contains? replay-source-enabled path))
                                     (get replay-source-enabled path))}
                      {:fx/type :label
                       :text (str " " path)
                       :style {:-fx-font-size 18}}]}))
                (filter :builtin replay-sources))}
             {:fx/type :label
              :style {:-fx-font-size 20}
              :text " Custom replay sources:"}
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
                (remove :builtin replay-sources))}
             {:fx/type :label
              :style {:-fx-font-size 20}
              :text " Add replay source:"}
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
                :text (cond
                        (string/blank? extra-replay-path)
                        "Set replay source path"
                        (string/blank? extra-replay-name)
                        "Set replay source name"
                        :else
                        "Add replay source")
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-plus:16"}}]}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
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
               {:fx/type :button
                :style-class ["button" "skylobby-normal"]
                :on-action {:event/type :spring-lobby/file-chooser-dir
                            :as-path true
                            :initial-dir extra-replay-path
                            :path [:extra-replay-path]}
                :text ""
                :graphic
                {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-file-find:16"}}]}
             {:fx/type :h-box
              :alignment :center-left
              :children
              [
               {:fx/type :label
                :text " Recursive: "}
               {:fx/type :check-box
                :selected (boolean extra-replay-recursive)
                :on-selected-changed {:event/type :spring-lobby/assoc
                                      :key :extra-replay-recursive}}]}]}
         {:fx/type filterable-section
          :search settings-search
          :title " Appearance"
          :children
          [
           {:fx/type :h-box
            :alignment :center-left
            :children
            [
             {:fx/type :label
              :text " Preset: "}
             {:fx/type :button
              :on-action {:event/type :spring-lobby/update-css
                          :css skylobby.fx/black-style-data}
              :text "Black (default)"}
             {:fx/type :button
              :on-action {:event/type :spring-lobby/update-css
                          :css skylobby.fx/grey-style-data}
              :text "Grey"}
             {:fx/type :button
              :on-action {:event/type :spring-lobby/update-css
                          :css skylobby.fx/javafx-style-data}
              :text "JavaFX"}]}
           {:fx/type :pane
            :pref-height 8}
           (let [custom-file (fs/file (fs/app-root) "custom-css.edn")]
             {:fx/type :button
              :on-action {:event/type :spring-lobby/load-custom-css-edn
                          :file custom-file}
              :text ""
              :graphic
              {:fx/type :v-box
               :children
               [{:fx/type :label
                 :text (str "Load custom CSS as EDN from:")}
                {:fx/type :label
                 :text (str custom-file)}]}})
           {:fx/type :label
            :text (str (fx/sub-val context :load-custom-css-edn-message))}
           #_
           (let [custom-css-file (fs/file (fs/app-root) "custom.css")]
             {:fx/type :button
              :on-action {:event/type :spring-lobby/load-custom-css
                          :file custom-css-file}
              :text (str "Custom from " custom-css-file)})
           {:fx/type :pane
            :pref-height 8}
           {:fx/type :h-box
            :alignment :center-left
            :children
            [{:fx/type :label
              :text " Chat history font size: "}
             {:fx/type :text-field
              :text-formatter
              {:fx/type :text-formatter
               :value-converter :integer
               :value (int (or (when (integer? chat-font-size) chat-font-size)
                               fx.channel/default-font-size))
               :on-value-changed {:event/type :spring-lobby/assoc
                                  :key :chat-font-size}}}]}]}
         {:fx/type filterable-section
          :search settings-search
          :title " Battle Players Columns"
          :children
          [
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
         {:fx/type filterable-section
          :search settings-search
          :title " Sound"
          :children
          (concat
            [
             {:fx/type filterable-checkbox-setting
              :title " Use default ring sound"
              :search settings-search
              :check-box
              {:fx/type :check-box
               :selected (boolean use-default-ring-sound)
               :on-selected-changed {:event/type :spring-lobby/assoc
                                     :key :use-default-ring-sound}}}]
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
                           (sound/play-ring {:ring-sound-file ring-sound-file
                                             :ring-volume ring-volume
                                             :use-default-ring-sound use-default-ring-sound}))
              :text "Test Ring"}
             {:fx/type :pane
              :pref-height 8}
             {:fx/type filterable-checkbox-setting
              :title " Prevent rings except from host"
              :search settings-search
              :check-box
              {:fx/type :check-box
               :selected (boolean prevent-non-host-rings)
               :on-selected-changed {:event/type :spring-lobby/assoc
                                     :key :prevent-non-host-rings}}}])}
         {:fx/type filterable-section
          :search settings-search
          :title " Music"
          :children
          [
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
                :style {:-fx-max-width 480
                        :-fx-pref-width 480}}
               {:fx/type :button
                :style-class ["button" "skylobby-normal"]
                :on-action {:event/type :spring-lobby/file-chooser-dir
                            :initial-dir music-dir
                            :path [:music-dir]
                            :post-task {:spring-lobby/task-type :spring-lobby/update-music-queue}}
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
         {:fx/type filterable-section
          :search settings-search
          :title " User Agent Override"
          :children
          [
           {:fx/type :text-field
            :text (str user-agent-override)
            :prompt-text (str (u/agent-string))
            :style {:-fx-max-width 480}
            :on-text-changed {:event/type :spring-lobby/assoc
                              :key :user-agent-override}}]}
         (let [client-id-type (fx/sub-val context :client-id-type)
               client-id-type (get client-id-types-set client-id-type default-client-id-type)]
           {:fx/type filterable-section
            :search settings-search
            :title " Client ID"
            :children
            [
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
                           (fx/sub-val context :client-id-override)))
              :style {:-fx-max-width 240}
              :disable true}
             {:fx/type :button
              :text "Generate"
              :on-action {:event/type :spring-lobby/randomize-client-id}}]})
         {:fx/type filterable-section
          :search settings-search
          :title " SPADS Messages"
          :children
          [
           {:fx/type filterable-checkbox-setting
            :title " Hide user vote messages"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean hide-vote-messages)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :hide-vote-messages}}}
           {:fx/type filterable-checkbox-setting
            :title " Hide \"!joinas spec\" messages"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean hide-joinas-spec)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :hide-joinas-spec}}}
           {:fx/type filterable-checkbox-setting
            :title " Hide \"BarManager\" messages"
            :search settings-search
            :check-box
            {:fx/type :check-box
             :selected (boolean hide-barmanager-messages)
             :on-selected-changed {:event/type :spring-lobby/assoc
                                   :key :hide-barmaager-messages}}}
           {:fx/type :label
            :text "Hide message types:"
            :style {:-fx-font-size 20}}
           {:fx/type :v-box
            :children
            (map
              (fn [message-type]
                {:fx/type :h-box
                 :children
                 [
                  {:fx/type :check-box
                   :selected (boolean (get hide-spads-messages message-type))
                   :on-selected-changed {:event/type :spring-lobby/assoc-in
                                         :path [:hide-spads-messages message-type]}}
                  {:fx/type :label
                   :text (str " " message-type)}]})
              spads/message-types)}]}
         {:fx/type filterable-section
          :search settings-search
          :title " Update"
          :children
          [
           {:fx/type :button
            :on-action
            {:event/type :spring-lobby/add-task
             :task {:spring-lobby/task-type :spring-lobby/check-app-update}}
            :disable (boolean (seq (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/check-app-update)))
            :style-class ["button" "skylobby-normal"]
            :text "Check for skylobby update"
            :graphic
            {:fx/type font-icon/lifecycle
             :icon-literal "mdi-refresh"}}
           {:fx/type app-update-button}]}]}]}}))



(defn settings-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        show (boolean
               (and
                 (fx/sub-val context :show-settings-window)
                 (not (fx/sub-val context :windows-as-tabs))))
        window-states (fx/sub-val context :window-states)]
    {:fx/type :stage
     :showing (boolean show)
     :title (str u/app-name " Settings")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-settings-window}
     :x (skylobby.fx/fitx screen-bounds (get-in window-states [:settings :x]))
     :y (skylobby.fx/fity screen-bounds (get-in window-states [:settings :y]))
     :width (skylobby.fx/fitwidth screen-bounds (get-in window-states [:settings :width]) settings-window-width)
     :height (skylobby.fx/fitheight screen-bounds (get-in window-states [:settings :height]) settings-window-height)
     :on-width-changed (partial skylobby.fx/window-changed :settings :width)
     :on-height-changed (partial skylobby.fx/window-changed :settings :height)
     :on-x-changed (partial skylobby.fx/window-changed :settings :x)
     :on-y-changed (partial skylobby.fx/window-changed :settings :y)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show
        {:fx/type settings-root}
        {:fx/type :pane
         :pref-width settings-window-width
         :pref-height settings-window-height})}}))

(defn settings-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :settings-window
      (settings-window-impl state))))
