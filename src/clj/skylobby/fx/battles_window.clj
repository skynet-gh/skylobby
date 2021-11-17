(ns skylobby.fx.battles-window
  (:require
    [cljfx.api :as fx]
    skylobby.fx
    [skylobby.fx.battles-table :as fx.battles-table]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def window-key :battles)

(def battles-window-width 1600)
(def battles-window-height 1000)


(defn- battles-window-view-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        window-states (fx/sub-val context :window-states)
        show-battles-window (fx/sub-val context :show-battles-window)
        server-key (fx/sub-ctx context skylobby.fx/selected-tab-server-key-sub)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        show (and show-battles-window client-data)]
    {:fx/type :stage
     :showing show
     :title (str u/app-name " Battles " server-key)
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-battles-window}
     :x (skylobby.fx/fitx screen-bounds (get-in window-states [window-key :x]))
     :y (skylobby.fx/fity screen-bounds (get-in window-states [window-key :y]))
     :width (skylobby.fx/fitwidth screen-bounds (get-in window-states [window-key :width]) battles-window-width)
     :height (skylobby.fx/fitheight screen-bounds (get-in window-states [window-key :height]) battles-window-height)
     :on-width-changed (partial skylobby.fx/window-changed window-key :width)
     :on-height-changed (partial skylobby.fx/window-changed window-key :height)
     :on-x-changed (partial skylobby.fx/window-changed window-key :x)
     :on-y-changed (partial skylobby.fx/window-changed window-key :y)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show
        {:fx/type fx.battles-table/battles-table
         :v-box/vgrow :always
         :server-key (u/server-key client-data)}
        {:fx/type :pane
         :pref-width battles-window-width
         :pref-height battles-window-height})}}))

(defn battles-window-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battles-window
      (battles-window-view-impl state))))
