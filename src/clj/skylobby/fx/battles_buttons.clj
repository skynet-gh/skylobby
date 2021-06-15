(ns skylobby.fx.battles-buttons
  (:require
    clojure.set
    [clojure.string :as string]
    [skylobby.fx.engines :refer [engines-view]]
    [skylobby.fx.maps :refer [maps-view]]
    [skylobby.fx.mods :refer [mods-view]]
    [skylobby.fx.welcome :refer [app-update-button]]
    [spring-lobby.fx.font-icon :as font-icon]))


(def matchmaking-compflag "matchmaking")


(def battles-buttons-state-keys
  [:app-update-available :battle-password :battle-title :engines :engine-filter :engine-version
   :http-download :map-input-prefix
   :map-name :maps :mod-filter :mod-name :mods :pop-out-battle :spring-isolation-dir :tasks-by-type
   :use-springlobby-modname])

(def battles-buttons-keys
  [:accepted :battle :battles :client-data :compflags :scripttags :selected-battle :server-key])

(defn battles-buttons-view
  [{:keys [accepted app-update-available battle battles battle-password battle-title client-data compflags engine-version
           engines mod-name map-name maps mods map-input-prefix engine-filter mod-filter
           pop-out-battle selected-battle server-key spring-isolation-dir]
    :as state}]
  {:fx/type :v-box
   :alignment :top-left
   :children
   [{:fx/type :flow-pane
     :alignment :center-left
     :style {:-fx-font-size 16}
     :children
     (concat
       [
        {:fx/type :button
         :text "Settings"
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-settings-window}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal "mdi-settings:16:white"}}
        {:fx/type :button
         :text "Replays"
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-replays}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal "mdi-open-in-new:16:white"}}
        {:fx/type :label
         :text " Resources: "}
        {:fx/type :button
         :text "Import"
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-importer}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal (str "mdi-file-import:16:white")}}
        {:fx/type :button
         :text "HTTP"
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-downloader}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal (str "mdi-download:16:white")}}
        {:fx/type :button
         :text "Rapid"
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-rapid-downloader}
         :graphic
         {:fx/type font-icon/lifecycle
          :icon-literal (str "mdi-download:16:white")}}]
       (when app-update-available
         [(merge
            {:fx/type app-update-button}
            state)])
       (when-not battle
         [
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
           :spring-isolation-dir spring-isolation-dir}]))}
    {:fx/type :h-box
     :style {:-fx-font-size 16}
     :alignment :center-left
     :children
     (concat
       (when (and accepted client-data (not battle))
         (let [host-battle-state
               (-> state
                   (clojure.set/rename-keys {:battle-title :title})
                   (select-keys [:battle-password :title :engine-version
                                 :mod-name :map-name])
                   (assoc :mod-hash -1
                          :map-hash -1))
               host-battle-action (merge
                                    {:event/type :spring-lobby/host-battle
                                     :host-battle-state host-battle-state}
                                    (select-keys state [:client-data :scripttags :use-springlobby-modname]))]
           [{:fx/type :button
             :text "Host Battle"
             :disable (boolean
                        (or (string/blank? engine-version)
                            (string/blank? map-name)
                            (string/blank? mod-name)
                            (string/blank? battle-title)))
             :on-action host-battle-action}
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
            #_
            {:fx/type fx.ext.node/with-tooltip-props
             :props
             {:tooltip
              {:fx/type :tooltip
               :show-delay [10 :ms]
               :style {:-fx-font-size 14}
               :text "If using git, set version to $VERSION so SpringLobby is happier"}}
             :desc
             {:fx/type :h-box
              :alignment :center-left
              :children
              [{:fx/type :check-box
                :selected (boolean use-springlobby-modname)
                :h-box/margin 8
                :on-selected-changed {:event/type :spring-lobby/use-springlobby-modname-change}}
               {:fx/type :label
                :text "Use SpringLobby Game Name"}]}}])))}
    {:fx/type :h-box
     :alignment :center-left
     :style {:-fx-font-size 16}
     :children
     (concat
       (when (seq battle)
         [{:fx/type :button
           :text "Leave Battle"
           :on-action {:event/type :spring-lobby/leave-battle
                       :client-data client-data
                       :server-key server-key}}
          {:fx/type :pane
           :h-box/margin 8}
          (if pop-out-battle
            {:fx/type :button
             :text "Pop In Battle "
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-window-maximize:16:white"}
             :on-action {:event/type :spring-lobby/dissoc
                         :key :pop-out-battle}}
            {:fx/type :button
             :text "Pop Out Battle "
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal "mdi-open-in-new:16:white"}
             :on-action {:event/type :spring-lobby/assoc
                         :key :pop-out-battle
                         :value true}})])
       (when (and (empty? battle)
                  selected-battle
                  (-> battles (get selected-battle)))
         (let [needs-password (= "1" (-> battles (get selected-battle) :battle-passworded))]
           (concat
             [{:fx/type :button
               :text "Join Battle"
               :disable (boolean (and needs-password (string/blank? battle-password)))
               :on-action {:event/type :spring-lobby/join-battle
                           :battle-password battle-password
                           :client-data client-data
                           :selected-battle selected-battle
                           :battle-passworded
                           (= "1" (-> battles (get selected-battle) :battle-passworded))}}] ; TODO
             (when needs-password
               [{:fx/type :label
                 :text " Battle Password: "}
                {:fx/type :text-field
                 :text (str battle-password)
                 :prompt-text "Battle Password"
                 :on-action {:event/type :spring-lobby/host-battle}
                 :on-text-changed {:event/type :spring-lobby/battle-password-change}}]))))
       (when (contains? (set compflags) matchmaking-compflag)
         [{:fx/type :button
           :text "Matchmaking"
           :on-action {:event/type :spring-lobby/assoc
                       :key :selected-tab-main
                       :value "matchmaking"}}]))}]})
