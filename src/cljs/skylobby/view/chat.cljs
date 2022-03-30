(ns skylobby.view.chat
  (:require 
    [clojure.string :as string]
    ["moment" :as moment]
    [re-frame.core :as rf]
    [reagent.dom :as rdom]
    [skylobby.css :as css]
    [skylobby.view.server-nav :as server-nav]
    [skylobby.view.servers-nav :as servers-nav]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-infer* true)


(defn listen [query-v]
  @(rf/subscribe query-v))

(defn chat-history [{:keys [server-key channel-name]}]
  (let [chat (listen [:skylobby/chat server-key channel-name])
        messages (->> chat
                      :messages
                      reverse
                      (map-indexed vector))]
    [:div
     {;:class "flex-column"
      :style {
              ;:flex "1 1 auto"
              :font-family "Monospace"
              :font-size 18
              :overflow-y "auto"}}
     (for [[i {:keys [message-type text timestamp username]}] messages]
       ^{:key i}
       [:div
        [:span {:style {:color "grey"}}
         (str "[" (u/format-hours (.local (moment/utc timestamp))) "] ")]
        [:span
         {:style
          {:color (case message-type
                    :ex "cyan"
                    nil "royalblue"
                    ; else
                    "grey")}}
         (case message-type
           :ex (str "* " username " " text)
           :join (str username " has joined")
           :leave (str username " has left")
           :info (str "* " text)
           ; else
           (str username ": "))]
        (when-not message-type
          [:span (str text)])])]))
      ;:wrap "soft"}]))


(defn scroll-to-bottom [this]
  (let [node (rdom/dom-node this)]
    (set! (.-scrollTop node) (.-scrollHeight node))))

(def auto-scroll-chat-history
  (with-meta chat-history
    {:component-did-mount scroll-to-bottom
     :component-did-update scroll-to-bottom}))


(defn chat-input [{:keys [channel-name server-key]}]
  [:form#chat
   {:on-submit (fn [event]
                 (.preventDefault event)
                 (let [form (.getElementById js/document "chat")
                       form-data (new js/FormData form)
                       message (.get form-data "chat-message")]
                   (if-not (string/blank? message)
                     (rf/dispatch [:skylobby/send-message server-key channel-name message])
                     (log/warn "Attempt to send blank message" server-key channel-name message))))
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
      :name "chat-message"
      :on-change #(rf/dispatch [:skylobby/assoc-in [:by-server server-key :chat-message] (-> % .-target .-value)])
      :style {:flex-grow 1}
      :type "text"
      :value (listen [:skylobby/chat-message server-key])}]]])


(defn chat-view [params]
  [:div
   [:div
    [auto-scroll-chat-history params]]
   [chat-input params]])


(defn my-channels-nav [{:keys [server-key server-url username] :as params}]
  [:div {:class "flex justify-center"}
   (if-let [my-channels (seq (remove (comp u/battle-channel-name? first) (listen [:skylobby/my-channels server-key])))]
     (for [[channel-name _] my-channels]
       [:div {:key channel-name
              :class "pa3"}
        [:a
         {:class (str css/header-class " "
                   (if (= channel-name (:channel-name params))
                     "green"
                     "gray"))
          :href (u/href :skylobby/chat {:server-url server-url :channel-name channel-name} {:username username})}
         channel-name]])
     [:div.f3 
      "No channels"])])

(defn chat-page [_]
  (let [current-route (listen [:skylobby/current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        channel-name (-> parameters :path :channel-name)
        username (-> parameters :query :username)
        server-key (u/get-server-key server-url username)]
    [:div
     {:class "flex"
      :style {:flex-flow "column"
              :height "100%"}}
     [servers-nav/servers-nav]
     [server-nav/server-nav]
     [:div {:class "flex justify-center"}
      [my-channels-nav {:channel-name channel-name :server-key server-key :server-url server-url :username username}]]
     (when-not (string/blank? channel-name)
       [:div
        {:class "flex flex-column"
         :style {
                 :flex "1 1 auto"
                 :flex-flow "column"
                 :overflow-y "auto"}}
        [auto-scroll-chat-history {:channel-name channel-name :server-key server-key}]])
     (when-not (string/blank? channel-name)
       [chat-input {:channel-name channel-name :server-key server-key}])]))


(defn channels-page [_]
  (let [current-route (listen [:skylobby/current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        channel-name (-> parameters :path :channel-name)
        username (-> parameters :query :username)
        server-key (u/get-server-key server-url username)]
    [:div
     [servers-nav/servers-nav]
     [server-nav/server-nav]
     [:div {:class "flex justify-center"}
      [my-channels-nav {:channel-name channel-name :server-key server-key :server-url server-url :username username}]]]))
