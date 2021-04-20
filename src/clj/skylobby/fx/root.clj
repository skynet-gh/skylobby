(ns skylobby.fx.root
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.download :as fx.download]
    [skylobby.fx.import :as fx.import]
    [skylobby.fx.main :as fx.main]
    [skylobby.fx.maps :as fx.maps]
    [skylobby.fx.matchmaking :as fx.matchmaking]
    [skylobby.fx.rapid :as fx.rapid]
    [skylobby.fx.register :as fx.register]
    [skylobby.fx.replay :as fx.replay]
    [skylobby.fx.server :as fx.server]
    [skylobby.fx.settings :as fx.settings]
    [skylobby.fx.tasks :as fx.tasks]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u])
  (:import
    (javafx.stage Screen)))


(def app-version (u/app-version))


(def battle-window-width 1740)
(def battle-window-height 800)

(def main-window-width 1920)
(def main-window-height 1200)


(defn- screen-bounds []
  (let [screen (Screen/getPrimary)
        bounds (.getVisualBounds screen)]
    {:min-x (.getMinX bounds)
     :min-y (.getMinY bounds)
     :width (.getWidth bounds)
     :height (.getHeight bounds)}))

(defn root-view
  [{{:keys [by-server by-spring-root current-tasks pop-out-battle selected-server-tab servers
            spring-isolation-dir standalone tasks-by-kind]
     :as state}
    :state}]
  (let [{:keys [width height] :as screen-bounds} (screen-bounds)
        all-tasks (->> tasks-by-kind
                       (mapcat second)
                       (concat (vals current-tasks))
                       (filter some?))
        tasks-by-type (group-by :spring-lobby/task-type all-tasks)]
    {:fx/type fx/ext-many
     :desc
     [
      {:fx/type :stage
       :showing true
       :title (str "skylobby " app-version)
       :icons skylobby.fx/icons
       :x 100
       :y 100
       :width (min main-window-width width)
       :height (min main-window-height height)
       :on-close-request {:event/type :spring-lobby/main-window-on-close-request
                          :standalone standalone}
       :scene
       {:fx/type :scene
        :stylesheets skylobby.fx/stylesheets
        :root (merge
                {:fx/type fx.main/main-window}
                state
                {:tasks-by-type tasks-by-type})}}
      (let [battle (-> by-server (get selected-server-tab) :battle)
            show-battle-window (boolean (and pop-out-battle battle))]
        {:fx/type :stage
         :showing show-battle-window
         :title (str u/app-name " Battle")
         :icons skylobby.fx/icons
         :on-close-request {:event/type :spring-lobby/dissoc
                            :key :pop-out-battle}
         :width (min battle-window-width width)
         :height (min battle-window-height height)
         :scene
         {:fx/type :scene
          :stylesheets skylobby.fx/stylesheets
          :root
          (if show-battle-window
            (merge
              {:fx/type fx.battle/battle-view
               :tasks-by-type tasks-by-type}
              (select-keys state fx.battle/battle-view-keys)
              (let [server-data (get by-server selected-server-tab)
                    server-url (-> server-data :client-data :server-url)
                    spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                                    spring-isolation-dir)
                    spring-root-data (get by-spring-root (fs/canonical-path spring-root))]
                ; TODO remove duplication with main-window
                (assoc server-data
                  :spring-isolation-dir spring-root
                  :engines (:engines spring-root-data)
                  :maps (:maps spring-root-data)
                  :mods (:mods spring-root-data))))
            {:fx/type :pane})}})
      (merge
        {:fx/type fx.download/download-window
         :screen-bounds screen-bounds}
        (select-keys state fx.download/download-window-keys))
      (merge
        {:fx/type fx.import/import-window
         :screen-bounds screen-bounds
         :tasks-by-type tasks-by-type}
        (select-keys state fx.import/import-window-keys))
      (merge
        {:fx/type fx.maps/maps-window
         :screen-bounds screen-bounds}
        (select-keys state fx.maps/maps-window-keys))
      (merge
        {:fx/type fx.rapid/rapid-download-window
         :screen-bounds screen-bounds}
        (select-keys state fx.rapid/rapid-download-window-keys))
      (merge
        {:fx/type fx.replay/replays-window
         :screen-bounds screen-bounds
         :tasks-by-type tasks-by-type}
        (select-keys state fx.replay/replays-window-keys))
      (merge
        {:fx/type fx.matchmaking/matchmaking-window}
        (select-keys state fx.matchmaking/matchmaking-window-keys))
      (merge
        {:fx/type fx.server/servers-window}
        (select-keys state fx.server/servers-window-keys))
      (merge
        {:fx/type fx.register/register-window}
        (select-keys state fx.register/register-window-keys))
      (merge
        {:fx/type fx.settings/settings-window}
        (select-keys state fx.settings/settings-window-keys))
      (merge
        {:fx/type fx.tasks/tasks-window
         :screen-bounds screen-bounds}
        (select-keys state fx.tasks/tasks-window-keys))]}))
