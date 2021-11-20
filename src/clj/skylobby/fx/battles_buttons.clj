(ns skylobby.fx.battles-buttons
  (:require
    [cljfx.api :as fx]
    clojure.set
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.font-icon :as font-icon]
    [taoensso.tufte :as tufte]))


(set! *warn-on-reflection* true)


(def matchmaking-compflag "matchmaking")

(def battles-layouts
  [
   "vertical"
   "horizontal"])


(defn- battles-buttons-view-impl
  [{:fx/keys [context]}]
  (let [battle-password (fx/sub-val context :battle-password)
        server-key (fx/sub-ctx context skylobby.fx/selected-tab-server-key-sub)
        selected-battle (fx/sub-val context get-in [:by-server server-key :selected-battle])
        selected-battle-details (fx/sub-val context get-in [:by-server server-key :battles selected-battle])
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])]
    {:fx/type :v-box
     :alignment :top-left
     :children
     [{:fx/type :flow-pane
       :alignment :center-left
       :style {:-fx-font-size 16}
       :children
       (concat
         (when (and selected-battle selected-battle-details)
           (let [needs-password (= "1" (:battle-passworded selected-battle-details))]
             (concat
               [{:fx/type :button
                 :text "Join Battle"
                 :disable (boolean (and needs-password (string/blank? battle-password)))
                 :on-action {:event/type :spring-lobby/join-battle
                             :battle-password battle-password
                             :client-data client-data
                             :selected-battle selected-battle
                             :battle-passworded needs-password}}]
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
          {:fx/type :button
           :text "Battles"
           :on-action {:event/type :spring-lobby/toggle
                       :key :show-battles-window}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-window-maximize:16:white"}}
          {:fx/type :button
           :text "Chat"
           :on-action {:event/type :spring-lobby/toggle
                       :key :show-chat-window}
           :graphic
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-window-maximize:16:white"}}
          {:fx/type :label
           :text " Orientation: "}
          {:fx/type :combo-box
           :value (fx/sub-val context :battles-layout)
           :items battles-layouts
           :on-value-changed {:event/type :spring-lobby/assoc
                              :key :battles-layout}}]
         #_
         [{:fx/type :label
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
            :icon-literal (str "mdi-download:16:white")}}])}]}))

(defn battles-buttons-view [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battles-buttons-view
      (battles-buttons-view-impl state))))
