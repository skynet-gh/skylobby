(ns skylobby.fx.bottom-bar
  (:require
    [spring-lobby.fs :as fs]
    [spring-lobby.fx.font-icon :as font-icon]))


(def bottom-bar-keys
  [:last-failed-message :media-player :music-dir :music-now-playing :music-paused :music-queue
   :music-volume :tasks-by-type])

(defn bottom-bar
  [{:keys [last-failed-message media-player music-dir music-now-playing music-paused music-queue
           music-volume tasks-by-type]}]
  {:fx/type :h-box
   :alignment :center-left
   :style {:-fx-font-size 14}
   :children
   (concat
     [{:fx/type :label
       :text (str " " last-failed-message)
       :style {:-fx-text-fill "#FF0000"}}
      {:fx/type :pane
       :h-box/hgrow :always}]
     (when music-dir
       (if music-now-playing
         [{:fx/type :h-box
           :alignment :center-left
           :children
           [
            {:fx/type :label
             :text " Song: "}
            {:fx/type :label
             :text (str (fs/filename music-now-playing))}
            {:fx/type :button
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
             :text ""
             :on-action {:event/type :spring-lobby/stop-music
                         :media-player media-player}
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-stop:16"}}
            {:fx/type :button
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
       :text "Replays"
       :style {:-fx-font-size 14}
       :on-action {:event/type :spring-lobby/toggle
                   :key :show-replays}
       :graphic
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-open-in-new:16:white"}}
      {:fx/type :button
       :style {:-fx-font-size 14}
       :text "Settings"
       :graphic {:fx/type font-icon/lifecycle
                 :icon-literal "mdi-settings:16:white"}
       :on-action {:event/type :spring-lobby/toggle
                   :key :show-settings-window}}
      {:fx/type :button
       :text (str (count tasks-by-type) " tasks")
       :on-action {:event/type :spring-lobby/toggle
                   :key :show-tasks-window}}])})
