(ns skylobby.fx.welcome
  (:require
    [clojure.string :as string]
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.bottom-bar :as fx.bottom-bar]
    [skylobby.fx.server :as fx.server]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.fs :as fs]
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
       (when (and client-deferred (or (not client) (not accepted)))
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

(defn app-update-button [{:keys [app-update-available http-download tasks-by-type]}]
  (let [{:keys [latest]} app-update-available
        color "gold"
        version latest
        url (str "https://github.com/skynet-gh/skylobby/releases/download/" version "/"
                 "skylobby-" version "-"
                 (if (fs/wsl-or-windows?) ; TODO mac
                   "windows"
                   "linux")
                 ".jar")
        installer-url (str "https://github.com/skynet-gh/skylobby/releases/download/" version "/"
                           "skylobby-" version "_"
                           (if (fs/wsl-or-windows?) ; TODO mac
                             "windows.msi"
                             "linux-amd64.deb"))
        download (get http-download url)
        running (seq (get tasks-by-type :spring-lobby/download-app-update-and-restart))
        is-java (u/is-java? (u/process-command))]
    {:fx/type :h-box
     :alignment :center-left
     :children
     [
      {:fx/type :button
       :text (if running
               (u/download-progress download)
               (str "Update to " latest))
       :disable (boolean running)
       :on-action
       (if is-java
         {:event/type :spring-lobby/add-task
          :task {:spring-lobby/task-type :spring-lobby/download-app-update-and-restart
                 :downloadable {:download-url url}
                 :version version}}
         {:event/type :spring-lobby/desktop-browse-url
          :url installer-url})
       :style {:-fx-base color
               :-fx-background color}
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-open-in-new:30:black"}}
      {:fx/type :button
       :text ""
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
        :icon-literal "mdi-close:30:black"}}]}))

(defn singleplayer-buttons
  [{:keys [app-update-available spring-isolation-dir] :as state}]
  {:fx/type :v-box
   :children
   (concat
     (when app-update-available
       [(merge
          {:fx/type app-update-button}
          state)])
     [
      {:fx/type :v-box
       :style {:-fx-font-size 16}
       :children
       [
        {:fx/type :label
         :text " Default Spring Dir"
         :style {:-fx-font-size 20}}
        {:fx/type :h-box
         :alignment :center-left
         :children
         [
          {:fx/type :text-field
           :disable true
           :text (str spring-isolation-dir)
           :style {:-fx-min-width 400}}
          {:fx/type :button
           :on-action {:event/type :spring-lobby/file-chooser-spring-root}
           :text ""
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-file-find:16:white"}}]}]}
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
                   :key :show-settings-window}}])})

(defn multiplayer-buttons
  [{:keys [by-server client-data login-error logins password server servers username]
    :as state}]
  {:fx/type :v-box
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
          :icon-literal "mdi-plus:30:white"}}]}
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
             [
              {:fx/type :text-field
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
                :icon-literal "mdi-file-find:16:white"}}])}]})]
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
     [{:fx/type :h-box
       :children
       (concat
         [(merge
            {:fx/type connect-button
             :server-key (u/server-key client-data)}
            (select-keys state connect-button-keys))
          {:fx/type :pane
           :h-box/hgrow :always}]
         (when-not client-data
           [{:fx/type :button
             :text "Register"
             :on-action {:event/type :spring-lobby/toggle
                         :key :show-register-window}}]))}]
     (let [auto-servers (->> servers
                             (filter (comp :auto-connect second)))
           auto-servers-not-connected (->> auto-servers
                                           (map (fn [[server-url _server-data]]
                                                  (u/server-key
                                                    {:server-url server-url
                                                     :username (-> logins (get server-url) :username)})))
                                           (filter some?)
                                           (remove (fn [server-key] (contains? by-server server-key))))]
       (when (seq auto-servers)
         [{:fx/type :button
           :text (if (empty? auto-servers-not-connected)
                   "All auto-connect servers connected"
                   (str "Connect to " (count auto-servers-not-connected) " auto-connect servers"))
           :disable (empty? auto-servers-not-connected)
           :on-action {:event/type :spring-lobby/auto-connect-servers}}])))})


(def welcome-view-keys
  (concat
    fx.battle/battle-view-keys
    fx.bottom-bar/bottom-bar-keys
    [:app-update-available :by-spring-root :by-server :client-data :http-download
     :login-error :logins :password
     :server :servers :spring-isolation-dir :tasks-by-type :username]))

(defn welcome-view
  [{:keys [by-spring-root by-server spring-isolation-dir]
    :as state}]
  {:fx/type :v-box
   :alignment :center
   :style {:-fx-font-size 20}
   :children
   (concat
     [
      {:fx/type :pane
       :v-box/vgrow :always}
      (if (-> by-server :local :battle :battle-id)
        {:fx/type :v-box
         :children
         [{:fx/type :h-box
           :children
           [
            {:fx/type :pane
             :h-box/hgrow :always}
            (merge
              {:fx/type singleplayer-buttons}
              state)
            (merge
              {:fx/type multiplayer-buttons}
              state)
            {:fx/type :pane
             :h-box/hgrow :always}]}
          {:fx/type :v-box
           :children
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
              (:local by-server)
              (get by-spring-root (fs/canonical-path spring-isolation-dir)))]}]}
        {:fx/type :h-box
         :children
         [
          {:fx/type :pane
           :h-box/hgrow :always}
          {:fx/type :v-box
           :children
           [{:fx/type :pane
             :v-box/vgrow :always}
            (merge
              {:fx/type singleplayer-buttons}
              state)
            (merge
              {:fx/type multiplayer-buttons}
              state)
            {:fx/type :pane
             :v-box/vgrow :always}]}
          {:fx/type :pane
           :h-box/hgrow :always}]})]
     (when-not (-> by-server :local :battle :battle-id)
       [{:fx/type :pane
         :v-box/vgrow :always}])
     [(merge
        {:fx/type fx.bottom-bar/bottom-bar}
        (select-keys state fx.bottom-bar/bottom-bar-keys))])})
