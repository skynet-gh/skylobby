(ns skylobby.fx.host-battle
  (:require
    clojure.set
    skylobby.fx
    [skylobby.fx.engines :refer [engines-view]]
    [skylobby.fx.maps :refer [maps-view]]
    [skylobby.fx.mods :refer [mods-view]]
    [skylobby.resource :as resource]
    [spring-lobby.util :as u]))


(def host-battle-window-width 700)
(def host-battle-window-height 400)

(def host-battle-window-keys
  [:battle-password :battle-title :by-server :by-spring-root :css :engine-filter :engine-version
   :map-input-prefix :map-name :mod-filter :mod-name :selected-server-tab :servers
   :show-host-battle-window :spring-isolation-dir])

(defn host-battle-window
  [{:keys [battle-password battle-title by-server by-spring-root css engine-filter engine-version
           map-input-prefix map-name mod-filter mod-name screen-bounds selected-server-tab servers
           show-host-battle-window spring-isolation-dir] :as state}]
  (let [server-data (get by-server selected-server-tab)
        server-url (-> server-data :client-data :server-url)
        spring-root (or (-> servers (get server-url) :spring-isolation-dir)
                        spring-isolation-dir)
        {:keys [engines maps mods]} (resource/spring-root-resources spring-root by-spring-root)
        host-battle-state (-> state
                              (clojure.set/rename-keys {:battle-title :title})
                              (select-keys [:battle-password :title :engine-version
                                            :mod-name :map-name])
                              (assoc :mod-hash -1
                                     :map-hash -1))
        host-battle-action (merge
                             {:event/type :spring-lobby/host-battle
                              :host-battle-state host-battle-state}
                             (select-keys server-data [:client-data :scripttags :use-git-mod-version]))]
    {:fx/type :stage
     :showing (boolean show-host-battle-window)
     :title (str u/app-name " Host Battle")
     :icons skylobby.fx/icons
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-register-window}
     :width ((fnil min host-battle-window-width) (:width screen-bounds) host-battle-window-width)
     :height ((fnil min host-battle-window-height) (:height screen-bounds) host-battle-window-height)
     :scene
     {:fx/type :scene
      :stylesheets (skylobby.fx/stylesheet-urls css)
      :root
      (if show-host-battle-window
        {:fx/type :v-box
         :style {:-fx-font-size 16}
         :children
         [
          {:fx/type :label
           :text " Battle Name: "}
          {:fx/type :text-field
           :text (str battle-title)
           :prompt-text "Battle Title"
           :on-action host-battle-action
           :on-text-changed {:event/type :spring-lobby/battle-title-change}}
          {:fx/type :label
           :text " Battle Password: "}
          {:fx/type :text-field
           :text (str battle-password)
           :prompt-text "Battle Password"
           :on-action host-battle-action
           :on-text-changed {:event/type :spring-lobby/battle-password-change}}
          {:fx/type engines-view
           :engine-filter engine-filter
           :engines engines
           :engine-version engine-version
           :flow false}
          {:fx/type mods-view
           :flow false
           :mod-filter mod-filter
           :mod-name mod-name
           :mods mods
           :spring-isolation-dir spring-isolation-dir}
          {:fx/type maps-view
           :flow false
           :map-name map-name
           :maps maps
           :map-input-prefix map-input-prefix
           :on-value-changed {:event/type :spring-lobby/assoc
                              :key :map-name}
           :spring-isolation-dir spring-isolation-dir}
          {:fx/type :pane
           :v-box/vgrow :always}
          {:fx/type :button
           :text "Host Battle"
           :on-action host-battle-action}]}
        {:fx/type :pane})}}))
