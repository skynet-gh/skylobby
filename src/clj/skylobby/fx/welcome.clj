(ns skylobby.fx.welcome
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.bottom-bar :as fx.bottom-bar]
    [skylobby.fx.server :as fx.server]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def app-update-browseurl "https://github.com/skynet-gh/skylobby/releases")


(defn connect-button [{:fx/keys [context]}]
  (let [{:keys [accepted client-data]} (fx/sub-ctx context skylobby.fx/selected-server-data-sub)
        {:keys [client client-deferred]} client-data
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
                 (if client-deferred
                   "Connecting..."
                   "Connect"))
         :disable (boolean
                    (or
                      (and client (not accepted))
                      (and
                        server
                        (not client)
                        client-deferred)
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
            :icon-literal "mdi-close-octagon:16:white"}}]))}))


(defn singleplayer-buttons
  [{:fx/keys [_context]}]
  {:fx/type :v-box
   :spacing 10
   :children
   [
    {:fx/type :label
     :text "Offline:"}
    {:fx/type :button
     :text "Singleplayer Battle"
     :on-action {:event/type :spring-lobby/start-singleplayer-battle}}
    {:fx/type :button
     :text "Watch Replays"
     :on-action {:event/type :spring-lobby/toggle
                 :key :show-replays}}
    {:fx/type :pane
     :v-box/vgrow :always}]})


(defn multiplayer-buttons
  [{:fx/keys [context]}]
  (let [server-key (fx/sub-ctx context skylobby.fx/welcome-server-key-sub)
        agreement (fx/sub-val context get-in [:by-server server-key :agreement])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        login-error (fx/sub-val context get-in [:by-server server-key :login-error])
        server (fx/sub-val context :server)
        servers (fx/sub-val context :servers)
        password (fx/sub-val context :password)
        username (fx/sub-val context :username)
        verification-code (fx/sub-val context :verification-code)
        tasks-by-type (fx/sub-ctx context skylobby.fx/tasks-by-type-sub)
        auto-connect-running (seq (get tasks-by-type :spring-lobby/auto-connect-servers))]
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
           :text ""
           :on-action {:event/type :spring-lobby/toggle
                       :key :show-servers-window}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal
            (if server
              "mdi-wrench:30:white"
              "mdi-plus:30:white")}}]}]
       (when-let [login-error (str " " (get login-error (first server)))]
         [{:fx/type :label
           :text (str " " login-error)
           :style {:-fx-text-fill "#FF0000"
                   :-fx-max-width "360px"}}])
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
              spring-isolation-dir (:spring-isolation-dir server-details)]
          {:fx/type :v-box
           :style {:-fx-font-size 16}
           :children
           [
            {:fx/type :label
             :text " Server-specific Spring Dir"
             :style {:-fx-font-size 20}}
            {:fx/type :h-box
             :alignment :center-left
             :children
             (concat
               [{:fx/type :text-field
                 :disable true
                 :text (str (or spring-isolation-dir
                                " < use default >"))
                 :style {:-fx-min-width 400}}]
               (when spring-isolation-dir
                 [{:fx/type :button
                   :text ""
                   :on-action {:event/type :spring-lobby/dissoc-in
                               :path [:servers server-url :spring-isolation-dir]}
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-close:16:white"}}])
               [{:fx/type :button
                 :on-action {:event/type :spring-lobby/file-chooser-spring-root
                             :target [:servers server-url :spring-isolation-dir]}
                 :text ""
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal "mdi-file-find:16:white"}}])}]})
        {:fx/type :h-box
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
                           :key :show-register-window}}]))}]
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
  [{:fx/keys [context]}]
  (let [show-local (and (fx/sub-val context get-in [:by-server :local :battle :battle-id])
                        (not (fx/sub-val context :pop-out-battle)))]
    {:fx/type :v-box
     :alignment :center
     :style {:-fx-font-size 20}
     :children
     (concat
       (when-not show-local
         [{:fx/type :pane
           :v-box/vgrow :always}])
       [
        (if show-local
          {:fx/type :split-pane
           :divider-positions [0]
           :orientation :vertical
           :v-box/vgrow :always
           :items
           [{:fx/type :v-box
             :children
             [
              {:fx/type :pane
               :v-box/vgrow :always}
              {:fx/type :h-box
               :children
               [
                {:fx/type :pane
                 :h-box/hgrow :always}
                {:fx/type singleplayer-buttons}
                {:fx/type multiplayer-buttons}
                {:fx/type :pane
                 :h-box/hgrow :always}]}
              {:fx/type :pane
               :v-box/vgrow :always}]}
            {:fx/type :v-box
             :children
             [{:fx/type :h-box
               :alignment :center-left
               :children
               [{:fx/type :button
                 :text "Close Singleplayer Battle"
                 :on-action {:event/type :spring-lobby/dissoc-in
                             :path [:by-server :local :battle]}}]}
              {:fx/type fx.battle/battle-view
               :v-box/vgrow :always
               :server-key :local}]}]}
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
             :h-box/hgrow :always}]})]
       (when-not show-local
         [{:fx/type :pane
           :v-box/vgrow :always}])
       [{:fx/type fx.bottom-bar/bottom-bar}])}))

(defn welcome-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :welcome-view
      (welcome-view-impl state))))
