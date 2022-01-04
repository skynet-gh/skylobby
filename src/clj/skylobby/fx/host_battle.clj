(ns skylobby.fx.host-battle
  (:require
    [cljfx.api :as fx]
    clojure.set
    skylobby.fx
    [skylobby.fx.sub :as sub]
    [skylobby.fx.engines :refer [engines-view]]
    [skylobby.fx.maps :refer [maps-view]]
    [skylobby.fx.mods :refer [mods-view]]
    [skylobby.util :as u]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def host-battle-window-width 700)
(def host-battle-window-height 400)

(def host-battle-window-keys
  [:battle-password :battle-port :battle-title :by-server :by-spring-root :css :engine-filter :engine-version
   :map-input-prefix :map-name :mod-filter :mod-name :selected-server-tab :servers
   :show-host-battle-window :spring-isolation-dir])

(defn nat-cell [nat-type]
  {:text (str nat-type ": " (case (int nat-type)
                              0 "none"
                              1 "Hole punching"
                              2 "Fixed source ports"
                              nil))})

(defn host-battle-window-impl
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        battle-password (fx/sub-val context :battle-password)
        battle-port (fx/sub-val context :battle-port)
        battle-nat-type (or (fx/sub-val context :battle-nat-type) 0)
        battle-title (fx/sub-val context :battle-title)
        engine-filter (fx/sub-val context :engine-filter)
        engine-version (fx/sub-val context :engine-version)
        map-input-prefix (fx/sub-val context :map-input-prefix)
        map-name (fx/sub-val context :map-name)
        mod-filter (fx/sub-val context :mod-filter)
        mod-name (fx/sub-val context :mod-name)
        server-key (fx/sub-ctx context skylobby.fx/selected-tab-server-key-sub)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        scripttags (fx/sub-val context get-in [:by-server server-key :scripttags])
        show-host-battle-window (fx/sub-val context :show-host-battle-window)
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        {:keys [maps mods]} (fx/sub-ctx context sub/spring-resources spring-root)
        host-battle-action {:event/type :spring-lobby/host-battle
                            :host-battle-state
                            {:host-port battle-port
                             :title battle-title
                             :nat-type battle-nat-type
                             :battle-password battle-password
                             :engine-version engine-version
                             :map-name map-name
                             :mod-name mod-name
                             :mod-hash -1
                             :map-hash -1}
                            :client-data client-data
                            :scripttags scripttags
                            :use-git-mod-version (fx/sub-val context :use-git-mod-version)}]
    {:fx/type :stage
     :showing (boolean (and client-data show-host-battle-window))
     :title (str u/app-name " Host Battle")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-host-battle-window}
     :x (skylobby.fx/fitx screen-bounds)
     :y (skylobby.fx/fity screen-bounds)
     :width (skylobby.fx/fitwidth screen-bounds host-battle-window-width)
     :height (skylobby.fx/fitheight screen-bounds host-battle-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      (if show-host-battle-window
        {:fx/type :v-box
         :style {:-fx-font-size 16}
         :children
         [
          {:fx/type :label
           :text (str " Spring root for this server:  " spring-root)}
          {:fx/type :label
           :text " Battle Name: "}
          {:fx/type :text-field
           :text (str battle-title)
           :prompt-text "Battle Title"
           :on-action host-battle-action
           :on-text-changed {:event/type :spring-lobby/assoc
                             :key :battle-title}}
          {:fx/type :label
           :text " Battle Password: "}
          {:fx/type :text-field
           :text (str battle-password)
           :prompt-text "Battle Password"
           :on-action host-battle-action
           :on-text-changed {:event/type :spring-lobby/assoc
                             :key :battle-password}}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [
            {:fx/type :label
             :text " Port: "}
            {:fx/type :text-field
             :text (str battle-port)
             :prompt-text "8452"
             :on-text-changed {:event/type :spring-lobby/assoc
                               :key :battle-port}
             :text-formatter
             {:fx/type :text-formatter
              :value-converter :integer
              :value (int (or (u/to-number battle-port) 8452))}}]}
          {:fx/type :h-box
           :alignment :center-left
           :children
           [
            {:fx/type :label
             :text " NAT Type: "}
            {:fx/type :combo-box
             :value battle-nat-type
             :items [0 1 2]
             :on-value-changed {:event/type :spring-lobby/assoc
                                :key :battle-nat-type}
             :button-cell nat-cell
             :cell-factory
             {:fx/cell-type :list-cell
              :describe nat-cell}}]}
          {:fx/type engines-view
           :engine-filter engine-filter
           :engine-version engine-version
           :flow false
           :spring-isolation-dir spring-root}
          {:fx/type mods-view
           :flow false
           :mod-filter mod-filter
           :mod-name mod-name
           :mods mods
           :spring-isolation-dir spring-root}
          {:fx/type maps-view
           :flow false
           :map-name map-name
           :maps maps
           :map-input-prefix map-input-prefix
           :on-value-changed {:event/type :spring-lobby/assoc
                              :key :map-name}
           :spring-isolation-dir spring-root}
          {:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :button
           :text "Host Battle"
           :on-action host-battle-action}]}
        {:fx/type :pane})}}))

(defn host-battle-window [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :host-battle-window
      (host-battle-window-impl state))))
