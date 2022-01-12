(ns skylobby.fx.root
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.battle :as fx.battle]
    [skylobby.fx.battle-chat :as fx.battle-chat]
    [skylobby.fx.battles-window :as fx.battles-window]
    [skylobby.fx.chat :as fx.chat]
    [skylobby.fx.download :as fx.download]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.host-battle :as fx.host-battle]
    [skylobby.fx.import :as fx.import]
    [skylobby.fx.main :as fx.main]
    [skylobby.fx.maps :as fx.maps]
    [skylobby.fx.players-table :as fx.players-table]
    [skylobby.fx.rapid :as fx.rapid]
    [skylobby.fx.register :as fx.register]
    [skylobby.fx.reset-password :as fx.reset-password]
    [skylobby.fx.replay :as fx.replay]
    [skylobby.fx.scenarios :as fx.scenarios]
    [skylobby.fx.server :as fx.server]
    [skylobby.fx.settings :as fx.settings]
    [skylobby.fx.spring-info :as fx.spring-info]
    [skylobby.fx.tasks :as fx.tasks]
    [skylobby.util :as u]
    spring-lobby
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def screen-bounds (skylobby.fx/get-screen-bounds))


(def battle-window-width 1740)
(def battle-window-height 800)

(def main-window-width 1920)
(def main-window-height 1280)


(defn battle-window [{:fx/keys [context]}]
  (let [
        pop-out-battle (fx/sub-val context :pop-out-battle)
        window-states (fx/sub-val context :window-states)
        server-key (fx/sub-ctx context skylobby.fx/selected-tab-server-key-sub)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        show-battle-window (boolean (and pop-out-battle battle-id (not= :local server-key)))]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (skylobby.fx/add-maximized-listener :battle node)
                   (reset! spring-lobby/main-stage-atom node))
     :desc
     {:fx/type :stage
      :showing show-battle-window
      :title (str u/app-name " Battle")
      :icons skylobby.fx/icons
      :on-close-request (if (fx/sub-val context :leave-battle-on-close-window)
                          {:event/type :spring-lobby/leave-battle
                           :client-data client-data
                           :server-key server-key}
                          {:event/type :spring-lobby/dissoc
                           :key :pop-out-battle})
      :maximized (get-in window-states [:battle :maximzed] false)
      :x (skylobby.fx/fitx screen-bounds (get-in window-states [:battle :x]))
      :y (skylobby.fx/fity screen-bounds (get-in window-states [:battle :y]))
      :width (skylobby.fx/fitwidth screen-bounds (get-in window-states [:battle :width]) battle-window-width)
      :height (skylobby.fx/fitheight screen-bounds (get-in window-states [:battle :height]) battle-window-height)
      :on-width-changed (partial skylobby.fx/window-changed :battle :width)
      :on-height-changed (partial skylobby.fx/window-changed :battle :height)
      :on-x-changed (partial skylobby.fx/window-changed :battle :x)
      :on-y-changed (partial skylobby.fx/window-changed :battle :y)
      :scene
      {:fx/type :scene
       :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
       :root
       (if show-battle-window
         {:fx/type ext-recreate-on-key-changed
          :key server-key
          :desc {:fx/type fx.battle/battle-view
                 :server-key server-key}}
         {:fx/type :pane
          :pref-width battle-window-width
          :pref-height battle-window-height})}}}))


(defn root-view-impl
  [{:fx/keys [context]}]
  (let [window-maximized (fx/sub-val context :window-maximized)
        window-states (fx/sub-val context :window-states)]
    {:fx/type fx/ext-many
     :desc
     [{:fx/type fx/ext-on-instance-lifecycle
       :on-created (partial skylobby.fx/add-maximized-listener :main)
       :desc
       {:fx/type :stage
        :showing true
        :title (str "skylobby " u/app-version)
        :icons skylobby.fx/icons
        :maximized (boolean
                     (or window-maximized
                       (get-in window-states [:main :maximized] false)))
        :x (skylobby.fx/fitx screen-bounds (get-in window-states [:main :x]))
        :y (skylobby.fx/fity screen-bounds (get-in window-states [:main :y]))
        :width (skylobby.fx/fitwidth screen-bounds (get-in window-states [:main :width]) main-window-width)
        :height (skylobby.fx/fitheight screen-bounds (get-in window-states [:main :height]) main-window-height)
        :on-width-changed (partial skylobby.fx/window-changed :main :width)
        :on-height-changed (partial skylobby.fx/window-changed :main :height)
        :on-x-changed (partial skylobby.fx/window-changed :main :x)
        :on-y-changed (partial skylobby.fx/window-changed :main :y)
        :on-close-request {:event/type :spring-lobby/main-window-on-close-request
                           :standalone (fx/sub-val context :standalone)}
        :scene
        {:fx/type :scene
         :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
         :root {:fx/type fx.main/main-window}
         :on-key-pressed {:event/type :spring-lobby/main-window-key-pressed
                          :standalone (fx/sub-val context :standalone)}}}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :pop-out-battle))
       :desc
       {:fx/type battle-window}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-downloader))
       :desc
       {:fx/type fx.download/download-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-host-battle-window))
       :desc
       {:fx/type fx.host-battle/host-battle-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-importer))
       :desc
       {:fx/type fx.import/import-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-maps))
       :desc
       {:fx/type fx.maps/maps-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-rapid-downloader))
       :desc
       {:fx/type fx.rapid/rapid-download-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-replays))
       :desc
       {:fx/type fx.replay/replays-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-servers-window))
       :desc
       {:fx/type fx.server/servers-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-register-window))
       :desc
       {:fx/type fx.register/register-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-reset-password-window))
       :desc
       {:fx/type fx.reset-password/reset-password-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-settings-window))
       :desc
       {:fx/type fx.settings/settings-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-tasks-window))
       :desc
       {:fx/type fx.tasks/tasks-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :pop-out-chat))
       :desc
       {:fx/type fx.battle-chat/battle-chat-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-chat-window))
       :desc
       {:fx/type fx.chat/chat-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-battles-window))
       :desc
       {:fx/type fx.battles-window/battles-window-view
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-download-replays))
       :desc
       {:fx/type fx.replay/download-replays-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-report-user-window))
       :desc
       {:fx/type fx.players-table/report-user-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-ai-options-window))
       :desc
       {:fx/type fx.players-table/ai-options-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-scenarios-window))
       :desc
       {:fx/type fx.scenarios/scenarios-window
        :screen-bounds screen-bounds}}
      {:fx/type ext-recreate-on-key-changed
       :key (boolean (fx/sub-val context :show-spring-info-window))
       :desc
       {:fx/type fx.spring-info/spring-info-window
        :screen-bounds screen-bounds}}]}))


(defn root-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :root
      (root-view-impl state))))
