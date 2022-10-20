(ns skylobby.view.console
  (:require
    [clojure.string :as string]
    [re-frame.core :as rf]
    [reagent.dom :as rdom]
    [skylobby.util :as u]
    [skylobby.view.server-nav :as server-nav]
    [skylobby.view.servers-nav :as servers-nav]
    [taoensso.timbre :as log]))


(set! *warn-on-infer* true)


(defn listen [query-v]
  @(rf/subscribe query-v))


(defn console-history [{:keys [server-key]}]
  (let [console-log (listen [:skylobby/console-log server-key])
        messages (->> console-log
                      reverse
                      (map-indexed vector))]
    [:div
     {;:class "vh-100"
      :style {
              :flex "1 1 auto"
              :font-family "Monospace"
              :font-size 18
              :overflow-y "auto"}}
     (for [[i {:keys [message source timestamp]}] messages]
       ^{:key i}
       [:div
        [:span {:style {:color "grey"}}
         (str "[" (u/format-hours timestamp) "] ")]
        [:span
         {:style
          {:color (case source
                    :server "blue"
                    :client "gold"
                    ; else
                    "grey")}}
         (case source
           :server " < "
           :client " > "
           ; else
           " ")]
        [:span (str message)]])]))
      ;:wrap "soft"}]))


(defn scroll-to-bottom [this]
  (let [node (rdom/dom-node this)]
    (set! (.-scrollTop node) (.-scrollHeight node))))

(def auto-scroll-console-history
  (with-meta console-history
    {:component-did-mount scroll-to-bottom
     :component-did-update scroll-to-bottom}))

(defn console-input [{:keys [server-key]}]
  [:form#chat
   {:on-submit (fn [event]
                 (.preventDefault event)
                 (let [form (.getElementById js/document "chat")
                       form-data (new js/FormData form)
                       message (.get form-data "command-message")]
                   (if-not (string/blank? message)
                     (rf/dispatch [:skylobby/send-command server-key message])
                     (log/warn "Attempt to send blank command" server-key message))))
    :style {:margin-bottom 0}}
   [:div {:class "flex justify-center mt2"}
    [:button
     {:class "ba pa2 mb2 mh2 db"
      :type "submit"}
     "Send"]
    [:input
     {:class "input-reset ba b--black-20 pa2 mb2 mr2 db w-100"
      :auto-focus true
      :autoComplete "off"
      :name "command-message"
      :on-change #(rf/dispatch [:skylobby/assoc-in [:by-server server-key :command-message] (-> % .-target .-value)])
      :style {:flex-grow 1}
      :type "text"
      :value (listen [:skylobby/command-message server-key])}]]])

(defn console-page [_]
  (let [current-route (listen [:skylobby/current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (u/get-server-key server-url username)]
    [:div
     {:class "flex vh-100"
      :style {:flex-flow "column"}}
              ;:height "100%"}}
     [servers-nav/servers-nav]
     [server-nav/server-nav]
     [:div
      {:class "flex flex-column"
       :style {
               :flex "1 1 auto"
               :flex-flow "column"
               :overflow-y "auto"
               :overflow-wrap "anywhere"}}
      [auto-scroll-console-history {:server-key server-key}]]
     [console-input {:server-key server-key}]]))
