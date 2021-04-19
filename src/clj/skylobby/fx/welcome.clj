(ns skylobby.fx.welcome
  (:require
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.server :as fx.server]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]))


(def app-update-browseurl "https://github.com/skynet-gh/skylobby/releases")


(def connect-button-keys
  [:accepted :client-data :client-deferred :password :server :username])

(defn connect-button [{:keys [accepted client-data password server username]}]
  (let [{:keys [client client-deferred]} client-data
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
                        client-deferred)))
         :on-action (if client
                      {:event/type :spring-lobby/disconnect
                       :server-key server-key}
                      {:event/type :spring-lobby/connect
                       :server server
                       :server-key server-key
                       :password password
                       :username username})}]
       (when (and client-deferred (not client))
         [{:fx/type :button
           :text ""
           :tooltip
           {:fx/type :tooltip
            :show-delay [10 :ms]
            :style {:-fx-font-size 14}
            :text "Cancel connect"}
           :on-action {:event/type :spring-lobby/cancel-connect
                       :client-deferred client-deferred
                       :client client
                       :server-key server-key}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-close-octagon:16:white"}}]))}))

(def welcome-view-keys
  (concat
    fx.battle/battle-view-keys
    [:app-update-available :by-server :client-data :map-details :mod-details :password :server :servers :tasks-by-type :username]))

(defn welcome-view
  [{:keys [app-update-available by-server client-data password server servers tasks-by-type username]
    :as state}]
  {:fx/type :v-box
   :alignment :center
   :style {:-fx-font-size 20}
   :children
   (concat
     [{:fx/type :pane
       :v-box/vgrow :always}
      {:fx/type :h-box
       :alignment :center
       :children
       [
        {:fx/type :v-box
         :alignment :center-left
         :children
         (concat
           (when-let [{:keys [latest]} app-update-available]
             (let [color "gold"]
               [{:fx/type :h-box
                 :alignment :center-left
                 :children
                 [
                  {:fx/type :button
                   :text (str "Update to " latest)
                   :on-action {:event/type :spring-lobby/desktop-browse-url
                               :url app-update-browseurl}
                   :style {:-fx-base color
                           :-fx-background color}
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-open-in-new:30:black"}}
                  {:fx/type :button
                   :text ""
                   :on-action {:event/type :spring-lobby/dissoc
                               :key :app-update-available}
                   :style {:-fx-base color
                           :-fx-background color}
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-close:30:black"}}]}]))
           [
            {:fx/type :button
             :text "Singleplayer Battle"
             :on-action {:event/type :spring-lobby/start-singleplayer-battle}}
            {:fx/type :button
             :text "Watch Replays"
             :on-action {:event/type :spring-lobby/toggle
                         :key :show-replays}}
            {:fx/type :button
             :text "Settings"
             :on-action {:event/type :spring-lobby/toggle
                         :key :show-settings-window}}
            {:fx/type :label
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
                :icon-literal "mdi-plus:30:white"}}]}]
           (if-not client-data
             [{:fx/type :button
               :text "Register"
               :on-action {:event/type :spring-lobby/toggle
                           :key :show-register-window}}]
             [{:fx/type :label
               :text (str " " (-> by-server (get (first server)) :login-error))
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
               :disable (boolean (or client-data (not server)))
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
                           :value (first server)}}])
           [(merge
              {:fx/type connect-button
               :server-key (u/server-key client-data)}
              (select-keys state connect-button-keys))])}]}]
     [{:fx/type :pane
       :v-box/vgrow :always}]
     (when (-> by-server :local :battle :battle-id)
       [{:fx/type :h-box
         :alignment :center-left
         :children
         [{:fx/type :button
           :text "Close Singleplayer Battle"
           :on-action {:event/type :spring-lobby/dissoc-in
                       :path [:by-server :local :battle]}}]}
        (merge
          {:fx/type fx.battle/battle-view}
          (select-keys state fx.battle/battle-view-keys)
          (:local by-server))])
     [{:fx/type :h-box
       :alignment :center-left
       :style {:-fx-font-size 14}
       :children
       [{:fx/type :pane
         :h-box/hgrow :always}
        {:fx/type :button
         :text (str (count tasks-by-type) " tasks")
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-tasks-window}}]}])})
