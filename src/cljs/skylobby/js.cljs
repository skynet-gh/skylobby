(ns skylobby.js
  (:require
    [cljs.reader :as reader]
    [clojure.string :as string]
    [reagent.dom :as rdom]
    [reitit.coercion.spec :as rss]
    [reitit.core :as r]
    [reitit.frontend :as reitit]
    [reitit.frontend.controllers :as rfc]
    [reitit.frontend.easy :as rfe]
    [re-frame.core :as rf]
    [taoensso.encore :as encore :refer-macros [have]]
    [taoensso.sente :as sente :refer [cb-success?]]
    [taoensso.timbre :as log]))


(defrecord File [path])

(reader/register-tag-parser! 'spring-lobby/java.io.File (fn [path] (File. path)))


(def ?csrf-token
  (when-let [el (.getElementById js/document "sente-csrf-token")]
    (.getAttribute el "data-csrf-token")))

(let [
      chsk-type :auto
      ;; Serializtion format, must use same val for client + server:
      packer :edn
      {:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client!
        "/chsk"
        nil ; ?csrf-token
        {
         :type   chsk-type
         :packer packer
         :wrap-recv-evs? false})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state))   ; Watchable, read-only atom

(defmulti -event-msg-handler :id)

(defn event-msg-handler
  [{:as ev-msg :keys [id ?data event]}]
  (log/trace ev-msg)
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (log/warnf "Unhandled event: %s" event))

(defmethod -event-msg-handler
  :chsk/ws-ping
  [{:as ev-msg :keys [event]}]
  (log/debugf "WebSocket ping: %s" event))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (let [[old-state-map new-state-map] (have vector? ?data)]
    (if (:first-open? new-state-map)
      (log/info "Channel socket successfully established!: %s" new-state-map)
      (log/info "Channel socket state change: %s"              new-state-map))))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (log/info "Push event from server: %s" ?data))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (log/info "Handshake: %s" ?data)))


(defmethod -event-msg-handler :skylobby/battles
  [{:as ev-msg :keys [?data]}]
  (log/info "Battles: %s" ?data)
  (rf/dispatch [::assoc :battles ?data]))

(defmethod -event-msg-handler :skylobby/battle
  [{:as ev-msg :keys [?data]}]
  (log/info "Battle: %s" ?data)
  (rf/dispatch [::assoc :battle ?data]))


; re-frame

(rf/reg-event-db ::initialize-db
  (fn [db _]
    (if db
      db
      {:current-route nil})))

(rf/reg-event-fx ::push-state
  (fn [_db [_ & route]]
    {:push-state route}))


(rf/reg-event-db ::navigated
  (fn [db [_ new-match]]
    (let [old-match (:current-route db)
          controllers (rfc/apply-controllers (:controllers old-match) new-match)]
      (assoc db :current-route (assoc new-match :controllers controllers)))))


(rf/reg-event-db ::get-servers
  (fn [db _event]
    (log/debug "Getting servers")
    (chsk-send!
      [:skylobby/get-servers]
      5000
      (fn [reply]
        (log/trace "Servers reply" reply)
        (if (sente/cb-success? reply)
          (do
            (log/trace "Got servers" reply)
            (rf/dispatch [::assoc :servers reply]))
          (log/error reply))))
    db))

(defn server-key [server-url username]
  (str username "@" server-url))

(rf/reg-event-db ::get-battles
  (fn [db [_t server-url username]]
    (let [server-key (server-key server-url username)]
      (log/info "Getting battles for" server-key)
      (chsk-send!
        [:skylobby/get-in [:by-server server-key :battles]]
        5000
        (fn [reply]
          (log/trace "Battles reply for" server-key reply)
          (if (sente/cb-success? reply)
            (rf/dispatch [::assoc :battles reply])
            (log/error reply)))))
    db))

(rf/reg-event-db ::get-my-channels
  (fn [db [_t server-key]]
    (log/info "Getting my channels for" server-key)
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :my-channels]]
      5000
      (fn [reply]
        (log/trace "My channels reply for" server-key reply)
        (if (sente/cb-success? reply)
          (rf/dispatch [::assoc :my-channels reply])
          (log/error reply))))
    db))

(defn battle-channel-name [{:keys [battle-id channel-name]}]
  (or channel-name
      (str "__battle__" battle-id)))

