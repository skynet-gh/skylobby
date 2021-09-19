(ns skylobby.fx.battles-buttons
  (:require
    clojure.set
    [clojure.string :as string]
    [skylobby.fx.welcome :refer [app-update-button]]
    [spring-lobby.fx.font-icon :as font-icon]))


(def matchmaking-compflag "matchmaking")


(def battles-buttons-state-keys
  [:app-update-available :battle-password :battle-title :engines :engine-filter :engine-version
   :http-download :map-input-prefix
   :map-name :maps :mod-filter :mod-name :mods :pop-out-battle :pop-out-chat :spring-isolation-dir :tasks-by-type
   :use-git-mod-version])

(def battles-buttons-keys
  [:accepted :battle :battles :client-data :compflags :scripttags :selected-battle :server-key])

(defn battles-buttons-view
  [{:keys [app-update-available battles battle-password client-data selected-battle] :as state}]
  {:fx/type :v-box
   :alignment :top-left
   :children
   [{:fx/type :flow-pane
     :alignment :center-left
     :style {:-fx-font-size 16}
     :children
     (concat
       (when (and selected-battle
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
       [
        {:fx/type :button
         :text "Host Battle"
         :on-action {:event/type :spring-lobby/toggle
                     :key :show-host-battle-window}}
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
            state)]))}]})
