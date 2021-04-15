(ns skylobby.fx.battles-buttons
  (:require
    clojure.set
    [clojure.string :as string]
    [skylobby.fx.engines :refer [engines-view]]
    [skylobby.fx.maps :refer [maps-view]]
    [skylobby.fx.mods :refer [mods-view]]
    [spring-lobby.fx.font-icon :as font-icon]))


(def matchmaking-compflag "matchmaking")


(def battles-buttons-keys
  [:accepted :battle :battle-password :battle-title :battles :client :compflags :engines :engine-filter
   :engine-version :map-input-prefix :map-name :maps :mod-filter :mod-name :mods
   :pop-out-battle :scripttags :selected-battle :singleplayer-battle :spring-isolation-dir :use-springlobby-modname])

(defn battles-buttons-view
  [{:keys [accepted battle battles battle-password battle-title client compflags engine-version mod-name map-name maps
           engines mods map-input-prefix engine-filter mod-filter pop-out-battle selected-battle
           spring-isolation-dir]
    :as state}]
  {:fx/type :v-box
   :alignment :top-left
   :children
   [{:fx/type :flow-pane
     :alignment :center-left
     :style {:-fx-font-size 16}
     :children
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
       :on-action {:event/type :toggle
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
        :icon-literal (str "mdi-download:16:white")}}
      {:fx/type engines-view
       :engine-filter engine-filter
       :engines engines
       :engine-version engine-version}
      {:fx/type mods-view
       :mod-filter mod-filter
       :mod-name mod-name
       :mods mods
       :spring-isolation-dir spring-isolation-dir}
      {:fx/type maps-view
       :map-name map-name
       :maps maps
       :map-input-prefix map-input-prefix
       :on-value-changed {:event/type :spring-lobby/assoc
                          :key :map-name}
       :spring-isolation-dir spring-isolation-dir}]}
    {:fx/type :h-box
     :style {:-fx-font-size 16}
     :alignment :center-left
     :children
     (concat
       (when (and accepted client (not battle))
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
                                    (select-keys state [:client :scripttags :use-springlobby-modname]))]
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
       (when battle
         [{:fx/type :button
           :text "Leave Battle"
           :on-action {:event/type :spring-lobby/leave-battle
                       :client client}}
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
       (when (and (not battle) selected-battle (-> battles (get selected-battle)))
         (let [needs-password (= "1" (-> battles (get selected-battle) :battle-passworded))]
           (concat
             [{:fx/type :button
               :text "Join Battle"
               :disable (boolean (and needs-password (string/blank? battle-password)))
               :on-action {:event/type :spring-lobby/join-battle
                           :battle-password battle-password
                           :client client
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
           :on-action {:event/type :spring-lobby/toggle
                       :key :show-matchmaking-window}}]))}]})
