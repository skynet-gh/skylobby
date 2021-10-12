(ns skylobby.fx.bottom-bar
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [spring-lobby.fs :as fs]
    [spring-lobby.fx.font-icon :as font-icon]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(defn- bottom-bar-impl
  [{:fx/keys [context]}]
  (let [media-player (fx/sub-val context :media-player)
        music-now-playing (fx/sub-val context :music-now-playing)
        music-paused (fx/sub-val context :music-paused)
        music-queue (fx/sub-val context :music-queue)
        music-volume (fx/sub-val context :music-volume)
        tasks-by-type (fx/sub-ctx context skylobby.fx/tasks-by-type-sub)]
    {:fx/type :h-box
     :alignment :center-left
     :style {:-fx-font-size 14}
     :children
     (concat
       [{:fx/type :label
         :text (str " " (fx/sub-val context :last-failed-message))
         :style {:-fx-text-fill "#FF0000"}}
        {:fx/type :pane
         :h-box/hgrow :always}]
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
                :icon-literal "mdi-skip-previous:16"}}
              {:fx/type :button
               :style-class ["button" "skylobby-normal"]
               :text ""
               :on-action {:event/type :spring-lobby/stop-music
                           :media-player media-player}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal "mdi-stop:16"}}
              {:fx/type :button
               :style-class ["button" "skylobby-normal"]
               :text ""
               :on-action {:event/type :spring-lobby/toggle-music-play
                           :media-player media-player
                           :music-paused music-paused}
               :graphic
               {:fx/type font-icon/lifecycle
                :icon-literal
                (if music-paused
                  "mdi-play:16"
                  "mdi-pause:16")}}
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
                :icon-literal "mdi-skip-next:16"}}]}]
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
                :icon-literal "mdi-play:16"}}])))
       [{:fx/type :pane
         :style {:-fx-pref-width "200px"}}
        {:fx/type :button
         :style-class ["button" "skylobby-normal"]
         :text "Replays"
         :style {:-fx-font-size 14}
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-replays}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal "mdi-open-in-new:16"}}
        {:fx/type :button
         :style-class ["button" "skylobby-normal"]
         :style {:-fx-font-size 14}
         :text "Settings"
         :graphic {:fx/type font-icon/lifecycle
                   :icon-literal "mdi-settings:16"}
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-settings-window}}
        {:fx/type :button
         :style-class ["button" "skylobby-normal"]
         :text (str (count tasks-by-type) " tasks")
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-tasks-window}}])}))

(defn bottom-bar [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :bottom-bar
      (bottom-bar-impl state))))
