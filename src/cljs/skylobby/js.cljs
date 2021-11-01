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
  (log/warn "Unhandled event: %s" event))

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
    (log/info "Getting servers")
    (chsk-send!
      [:skylobby/get-servers]
      5000
      (fn [reply]
        (log/trace "Servers reply" reply)
        (if (sente/cb-success? reply)
          (rf/dispatch [::assoc :servers reply])
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
              (rf/dispatch [::get-chat server-key (battle-channel-name reply)]))
            (log/error reply)))))
    db))

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


(rf/reg-event-db ::assoc
  (fn [db [_t k v]]
    (log/trace "Assoc" k v)
    (assoc db k v)))


;

(rf/reg-sub ::current-route
  (fn [db]
    (:current-route db)))

(rf/reg-sub ::battles
  (fn [db]
    (:battles db)))

(rf/reg-sub ::battle
  (fn [db]
    (:battle db)))

(rf/reg-sub ::chat
  (fn [db]
    (:chat db)))


(rf/reg-sub ::active-servers
  (fn [db]
    (get-in db [:servers :active-servers])))


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
     [:thead
      [:tr
       [:th "ID"]
       [:th "Title"]
       [:th "Owner"]
       [:th "Map"]
       [:th "Game"]
       [:th "Engine"]
       [:th "Locked?"]]]
     [:tbody
      (for [[battle-id battle] (listen [::battles])]
         ^{:key battle-id}
         [:tr
          [:td battle-id]
          [:td (:battle-title battle)]
          [:td (:host-username battle)]
          [:td (:battle-map battle)]
          [:td (:battle-modname battle)]
          [:td (str (:battle-engine battle) " " (:battle-version battle))]
          [:td (:battle-locked battle)]])]]]])

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
     (for [route-name [::battles ::room]]
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
      [:h1 "Battles"]]
     [:div {:class "flex justify-center"}
      [:button
       {:on-click #(rf/dispatch [::get-battles server-url username])}
       "Refresh"]]
     [:div {:class "flex justify-center"}
      [:table
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


(defn room-page [current-route]
  [:div
   [server-nav current-route]
   (let [{:keys [parameters]} current-route
         server-url (-> parameters :path :server-url)
         username (-> parameters :query :username)
         server-key (server-key server-url username)]
     [:div {:class "flex justify-center"}
      [:button
       {:on-click
        (fn []
         (rf/dispatch [::get-battle server-url username])
         (rf/dispatch [::get-battles server-url username]))}
       "Refresh"]])
   (let [battle (listen [::battle])
         battles (listen [::battles])
         battle-details (get battles (:battle-id battle))
         map-name (:battle-map battle-details)]
     (log/info (keys battle))
     (log/info (keys battle-details))
     [:div {:class "flex justify-center"}
      [:table
       [:thead
        [:tr
         [:th "Nickname"]
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
            [:td username]
            [:td (:skill user)]
            [:td (:ally (:battle-status user))]
            [:td (:team (:battle-status user))]
            [:td (:team-color user)]
            [:td (:mode (:battle-status user))]
            [:td (:side (:battle-status user))]
            [:td (:rank user)]
            [:td (:country user)]
            [:td (:handicap (:battle-status user))]])]]
      [:img
       {:src (str "http://localhost:12345/minimap-image?map-name=" map-name)
        :alt (str map-name)
        :style {:max-width "256px"
                :max-height "256px"}}]])
   (let [chat (listen [::chat])]
     [:div {:class "flex justify-center"}
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
        :wrap "soft"}]])
   [:div {:class "flex justify-center"}
    [:input
     {:style {:flex-grow 1}}]]])


(defn server-page [current-route]
  [:div
   [server-nav current-route]
   [:div {:class "flex justify-center"}
    [:button
     {:on-click #(rf/dispatch [::get-servers])}
     "Refresh"]]])
   ;(let [match (:rfc/identity current-route)])])


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
                (js/console.log "Entering servers page")
                (rf/dispatch [::get-servers]))
       ;; Teardown can be done here.
       :stop  (fn [& _params] (js/console.log "Leaving servers page"))}]}]
   ["server/:server-url"
    {
     :view      server-page
     :parameters {:path {:server-url string?}
                  :query {:username string?}}
     :controllers
     [{:parameters {:path [:server-url]
                    :query [:username]}
       :start (fn [{:keys [path query]}]
                (js/console.log "Entering page server" (:server-url path) "as user" (:username query)))
       :stop  (fn [{:keys [path query]}]
                (js/console.log "Leaving page server" (:server-url path) "as user" (:username query)))}]}
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
                 (js/console.log "Entering battles page")
                 (log/info params)
                 (rf/dispatch [::get-battles (get-in params [:path :server-url]) (get-in params [:query :username])]))
        ;; Teardown can be done here.
        :stop  (fn [& _params] (js/console.log "Leaving battles page"))}]}]
    ["/room"
     {:name      ::room
      :view      room-page
      :link-text "Room"
      :controllers
      [{
        :parameters {:path [:server-url]
                     :query [:username]}
        :start (fn [params]
                 (js/console.log "Entering room")
                 (rf/dispatch [::get-battles (get-in params [:path :server-url]) (get-in params [:query :username])])
                 (rf/dispatch [::get-battle (get-in params [:path :server-url]) (get-in params [:query :username])]))
        :stop  (fn [& _params] (js/console.log "Leaving room"))}]}]]])


(defn on-navigate [new-match]
  (when new-match
    (rf/dispatch [::navigated new-match])))

(def router
  (reitit/router
    routes
    {:data {:coercion rss/coercion}}))

(defn init-routes! []
  (js/console.log "initializing routes")
  (rfe/start!
    router
    on-navigate
    {:use-fragment false}))


(defn nav [{:keys [router current-route]}]
  [:div
   [:div {:class "flex justify-center"}
    (let [route-name ::servers]
      [:div {:key route-name
             :class "pa3"}
       (when (= route-name (-> current-route :data :name))
         "> ")
       ;; Create a normal links that user can click
       [:a {:href (href route-name)} "Servers"]])
    (for [{:keys [server-id server-url username]} (filter :server-id (listen [::active-servers]))]
      [:div {:key server-id
             :class "pa3"}
       [:a {:href (href ::battles {:server-url server-url} {:username username})} server-id]])]
   #_
   [:div {:class "flex justify-center"}
    (for [route-name [::battles ::room]
          :let       [route (r/match-by-name router route-name)
                      text (-> route :data :link-text)]]
      [:div {:key route-name
             :class "pa3"}
       (when (= route-name (-> current-route :data :name))
         "> ")
       ;; Create a normal links that user can click
       [:a {:href (href route-name)} text]])]])

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
