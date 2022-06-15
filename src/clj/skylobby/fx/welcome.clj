(ns skylobby.fx.welcome
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.server :as fx.server]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def app-update-browseurl "https://github.com/skynet-gh/skylobby/releases")


(defn connect-button [{:fx/keys [context]}]
  (let [{:keys [accepted client-data]} (fx/sub-ctx context skylobby.fx/selected-server-data-sub)
        {:keys [client client-deferred connecting]} client-data
        password (fx/sub-val context :password)
        username (fx/sub-val context :username)
        server (fx/sub-val context :server)
        server-url (first server)
        server-key (u/server-key {:server-url server-url :username username})]
    {:fx/type :h-box
     :children
     (concat
       [{:fx/type :button
         :text (if client
                 (if accepted
                   "Disconnect"
                   "Logging in...")
                 (if (or connecting client-deferred)
                   "Connecting..."
                   "Connect"))
         :disable (boolean
                    (or
                      (and client (not accepted))
                      (and
                        server
                        (not client)
                        (or connecting
                            client-deferred))
                      (string/blank? username)
                      (string/blank? password)))
         :on-action (if client
                      {:event/type :spring-lobby/disconnect
                       :server-key server-key}
                      {:event/type :spring-lobby/connect
                       :server server
                       :server-key server-key
                       :password password
                       :username username})}]
       (when (or (and client-deferred (or (not client) (not accepted)))
                 (and client (not client-deferred)))
         [{:fx/type :button
           :style-class ["button" "skylobby-normal"]
           :text ""
           :tooltip
           {:fx/type tooltip-nofocus/lifecycle
            :show-delay skylobby.fx/tooltip-show-delay
            :style {:-fx-font-size 14}
            :text "Cancel connect"}
           :on-action {:event/type :spring-lobby/cancel-connect
                       :client-deferred client-deferred
                       :client client
                       :server-key server-key}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-close-octagon:16"}}]))}))


(def local-buttons-width 200)
(def local-buttons-text-gap 16)

(defn singleplayer-buttons
  [{:fx/keys [context]}]
  {:fx/type :v-box
   :spacing 10
   :children
   (concat
     [
      {:fx/type :button
       :style-class ["button" "skylobby-normal"]
       :pref-width local-buttons-width
       :text "Singleplayer"
       :on-action {:event/type :spring-lobby/start-singleplayer-battle}
       :alignment :center-left
       :graphic-text-gap local-buttons-text-gap
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-account:30"}}
      {:fx/type :button
       :style-class ["button" "skylobby-normal"]
       :pref-width local-buttons-width
       :text "LAN/Direct"
       :on-action {:event/type :spring-lobby/toggle-window
                   :windows-as-tabs true
                   :key :show-direct-connect}
       :alignment :center-left
       :graphic-text-gap local-buttons-text-gap
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-lan-connect:30"}}
      {:fx/type :button
       :style-class ["button" "skylobby-normal"]
       :pref-width local-buttons-width
       :text "Replays"
       :on-action {:event/type :spring-lobby/toggle-window
                   :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                   :key :show-replays}
       :alignment :center-left
       :graphic-text-gap local-buttons-text-gap
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-movie:30"}}
      {:fx/type :button
       :style-class ["button" "skylobby-normal"]
       :pref-width local-buttons-width
       :text "Scenarios"
       :on-action {:event/type :spring-lobby/toggle-window
                   :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                   :key :show-scenarios-window}
       :alignment :center-left
       :graphic-text-gap local-buttons-text-gap
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-checkerboard:30"}}]
     (when (not (fx/sub-val context :spring-lobby.main/spring-root-arg))
       [{:fx/type :button
         :style-class ["button" "skylobby-normal"]
         :pref-width local-buttons-width
         :text "Spring"
         :on-action {:event/type :spring-lobby/toggle-window
                     :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                     :key :show-spring-picker}
         :alignment :center-left
         :graphic-text-gap local-buttons-text-gap
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal "mdi-white-balance-sunny:30"}}])
     [{:fx/type :button
       :style-class ["button" "skylobby-normal"]
       :pref-width local-buttons-width
       :text "Settings"
       :on-action {:event/type :spring-lobby/toggle-window
                   :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                   :key :show-settings-window}
       :alignment :center-left
       :graphic-text-gap local-buttons-text-gap
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-settings:30"}}
      {:fx/type :button
       :style-class ["button" "skylobby-normal"]
       :pref-width local-buttons-width
       :text "Report Bug"
       :on-action {:event/type :spring-lobby/desktop-browse-url
                   :url "https://github.com/skynet-gh/skylobby/issues"}
       :alignment :center-left
       :graphic-text-gap local-buttons-text-gap
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-bug:30"}}
      {:fx/type :pane
       :v-box/vgrow :always}])})


(defn multiplayer-buttons
  [{:fx/keys [context]}]
  (let [server-key (fx/sub-ctx context skylobby.fx/welcome-server-key-sub)
        agreement (fx/sub-val context get-in [:by-server server-key :agreement])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        login-error (fx/sub-val context get-in [:login-error server-key])
        server (fx/sub-val context :server)
        servers (fx/sub-val context :servers)
        password (fx/sub-val context :password)
        username (fx/sub-val context :username)
        verification-code (fx/sub-val context :verification-code)
        tasks-by-type (fx/sub-ctx context skylobby.fx/tasks-by-type-sub)
        auto-connect-running (seq (get tasks-by-type :spring-lobby/auto-connect-servers))
        spring-root (fx/sub-val context :spring-isolation-dir)]
    {:fx/type :v-box
     :spacing 10
     :children
     (concat
       [{:fx/type :label
         :text "Join a Multiplayer Server:"}
        {:fx/type :h-box
         :children
         [
          {:fx/type fx.server/server-combo-box
           :server server
           :servers servers
           :on-value-changed {:event/type :spring-lobby/on-change-server}}
          {:fx/type :button
           :style-class ["button" "skylobby-normal"]
           :text (if server "Edit" "Add")
           :on-action {:event/type :spring-lobby/toggle
                       :key :show-servers-window}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal
            (if server
              "mdi-wrench:30"
              "mdi-plus:30")}}]}]
       (when (= "server2.beyondallreason.info:8201" (first server))
         [{:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :text "You must first login once at "}
            (let [url "https://server2.beyondallreason.info"]
              {:fx/type :hyperlink
               :text url
               :on-action {:event/type :spring-lobby/desktop-browse-url
                           :url url}})]}])
       [{:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :label
           :text "Username: "}
          {:fx/type :text-field
           :text username
           :prompt-text "Username"
           :style {:-fx-pref-width 300
                   :-fx-max-width 300}
           :disable (boolean (not server))
           :on-text-changed {:event/type :spring-lobby/username-change
                             :server-url (first server)}}]}]
       (if-not client-data
         [
          {:fx/type :h-box
           :alignment :center-left
           :children
           [{:fx/type :label
             :text "Password: "}
            {:fx/type :password-field
             :text password
             :disable (boolean (not server))
             :prompt-text "Password"
             :style {:-fx-pref-width 300}
             :on-text-changed {:event/type :spring-lobby/password-change
                               :server-url (first server)}}]}]
         [{:fx/type :label
           :style {:-fx-font-size 16}
           :text "Logged in"}
          {:fx/type :button
           :text "Go to server tab"
           :on-action {:event/type :spring-lobby/assoc
                       :key :selected-server-tab
                       :value (str username "@" (first server))}}])
       [
        {:fx/type :pane
         :style {:-fx-min-height 20
                 :-fx-pref-height 20}}
        (let [[server-url server-details] server
              server-spring-root (:spring-isolation-dir server-details)]
          {:fx/type :v-box
           :style {:-fx-font-size 16}
           :children
           [
            {:fx/type :label
             :text (if (or (not server-spring-root)
                           (= server-spring-root spring-root))
                     "Using default Spring dir"
                     "Using server-specific Spring dir")
             :style {:-fx-font-size 20}}
            {:fx/type :h-box
             :alignment :center-left
             :children
             (concat
               [{:fx/type :text-field
                 :disable true
                 :text (str (or server-spring-root spring-root))
                 :style {:-fx-min-width 400
                         :-fx-font-size 16}}]
               (when server-spring-root
                 [{:fx/type :button
                   :style-class ["button" "skylobby-normal"]
                   :text "Reset"
                   :on-action {:event/type :spring-lobby/dissoc-in
                               :path [:servers server-url :spring-isolation-dir]}
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-close:20"}}])
               [{:fx/type :button
                 :style-class ["button" "skylobby-normal"]
                 :text "Change"
                 :on-action {:event/type :spring-lobby/file-chooser-dir
                             :initial-dir (or server-spring-root spring-root)
                             :path [:servers server-url :spring-isolation-dir]}
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-file-find:20"}}])}]})]
       (when login-error
         [{:fx/type :label
           :style {:-fx-font-size 24}
           :text "Error:"}
          {:fx/type :text-area
           :editable false
           :text (str (string/trim login-error))
           :pref-row-count 3
           :style {:-fx-text-fill "red"}}])
       [{:fx/type :h-box
         :spacing 10
         :children
         (concat
           [{:fx/type :pane
             :h-box/hgrow :always}
            {:fx/type connect-button}]
           (when-not client-data
             [{:fx/type :button
               :text "Register"
               :on-action {:event/type :spring-lobby/toggle
                           :key :show-register-window}}
              {:fx/type :button
               :text "Reset Password"
               :on-action {:event/type :spring-lobby/toggle
                           :key :show-reset-password-window}}]))}]
      (let [auto-servers (fx/sub-ctx context skylobby.fx/auto-servers-sub)
            auto-servers-not-connected (fx/sub-ctx context skylobby.fx/auto-servers-not-connected-sub)]
        (when (seq auto-servers)
          [{:fx/type :button
            :text
            (if auto-connect-running
              "Connecting..."
              (if (empty? auto-servers-not-connected)
                "All auto-connect servers connected"
                (str "Connect to " (count auto-servers-not-connected) " auto-connect servers")))
            :disable (boolean (or auto-connect-running
                                  (empty? auto-servers-not-connected)))
            :on-action {:event/type :spring-lobby/add-task
                        :task {:spring-lobby/task-type :spring-lobby/auto-connect-servers}}}]))
      (when agreement
        [{:fx/type :label
          :style {:-fx-font-size 20}
          :text " Server agreement: "}
         {:fx/type :text-area
          :editable false
          :text (str agreement)}
         {:fx/type :h-box
          :style {:-fx-font-size 20}
          :children
          [{:fx/type :text-field
            :prompt-text "Email Verification Code"
            :text verification-code
            :on-text-changed {:event/type :spring-lobby/assoc
                              :key :verification-code}}
           {:fx/type :button
            :text "Confirm"
            :on-action {:event/type :spring-lobby/confirm-agreement
                        :client-data client-data
                        :server-key (u/server-key client-data)
                        :verification-code verification-code}}]}]))}))


(defn- welcome-view-impl
  [_state]
  {:fx/type :v-box
   :alignment :center
   :style {:-fx-font-size 20}
   :children
   [
    {:fx/type :pane
     :v-box/vgrow :always}
    {:fx/type :hyperlink
     :style {:-fx-font-size 24}
     :text (str "skylobby " u/app-version)
     :on-action {:event/type :spring-lobby/desktop-browse-url
                 :url "https://github.com/skynet-gh/skylobby/wiki/User-Guide"}}
    {:fx/type :pane
     :pref-height 20}
    {:fx/type :h-box
     :children
     [
      {:fx/type :pane
       :h-box/hgrow :always}
      {:fx/type :h-box
       :spacing 100
       :children
       [{:fx/type :pane
         :h-box/hgrow :always}
        {:fx/type singleplayer-buttons}
        {:fx/type multiplayer-buttons}
        {:fx/type :pane
         :h-box/hgrow :always}]}
      {:fx/type :pane
       :h-box/hgrow :always}]}
    {:fx/type :pane
     :v-box/vgrow :always}]})

(defn welcome-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :welcome-view
      (welcome-view-impl state))))