(rf/reg-event-db ::get-battle
  (fn [db [_t server-url username]]
    (let [server-key (server-key server-url username)]
      (log/info "Getting battle for" server-key)
      (chsk-send!
        [:skylobby/get-in [:by-server server-key :battle]]
        5000
        (fn [reply]
          (log/trace "Battle reply for" server-key reply)
          (if (sente/cb-success? reply)
            (do
              (rf/dispatch [::assoc :battle reply])
              (rf/dispatch [::poll-chat server-key (battle-channel-name reply)]))
            (log/error reply)))))
    db))

(rf/reg-event-db ::poll-chat
  (fn [db [_t server-key channel-name]]
    (rf/dispatch [::clear-chat-poll])
    (let [interval (js/setInterval
                     #(rf/dispatch [::get-chat server-key channel-name])
                     3000)]
      (rf/dispatch [::get-chat server-key channel-name])
      (assoc db :poll-chat-interval interval))))

(rf/reg-event-db ::clear-chat-poll
  (fn [db _event]
    (when-let [interval (:poll-chat-interval db)]
      (js/clearInterval interval))
    (dissoc db :poll-chat-interval)))

(rf/reg-event-db ::get-chat
  (fn [db [_t server-key channel-name]]
    (log/info "Getting chat for" server-key channel-name)
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :channels channel-name]]
      5000
      (fn [reply]
        (log/trace "Chat reply for" server-key channel-name)
        (if (sente/cb-success? reply)
          (rf/dispatch [::assoc :chat reply])
          (log/error reply))))
    db))

(rf/reg-event-db ::join-battle
  (fn [db [_t server-key battle-id]]
    (chsk-send!
      [:skylobby/join-battle [server-key battle-id]]
      5000)
    db))

(rf/reg-event-db ::connect-server
  (fn [db [_t server-url]]
    (chsk-send!
      [:skylobby/connect-server {:server-url server-url}]
      5000)
    db))

(rf/reg-event-db ::start-battle
  (fn [db [_t server-key]]
    (chsk-send!
      [:skylobby/start-battle {:server-key server-key}]
      5000)
    db))

(rf/reg-event-db ::send-message
  (fn [db [_t server-key channel-name message]]
    (log/info "Sending message in" server-key channel-name ": " message)
    (chsk-send!
      [:skylobby/send-message {:server-key server-key
                               :channel-name channel-name
                               :message message}]
      5000)
    (assoc db :chat-message "")))


(rf/reg-event-db ::assoc
  (fn [db [_t k v]]
    (log/trace "Assoc" k v)
    (assoc db k v)))


;

(rf/reg-sub ::current-route
  (fn [db]
    (:current-route db)))

(rf/reg-sub ::servers
  (fn [db]
    (:servers db)))

(rf/reg-sub ::battles
  (fn [db]
    (:battles db)))

(rf/reg-sub ::battle
  (fn [db]
    (:battle db)))

(rf/reg-sub ::chat
  (fn [db]
    (:chat db)))

(rf/reg-sub ::my-channels
  (fn [db]
    (:my-channels db)))

(rf/reg-sub ::active-servers
  (fn [db]
    (get-in db [:servers :active-servers])))

(rf/reg-sub ::chat-message
  (fn [db]
    (:chat-message db)))


(defn listen [query-v]
  @(rf/subscribe query-v))


