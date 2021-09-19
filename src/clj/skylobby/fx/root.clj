(ns skylobby.fx.root
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.chat :as fx.chat]
    [skylobby.fx.download :as fx.download]
    [skylobby.fx.host-battle :as fx.host-battle]
    [skylobby.fx.import :as fx.import]
    [skylobby.fx.main :as fx.main]
    [skylobby.fx.maps :as fx.maps]
    [skylobby.fx.rapid :as fx.rapid]
    [skylobby.fx.register :as fx.register]
    [skylobby.fx.replay :as fx.replay]
    [skylobby.fx.server :as fx.server]
    [skylobby.fx.settings :as fx.settings]
    [skylobby.fx.tasks :as fx.tasks]
    [skylobby.resource :as resource]
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(def app-version (u/app-version))
(def screen-bounds (skylobby.fx/screen-bounds))


(def battle-window-width 1740)
(def battle-window-height 800)

(def main-window-width 1920)
(def main-window-height 1280)


(defn root-view-impl
  [{{:keys [by-server by-spring-root css current-tasks leave-battle-on-close-window pop-out-battle pop-out-chat selected-server-tab servers
            spring-isolation-dir standalone tasks-by-kind window-maximized]
     :as state}
    :state}]
  (let [{:keys [width height]} screen-bounds
        all-tasks (->> tasks-by-kind
                       (mapcat second)
                       (concat (vals current-tasks))
                       (filter some?))
        tasks-by-type (group-by :spring-lobby/task-type all-tasks)
        {:keys [battle] :as server-data} (get by-server selected-server-tab)
        server-url (-> server-data :client-data :server-url)
        spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                        spring-isolation-dir)
        spring-root-data (get by-spring-root (fs/canonical-path spring-root))
        server-data (merge server-data
                      {:server-key selected-server-tab}
                      (resource/spring-root-resources spring-root by-spring-root))
        show-battle-window (boolean (and pop-out-battle battle))
        show-chat-window (boolean (and pop-out-chat battle))
        stylesheet-urls (skylobby.fx/stylesheet-urls css)]
    {:fx/type fx/ext-many
     :desc
     [
      {:fx/type :stage
       :showing true
       :title (str "skylobby " app-version)
       :icons skylobby.fx/icons
       :maximized (boolean window-maximized)
       :x 100
       :y 100
       :width (min main-window-width width)
       :height (min main-window-height height)
       :on-close-request {:event/type :spring-lobby/main-window-on-close-request
                          :standalone standalone}
       :scene
       {:fx/type :scene
        :stylesheets stylesheet-urls
        :root (merge
                {:fx/type fx.main/main-window}
                state
                {:tasks-by-type tasks-by-type})}}
      {:fx/type :stage
       :showing show-battle-window
       :title (str u/app-name " Battle")
       :icons skylobby.fx/icons
       :on-close-request (if leave-battle-on-close-window
                           {:event/type :spring-lobby/leave-battle
                            :client-data (:client-data server-data)}
                           {:event/type :spring-lobby/dissoc
                            :key :pop-out-battle})
       :width (min battle-window-width width)
       :height (min battle-window-height height)
       :scene
       {:fx/type :scene
        :stylesheets stylesheet-urls
        :root
        (if show-battle-window
          (merge
            {:fx/type fx.battle/battle-view
             :tasks-by-type tasks-by-type}
            (select-keys state fx.battle/battle-view-keys)
            server-data)
          {:fx/type :pane})}}
      (merge
        {:fx/type fx.download/download-window
         :screen-bounds screen-bounds}
        (select-keys state fx.download/download-window-keys))
      (merge
        {:fx/type fx.host-battle/host-battle-window
         :screen-bounds screen-bounds}
        (select-keys state fx.host-battle/host-battle-window-keys))
      (merge
        {:fx/type fx.import/import-window
         :screen-bounds screen-bounds
         :tasks-by-type tasks-by-type}
        (select-keys state fx.import/import-window-keys))
      (merge
        {:fx/type fx.maps/maps-window
         :screen-bounds screen-bounds}
        (select-keys state fx.maps/maps-window-keys)
        server-data)
      (merge
        {:fx/type fx.rapid/rapid-download-window
         :screen-bounds screen-bounds}
        (select-keys state fx.rapid/rapid-download-window-keys)
        spring-root-data)
      (merge
        {:fx/type fx.replay/replays-window
         :screen-bounds screen-bounds
         :tasks-by-type tasks-by-type}
        (select-keys state fx.replay/replays-window-keys))
      (merge
        {:fx/type fx.server/servers-window
         :screen-bounds screen-bounds}
        (select-keys state fx.server/servers-window-keys))
      (merge
        {:fx/type fx.register/register-window
         :screen-bounds screen-bounds}
        (select-keys state fx.register/register-window-keys))
      (merge
        {:fx/type fx.settings/settings-window
         :screen-bounds screen-bounds}
        (select-keys state fx.settings/settings-window-keys))
      (merge
        {:fx/type fx.tasks/tasks-window
         :screen-bounds screen-bounds}
        (select-keys state fx.tasks/tasks-window-keys))
      (merge
        (let [{:keys [battle battles]} server-data
              {:keys [battle-id]} battle
              {:keys [channel-name]} (get battles battle-id)
              channel-name (or channel-name
                               (str "__battle__" battle-id))]
          ; TODO dedupe with battle
          {:fx/type fx.chat/chat-window
           :channel-name channel-name
           :screen-bounds screen-bounds
           :show-chat-window show-chat-window})
        (select-keys state fx.chat/chat-window-keys)
        server-data)]}))

(defn root-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :root
      (root-view-impl state))))
