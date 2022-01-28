(ns skylobby.fx.bottom-bar
  (:require
    [cljfx.api :as fx]
    [skylobby.fs :as fs]
    skylobby.fx
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def app-update-browseurl "https://github.com/skynet-gh/skylobby/releases")


(def icon-size 20)


(defn app-update-button
  [{:fx/keys [context]}]
  (let [app-update-available (fx/sub-val context :app-update-available)]
    (if app-update-available
      (let [{:keys [latest]} app-update-available
            color "gold"
            version latest
            url (str "https://github.com/skynet-gh/skylobby/releases/download/" version "/"
                     "skylobby-" version "-"
                     (cond
                       (fs/mac?)
                       "mac"
                       (fs/wsl-or-windows?)
                       "windows"
                       :else
                       "linux")
                     ".jar")
            installer-url (str "https://github.com/skynet-gh/skylobby/releases/download/" version "/"
                               "skylobby-" version "_"
                               (cond
                                 (fs/mac?)
                                 "mac.dmg"
                                 (fs/wsl-or-windows?)
                                 "windows.msi"
                                 :else
                                 "linux-amd64.deb"))
            download (get (fx/sub-val context :http-download) url)
            running (seq (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/download-app-update-and-restart))]
        {:fx/type :h-box
         :alignment :center-left
         :children
         [
          {:fx/type :button
           :text (if running
                   (str "Downloading update: " (u/download-progress download))
                   (str "Update to " latest))
           :disable (boolean running)
           :on-action
           (if (fs/windows?)
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
            :icon-literal (str "mdi-download:" icon-size ":black")}}
          {:fx/type :button
           :text ""
           :on-action {:event/type :spring-lobby/desktop-browse-url
                       :url app-update-browseurl}
           :style {:-fx-base color
                   :-fx-background color}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal (str "mdi-open-in-new:" icon-size ":black")}}
          {:fx/type :button
           :text ""
           :on-action {:event/type :spring-lobby/dissoc
                       :key :app-update-available}
           :style {:-fx-base color
                   :-fx-background color}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal (str "mdi-close:" icon-size ":black")}}]})
      {:fx/type :pane})))


(defn bottom-bar-impl
  [{:fx/keys [context]}]
  (let [media-player (fx/sub-val context :media-player)
        music-now-playing (fx/sub-val context :music-now-playing)
        music-paused (fx/sub-val context :music-paused)
        music-queue (fx/sub-val context :music-queue)
        music-volume (fx/sub-val context :music-volume)
        tasks-by-type (fx/sub-ctx context skylobby.fx/tasks-by-type-sub)
        server-key (fx/sub-ctx context skylobby.fx/selected-tab-server-key-sub)]
    {:fx/type :h-box
     :alignment :center-left
     :style {:-fx-font-size 14}
     :children
     (concat
       [{:fx/type app-update-button}
        (let [last-failed-message (fx/sub-val context get-in [:by-server server-key :last-failed-message])]
          {:fx/type :h-box
           :alignment :center-left
           :children
           (if last-failed-message
             [
              {:fx/type :button
               :style-class ["button" "skylobby-normal"]
               :on-action {:event/type :spring-lobby/dissoc-in
                           :path [:by-server server-key :last-failed-message]}
               :graphic
               {
                :fx/type font-icon/lifecycle
                :icon-literal (str "mdi-window-close:" icon-size)}}
              {:fx/type :label
               :text (str " " last-failed-message)
               :style {:-fx-text-fill "#FF0000"}}]
             [])})
        {:fx/type :pane
         :h-box/hgrow :always}]
       (when-let [rtt (fx/sub-val context get-in [:by-server server-key :rtt])]
         [{:fx/type :label
           :text (str "Ping: " rtt "ms")}
          {:fx/type :pane
           :style {:-fx-pref-width 80}}])
       (when (fx/sub-val context :music-dir)
         (if music-now-playing
           [{:fx/type :h-box
             :alignment :center-left
             :children
             [
              {:fx/type :label
               :text " Song: "}
              {:fx/type :label
               :text (str (fs/filename music-now-playing) " ")}
              {:fx/type :button
               :style-class ["button" "skylobby-normal"]
               :text ""
               :on-action {:event/type :spring-lobby/prev-music
                           :media-player media-player
                           :music-now-playing music-now-playing
                           :music-queue music-queue
                           :music-volume music-volume}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal (str "mdi-skip-previous:" icon-size)}}
              {:fx/type :button
               :style-class ["button" "skylobby-normal"]
               :text ""
               :on-action {:event/type :spring-lobby/stop-music
                           :media-player media-player}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal (str "mdi-stop:" icon-size)}}
              {:fx/type :button
               :style-class ["button" "skylobby-normal"]
               :text ""
               :on-action {:event/type :spring-lobby/toggle-music-play
                           :media-player media-player
                           :music-paused music-paused}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal
                (str
                  (if music-paused
                    "mdi-play:"
                    "mdi-pause:")
                  icon-size)}}
              {:fx/type :button
               :style-class ["button" "skylobby-normal"]
               :text ""
               :on-action {:event/type :spring-lobby/next-music
                           :media-player media-player
                           :music-now-playing music-now-playing
                           :music-queue music-queue
                           :music-volume music-volume}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal (str "mdi-skip-next:" icon-size)}}]}]
           (if (empty? music-queue)
             [{:fx/type :label
               :text " No music "}]
             [
              {:fx/type :label
               :text " No music playing "}
              {:fx/type :button
               :style-class ["button" "skylobby-normal"]
               :text ""
               :on-action {:event/type :spring-lobby/start-music
                           :music-queue music-queue
                           :music-volume music-volume}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal (str "mdi-play:" icon-size)}}])))
       [{:fx/type :pane
         :style {:-fx-pref-width "200px"}}
        {:fx/type :button
         :style-class ["button" "skylobby-normal"]
         :text "Replays"
         :style {:-fx-font-size 14}
         :on-action {:event/type :spring-lobby/toggle-window
                     :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                     :key :show-replays}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal (str "mdi-open-in-new:" icon-size)}}
        {:fx/type :button
         :style-class ["button" "skylobby-normal"]
         :style {:-fx-font-size 14}
         :text "Settings"
         :graphic {:fx/type font-icon/lifecycle
                   :icon-literal (str "mdi-settings:" icon-size)}
         :on-action {:event/type :spring-lobby/toggle-window
                     :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                     :key :show-settings-window}}
        {:fx/type :button
         :style-class ["button" "skylobby-normal"]
         :text (str (count tasks-by-type) " tasks")
         :on-action {:event/type :spring-lobby/toggle-window
                     :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                     :key :show-tasks-window}
         :graphic {:fx/type font-icon/lifecycle
                   :icon-literal (str "mdi-chip:" icon-size)}}])}))

(defn bottom-bar [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :bottom-bar
      (bottom-bar-impl state))))
