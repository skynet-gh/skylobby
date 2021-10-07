(ns skylobby.fx.battles-window
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.battles-table :as fx.battles-table]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
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
        (let [
              filter-battles (fx/sub-val context :filter-battles)
              battles (fx/sub-val context get-in [:by-server server-key :battles])]
          {:fx/type :v-box
           :children
           [{:fx/type :h-box
             :alignment :center-left
             :children
             (concat
               [
                {:fx/type :label
                 :text (str "Battles (" (count battles) ")")}
                {:fx/type :pane
                 :h-box/hgrow :always}
                {:fx/type :label
                 :text (str " Filter: ")}
                {:fx/type :text-field
                 :text (str filter-battles)
                 :on-text-changed {:event/type :spring-lobby/assoc
                                   :key :filter-battles}}]
               (when-not (string/blank? filter-battles)
                 [{:fx/type :button
                   :on-action {:event/type :spring-lobby/dissoc
                               :key :filter-battles}
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-close:16:white"}}]))}
            {:fx/type fx.battles-table/battles-table
             :v-box/vgrow :always
             :server-key (u/server-key client-data)}]})
        {:fx/type :pane
         :pref-width battles-window-width
         :pref-height battles-window-height})}}))

(defn battles-window-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battles-window
      (battles-window-view-impl state))))
