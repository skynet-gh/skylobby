(ns skylobby.fx.console
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.string :as string]
    skylobby.fx
    [skylobby.fx.ext :refer [with-scroll-text-prop]]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (java.util TimeZone)))


(def console-view-keys
  [:client-data :console-auto-scroll :console-log :console-message-draft :server-key])

(defn console-view-impl [{:keys [client-data console-auto-scroll console-log console-message-draft server-key]}]
  (let [time-zone-id (.toZoneId (TimeZone/getDefault))
        console-text (string/join "\n"
                       (map
                         (fn [{:keys [message source timestamp]}]
                           (str (u/format-hours time-zone-id timestamp)
                                (case source
                                  :server " < "
                                  :client " > "
                                  " ")
                                message))
                         (reverse console-log)))]
    {:fx/type :v-box
     :children
     [{:fx/type with-scroll-text-prop
       :v-box/vgrow :always
       :props {:scroll-text [console-text console-auto-scroll]}
       :desc
       {:fx/type :text-area
        :editable false
        :wrap-text true
        :style {:-fx-font-family skylobby.fx/monospace-font-family}}}
      {:fx/type :h-box
       :alignment :center-left
       :children
       [{:fx/type :button
         :text "Send"
         :on-action {:event/type :spring-lobby/send-console
                     :client-data client-data
                     :message console-message-draft
                     :server-key server-key}}
        {:fx/type :text-field
         :h-box/hgrow :always
         :text (str console-message-draft)
         :on-text-changed {:event/type :spring-lobby/assoc-in
                           :path [:by-server server-key :console-message-draft]}
         :on-action {:event/type :spring-lobby/send-console
                     :client-data client-data
                     :message console-message-draft
                     :server-key server-key}}
        {:fx/type fx.ext.node/with-tooltip-props
         :props
         {:tooltip
          {:fx/type :tooltip
           :show-delay [10 :ms]
           :text "Auto scroll"}}
         :desc
         {:fx/type :h-box
          :alignment :center-left
          :children
          [
           {:fx/type font-icon/lifecycle
            :icon-literal "mdi-autorenew:20:white"}
           {:fx/type :check-box
            :selected (boolean console-auto-scroll)
            :on-selected-changed {:event/type :spring-lobby/assoc
                                  :key :console-auto-scroll}}]}}]}]}))


(defn console-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :console-view
      (console-view-impl state))))