(defn servers-page [current-route]
  [:div
   [:div {:class "flex justify-center"}
    [:h1 "Servers"]]
   [:div {:class "flex justify-center"}
    [:button
     {:on-click #(rf/dispatch [::get-servers])}
     "Refresh"]]
   [:div {:class "flex justify-center"}
    [:table
     {:style {:flex-grow 1}}
     [:thead
      [:tr
       [:th "Alias"]
       [:th "URL"]
       [:th "Username"]
       [:th "Password"]
       [:th "Actions"]]]
     [:tbody
      (let [{:keys [active-servers logins servers]} (listen [::servers])]
        (for [[server-url server-config] servers]
          ^{:key server-url}
          [:tr
           [:td (:alias server-config)]
           [:td server-url]
           [:td
            [:input
             {:value (get-in logins [server-url :username])}]]
           [:td
            [:input
             {:type "password"
              :value (get-in logins [server-url :password])}]]
           [:td
            [:button
             {:on-click #(rf/dispatch [::connect-server server-url])}
             "Login"]]]))]]]])

(defn href
  "Return relative url for given route. Url can be used in HTML links."
  ([k]
   (href k nil nil))
  ([k params]
   (href k params nil))
  ([k params query]
   (rfe/href k params query)))

(defn server-nav [current-route]
  (let [{:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)]
    [:div {:class "flex justify-center"}
     (for [route-name [::battles ::channels ::room]]
       [:div {:key route-name
              :class "pa3"}
        (when (= route-name (-> current-route :data :name))
          "> ")
        [:a {:href (href route-name {:server-url server-url} {:username username})} (name route-name)]])]))

(defn battles-page [current-route]
  (let [{:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (server-key server-url username)]
    [:div
     [server-nav current-route]
     [:div {:class "flex justify-center"}
      [:button
       {:on-click #(rf/dispatch [::get-battles server-url username])}
       "Refresh"]]
     [:div {:class "flex justify-center"}
      [:table
       {:style {:flex-grow 1}}
       [:thead
        [:tr
         [:th "Actions"]
         [:th "ID"]
         [:th "Title"]
         [:th "Locked?"]
         [:th "Map"]
         [:th "Play (Spec)"]
         [:th "Game"]
         [:th "Engine"]
         [:th "Owner"]]]
       [:tbody
        (for [[battle-id battle] (listen [::battles])]
           ^{:key battle-id}
           [:tr
            [:td
             [:button
              {:on-click #(rf/dispatch [::join-battle server-key battle-id])}
              "Join"]]
            [:td battle-id]
            [:td (:battle-title battle)]
            [:td (:battle-locked battle)]
            [:td (:battle-map battle)]
            (let [total-user-count (count (:users battle))
                  spec-count (:battle-spectators battle)]
              [:td (str (- total-user-count spec-count)
                        " (" spec-count ")")])
            [:td (:battle-modname battle)]
            [:td (str (:battle-engine battle) " " (:battle-version battle))]
            [:td (:host-username battle)]])]]]]))

(defn my-channels-nav [{:keys [server-url username] :as params}]
  (log/info (listen [::my-channels]))
  [:div {:class "flex justify-center"}
   (for [[channel-name _] (listen [::my-channels])]
     [:div {:key channel-name
            :class "pa3"}
      (when (= channel-name (:channel-name params))
        "> ")
      [:a {:href (href ::chat {:server-url server-url :channel-name channel-name} {:username username})} channel-name]])])

(defn chat-history []
  (let [chat (listen [::chat])]
    [:textarea
     {:readonly true
      :rows 16
      :style {:flex-grow 1
              :font-family "Monospace"
              :resize "none"}
      :value
      (->> chat
           :messages
           reverse
           (map
             (fn [{:keys [username text]}]
               (str username ": " text)))
           (string/join "\n"))
      :wrap "soft"}]))

(def auto-scroll-chat-history
  (with-meta chat-history
    {:component-did-update
     (fn [this]
       (let [node (rdom/dom-node this)]
         (set! (.-scrollTop node) (.-scrollHeight node))))}))

(defn chat-view [{:keys [channel-name server-key]}]
  [:div
   [:div {:class "flex justify-center"}
    [auto-scroll-chat-history]]
   [:form#chat
    {:on-submit (fn [event]
                  (.preventDefault event)
                  (let [form (.getElementById js/document "chat")
                        form-data (new js/FormData form)
                        message (.get form-data "chat-message")]
                    (if-not (string/blank? message)
                      (rf/dispatch [::send-message server-key channel-name message])
                      (log/warn "Attempt to send blank message" server-key channel-name message))))
     :style {:margin-bottom 0}}
    [:div {:class "flex justify-center"}
     [:button
      {:type "submit"}
      "Send"]
     [:input
      {:auto-focus true
       :autoComplete "off"
       :name "chat-message"
       :on-change #(rf/dispatch [::assoc :chat-message (-> % .-target .-value)])
       :style {:flex-grow 1}
       :type "text"
       :value (listen [::chat-message])}]]]])

(defn channels-page [current-route]
  (let [{:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        channel-name (-> parameters :path :channel-name)
        username (-> parameters :query :username)
        server-key (server-key server-url username)]
    [:div
     [server-nav current-route]
     [:div {:class "flex justify-center"}
      [:button
       {:on-click #(rf/dispatch [::get-my-channels server-key])}
       "Refresh"]]
     [:div {:class "flex justify-center"}
      [my-channels-nav {:channel-name channel-name :server-url server-url :username username}]]]))

(defn chat-page [current-route]
  (let [{:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        channel-name (-> parameters :path :channel-name)
        username (-> parameters :query :username)
        server-key (server-key server-url username)]
    [:div
     [server-nav current-route]
     [:div {:class "flex justify-center"}
      [:button
       {:on-click #(rf/dispatch [::get-chat server-key channel-name])}
       "Refresh"]]
     [:div {:class "flex justify-center"}
      [my-channels-nav {:channel-name channel-name :server-url server-url :username username}]]
     (when-not (string/blank? channel-name)
       [chat-view {:channel-name channel-name :server-key server-key}])]))


(defn room-page [current-route]
  (let [{:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (server-key server-url username)
        battle (listen [::battle])]
    [:div {:class "flex-column"}
     [server-nav current-route]
     [:div {:class "flex justify-center"}
      [:button
       {:on-click
        (fn []
         (rf/dispatch [::get-battle server-url username])
         (rf/dispatch [::get-battles server-url username]))}
       "Refresh"]]
     (let [battles (listen [::battles])
           battle-details (get battles (:battle-id battle))
           map-name (:battle-map battle-details)]
       [:div {:class "flex justify-center"}
        [:table
         {:class "flex"
          :style
          {:flex-grow 1
           :overflow-y "scroll"
           :height "256px"
           :width "100%"
           :display "block"}}
         [:thead
          [:tr
           [:th {:style {:max-width "99%"}} "Nickname"]
           [:th "Skill"]
           [:th "Ally"]
           [:th "Team"]
           [:th "Color"]
           [:th "Spectator"]
           [:th "Faction"]
           [:th "Rank"]
           [:th "Country"]
           [:th "Bonus"]]]
         [:tbody
          (for [[username user] (:users battle)]
             ^{:key username}
             [:tr
              [:td {:style {:max-width "99%"}} username]
              [:td (:skill user)]
              [:td (:ally (:battle-status user))]
              [:td (:team (:battle-status user))]
              [:td (:team-color user)]
              [:td (:mode (:battle-status user))]
              [:td (:side (:battle-status user))]
              [:td (:rank user)]
              [:td (:country user)]
              [:td (:handicap (:battle-status user))]])]]
        [:div
         {:style {:width "256px"
                  :height "256px"}}]
        [:img
         {:src (str "http://localhost:12345/minimap-image?map-name=" map-name)
          :alt (str map-name)
          :style {:width "100%"
                  :height "100%"
                  :object-fit "contain"}}]])
     [:div {:class "flex justify-center"}
      [:button
       {:on-click #(rf/dispatch [::start-battle server-key])}
       "Join Game"]]
     [chat-view {:channel-name (battle-channel-name battle) :server-key server-key}]]))


(defn server-page [current-route]
  [:div
   [server-nav current-route]
   [:div {:class "flex justify-center"}
    [:button
     {:on-click #(rf/dispatch [::get-servers])}
     "Refresh"]]])


(rf/reg-fx :push-state
  (fn [route]
    (apply rfe/push-state route)))


(def routes
  ["/"
   ["servers"
    {:name      ::servers
     :view      servers-page
     :link-text "Servers"
     :controllers
     [{;; Do whatever initialization needed for home page
       ;; I.e (re-frame/dispatch [::events/load-something-with-ajax])
       :start (fn [& _params]
                (log/info "Entering servers page")
                (rf/dispatch [::get-servers]))
       ;; Teardown can be done here.
       :stop  (fn [& _params]
                (log/info "Leaving servers page"))}]}]
   ["server/:server-url"
    {
     :view      server-page
     :parameters {:path {:server-url string?}
                  :query {:username string?}}
     :controllers
     [{:parameters {:path [:server-url]
                    :query [:username]}
       :start (fn [{:keys [path query]}]
                (log/info "Entering page server" (:server-url path) "as user" (:username query)))
       :stop  (fn [{:keys [path query]}]
                (log/info "Leaving page server" (:server-url path) "as user" (:username query)))}]}
    ["/battles"
     {:name      ::battles
      :view      battles-page
      :link-text "Battles"
      :controllers
      [{;; Do whatever initialization needed for home page
        ;; I.e (re-frame/dispatch [::events/load-something-with-ajax])
        :parameters {:path [:server-url]
                     :query [:username]}
        :start (fn [params]
                 (let [server-url (get-in params [:path :server-url])
                       username (get-in params [:query :username])]
                   (log/info "Entering battles page" server-url)
                   (rf/dispatch [::get-battles server-url username])))
        ;; Teardown can be done here.
        :stop  (fn [& _params]
                 (log/info "Leaving battles page"))}]}]
    ["/channels"
     {:name      ::channels
      :view      channels-page
      :controllers
      [{
        :parameters {:path [:server-url :channel-name]
                     :query [:username]}
        :start (fn [params]
                 (let [server-key (server-key (get-in params [:path :server-url]) (get-in params [:query :username]))]
                   (log/info "Entering channels page" server-key)
                   (rf/dispatch [::get-my-channels server-key])))
        :stop  (fn [_params]
                 (log/info "Leaving channels page"))}]}]
    ["/chat/:channel-name"
     {:name      ::chat
      :view      chat-page
      :parameters {:path {:channel-name string?}}
      :controllers
      [{
        :parameters {:path [:server-url :channel-name]
                     :query [:username]}
        :start (fn [params]
                 (let [server-key (server-key (get-in params [:path :server-url]) (get-in params [:query :username]))
                       channel-name (get-in params [:path :channel-name])]
                   (log/info "Entering chat page" server-key channel-name)
                   (rf/dispatch [::get-my-channels server-key])
                   (rf/dispatch [::poll-chat server-key channel-name])))
        :stop  (fn [_params]
                 (log/info "Leaving chat page")
                 (rf/dispatch [::clear-chat-poll]))}]}]
    ["/room"
     {:name      ::room
      :view      room-page
      :link-text "Room"
      :controllers
      [{
        :parameters {:path [:server-url]
                     :query [:username]}
        :start (fn [params]
                 (let [server-url (get-in params [:path :server-url])
                       username (get-in params [:query :username])]
                   (log/info "Entering room")
                   (rf/dispatch [::get-battles server-url username])
                   (rf/dispatch [::get-battle server-url username])))
        :stop  (fn [& _params]
                 (log/info "Leaving room")
                 (rf/dispatch [::clear-chat-poll]))}]}]]])


(defn on-navigate [new-match]
  (when new-match
    (rf/dispatch [::navigated new-match])))

(def router
  (reitit/router
    routes
    {:data {:coercion rss/coercion}}))

(defn init-routes! []
  (log/info "Initializing routes")
  (rfe/start!
    router
    on-navigate
    {:use-fragment false}))


(defn nav [{:keys [current-route]}]
  (let [{:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (server-key server-url username)]
    [:div
     [:div {:class "flex justify-center"}
      (let [route-name ::servers]
        [:div {:key route-name
               :class "pa3"}
         (when (= route-name (-> current-route :data :name))
           "> ")
         [:a {:href (href route-name)} "Servers"]])
      (for [{:keys [server-id server-url username]} (filter :server-id (listen [::active-servers]))]
        [:div {:key server-id
               :class "pa3"}
         (when (= server-id server-key)
           "> ")
         [:a {:href (href ::battles {:server-url server-url} {:username username})} server-id]])]]))


(defn router-component [{:keys [router]}]
  (let [current-route (listen [::current-route])]
    [:div
     [nav {:router router :current-route current-route}]
     (when current-route
       [(-> current-route :data :view) current-route])]))


(def debug? ^boolean goog.DEBUG)

(defn dev-setup []
  (when debug?
    (enable-console-print!)
    (println "dev mode")))


(defn ^:dev/after-load init []
  (println "init")
  (rf/clear-subscription-cache!)
  (rf/dispatch-sync [::initialize-db])
  (rf/dispatch [::get-servers])
  (dev-setup)
  (init-routes!) ;; Reset routes on figwheel reload
  (sente/start-client-chsk-router! ch-chsk event-msg-handler)
  (let [user-id "localhost"]
    (log/info "Logging in with user-id %s" user-id)
    (sente/ajax-lite "/login"
      {:method :post
       ;:headers {:X-CSRF-Token (:csrf-token @chsk-state)}
       :params  {:user-id (str user-id)}}
      (fn [ajax-resp]
        (log/info "Ajax login response: %s" ajax-resp)
        (let [login-successful? true] ; Your logic here
          (if-not login-successful?
            (log/info "Login failed")
            (do
              (log/info "Login successful")
              (sente/chsk-reconnect! chsk)))))))
  (rdom/render [router-component {:router router}]
               (.getElementById js/document "root")))
