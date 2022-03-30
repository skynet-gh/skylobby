(ns skylobby.js
  (:require
    [cljs.reader :as reader]
    [clojure.edn :as edn]
    [reagent.dom :as rdom]
    [reitit.coercion.spec :as rss]
    [reitit.frontend :as reitit]
    [reitit.frontend.controllers :as rfc]
    [reitit.frontend.easy :as rfe]
    [re-frame.core :as rf]
    [skylobby.view.battle :as battle-view]
    [skylobby.view.battles :as battles-view]
    [skylobby.view.chat :as chat-view]
    [skylobby.view.servers :as servers-view]
    [skylobby.view.server-nav :as server-nav]
    [skylobby.view.servers-nav :as servers-nav]
    [taoensso.encore :as encore :refer-macros [have]]
    [taoensso.sente :as sente]
    [taoensso.timbre :as log]))


(set! *warn-on-infer* true)


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
  [{:as ev-msg}]
  (log/trace ev-msg)
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:keys [event]}]
  (log/warnf "Unhandled event: %s" event))

(defmethod -event-msg-handler
  :chsk/ws-ping
  [{:keys [event]}]
  (log/debugf "WebSocket ping: %s" event))

(defmethod -event-msg-handler :chsk/state
  [{:keys [?data]}]
  (let [[_old-state-map new-state-map] (have vector? ?data)]
    (if (:first-open? new-state-map)
      (log/info "Channel socket successfully established!: %s" new-state-map)
      (log/info "Channel socket state change: %s"              new-state-map))))

(defmethod -event-msg-handler :chsk/recv
  [{:keys [?data]}]
  (log/info "Push event from server: %s" ?data))

(defmethod -event-msg-handler :chsk/handshake
  [{:keys [?data]}]
  (let [[_?uid _?csrf-token _?handshake-data] ?data]
    (log/info "Handshake: %s" ?data)))


(defmethod -event-msg-handler :skylobby/servers
  [{:keys [?data]}]
  (log/trace "Servers: %s" ?data)
  (rf/dispatch [:skylobby/assoc :servers ?data]))

(defmethod -event-msg-handler :skylobby/battles
  [{:keys [?data]}]
  (log/trace "Battles: %s" ?data)
  (let [{:keys [server-key battles]} ?data]
    (rf/dispatch [:skylobby/assoc-in [:by-server server-key :battles] battles])))

(defmethod -event-msg-handler :skylobby/users
  [{:keys [?data]}]
  (log/trace "Users: %s" ?data)
  (let [{:keys [server-key users]} ?data]
    (rf/dispatch [:skylobby/assoc-in [:by-server server-key :users] users])))

(defmethod -event-msg-handler :skylobby/battle
  [{:keys [?data]}]
  (log/trace "Battle: %s" ?data)
  (let [{:keys [server-key battle]} ?data]
    (rf/dispatch [:skylobby/update-battle server-key battle])))

(defmethod -event-msg-handler :skylobby/chat
  [{:keys [?data]}]
  (log/trace "Chat: %s" ?data)
  (let [{:keys [channel-name server-key channel-data]} ?data]
    (rf/dispatch [:skylobby/assoc-in [:by-server server-key :chat channel-name] channel-data])))

(defmethod -event-msg-handler :skylobby/logins
  [{:keys [?data]}]
  (rf/dispatch [:skylobby/assoc :logins ?data]))

(defmethod -event-msg-handler :skylobby/auto-launch
  [{:keys [?data]}]
  (rf/dispatch [:skylobby/assoc :auto-launch ?data]))

(defmethod -event-msg-handler :skylobby/auto-unspec
  [{:keys [?data]}]
  (let [{:keys [auto-unspec server-key]} ?data]
    (rf/dispatch [:skylobby/assoc-in [:by-server server-key :auto-unspec] auto-unspec])))


; re-frame

(rf/reg-event-db :skylobby/initialize-db
  (fn [db _]
    (if db
      db
      {:current-route nil})))

(rf/reg-event-fx :skylobby/push-state
  (fn [_db [_ & route]]
    {:push-state route}))


(rf/reg-event-db :skylobby/navigated
  (fn [db [_ new-match]]
    (let [old-match (:current-route db)
          controllers (rfc/apply-controllers (:controllers old-match) new-match)]
      (assoc db :current-route (assoc new-match :controllers controllers)))))


(rf/reg-event-db :skylobby/get-servers
  (fn [db _event]
    (log/debug "Getting servers")
    (chsk-send!
      [:skylobby/get-servers]
      5000
      (fn [reply]
        (log/debug "Servers reply" reply)
        (if (sente/cb-success? reply)
          (do
            (log/trace "Got servers" reply)
            (rf/dispatch [:skylobby/assoc :servers reply]))
          (log/error reply))))
    db))

(defn get-server-key [server-url username]
  (str username "@" server-url))



(rf/reg-event-db :skylobby/get-battles
  (fn [db [_t server-key]]
    (log/info "Getting battles for" (pr-str server-key))
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :battles]]
      5000
      (fn [reply]
        (log/trace "Battles reply for" server-key reply)
        (if (sente/cb-success? reply)
          (rf/dispatch [:skylobby/assoc-in [:by-server server-key :battles] reply])
          (log/error reply))))
    db))

(rf/reg-event-db :skylobby/get-users
  (fn [db [_t server-key]]
    (log/info "Getting users for" (pr-str server-key))
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :users]]
      5000
      (fn [reply]
        (log/trace "Users reply for" server-key reply)
        (if (sente/cb-success? reply)
          (rf/dispatch [:skylobby/assoc-in [:by-server server-key :users] reply])
          (log/error reply))))
    db))

(rf/reg-event-db :skylobby/get-my-channels
  (fn [db [_t server-key]]
    (log/info "Getting my channels for" server-key)
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :my-channels]]
      5000
      (fn [reply]
        (log/trace "My channels reply for" server-key reply)
        (if (sente/cb-success? reply)
          (rf/dispatch [:skylobby/assoc-in [:by-server server-key :my-channels] reply])
          (log/error reply))))
    db))

(rf/reg-event-db :skylobby/get-battle
  (fn [db [_t server-key]]
    (log/info "Getting battle for" (pr-str server-key))
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :battle]]
      5000
      (fn [reply]
        (log/trace "Battle reply for" server-key reply)
        (if (sente/cb-success? reply)
          (rf/dispatch [:skylobby/update-battle server-key reply])
          (log/error reply))))
    db))

(rf/reg-event-db :skylobby/get-auto-unspec
  (fn [db [_t server-key]]
    (log/info "Getting auto unspec for" (pr-str server-key))
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :auto-unspec]]
      5000
      (fn [reply]
        (if (sente/cb-success? reply)
          (rf/dispatch [:skylobby/assoc-in [:by-server server-key :auto-unspec] reply])
          (log/error reply))))
    db))

(rf/reg-event-db :skylobby/get-auto-launch
  (fn [db [_t server-key]]
    (log/info "Getting auto launch for" (pr-str server-key))
    (chsk-send!
      [:skylobby/get-in [:auto-launch server-key]]
      5000
      (fn [reply]
        (if (sente/cb-success? reply)
          (rf/dispatch [:skylobby/assoc-in [:auto-launch server-key] reply])
          (log/error reply))))
    db))

(rf/reg-event-db :skylobby/poll
  (fn [db [_t interval-key poll-data frequency]]
    (log/info "Polling" interval-key poll-data)
    (when-let [interval (get db interval-key)]
      (js/clearInterval interval))
    (let [interval (js/setInterval
                     (fn []
                       (rf/dispatch poll-data))
                     (or frequency 10000))]
      (rf/dispatch poll-data)
      (assoc db interval-key interval))))

(rf/reg-event-db :skylobby/clear-poll
  (fn [db [_t interval-key]]
    (when-let [interval (get db interval-key)]
      (js/clearInterval interval))
    (dissoc db interval-key)))


(rf/reg-event-db :skylobby/get-chat
  (fn [db [_t server-key channel-name]]
    (log/info "Getting chat for" server-key channel-name)
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :channels channel-name]]
      5000
      (fn [reply]
        (log/trace "Chat reply for" server-key channel-name)
        (if (sente/cb-success? reply)
          (rf/dispatch [:skylobby/assoc-in [:by-server server-key :chat channel-name] reply])
          (log/error reply))))
    db))

(rf/reg-event-db :skylobby/join-battle
  (fn [db [_t server-key battle-id]]
    (chsk-send!
      [:skylobby/join-battle [server-key battle-id]]
      5000)
    db))

(rf/reg-event-db :skylobby/leave-battle
  (fn [db [_t server-key]]
    (chsk-send!
      [:skylobby/leave-battle [server-key]]
      5000)
    db))

(rf/reg-event-db :skylobby/connect-server
  (fn [db [_t opts]]
    (chsk-send!
      [:skylobby/connect-server opts]
      5000)
    db))

(rf/reg-event-db :skylobby/disconnect-server
  (fn [db [_t server-key]]
    (chsk-send!
      [:skylobby/disconnect-server {:server-key server-key}]
      5000)
    db))

(rf/reg-event-db :skylobby/start-battle
  (fn [db [_t server-key]]
    (chsk-send!
      [:skylobby/start-battle {:server-key server-key}]
      5000)
    db))

(rf/reg-event-db :skylobby/send-message
  (fn [db [_t server-key channel-name message]]
    (log/info "Sending message in" server-key channel-name ": " message)
    (chsk-send!
      [:skylobby/send-message {:server-key server-key
                               :channel-name channel-name
                               :message message}]
      5000)
    (assoc-in db [:by-server server-key :chat-message] "")))


(rf/reg-event-db :skylobby/set-server-username
  (fn [db [_t server-url username]]
    (log/info "Setting username for" server-url)
    (chsk-send!
      [:skylobby/assoc-in [[:logins server-url :username] username]]
      5000)
    (update db :username-drafts dissoc server-url)))

(rf/reg-event-db :skylobby/set-server-password
  (fn [db [_t server-url password]]
    (log/info "Setting password for" server-url)
    (chsk-send!
      [:skylobby/assoc-in [[:logins server-url :password] password]]
      5000)
    (update db :password-drafts dissoc server-url)))


(rf/reg-event-db :skylobby/set-away
  (fn [db [_t server-key away]]
    (log/info "Setting away in" server-key "to" away)
    (chsk-send!
      [:skylobby/set-away {:server-key server-key :away away}]
      5000)
    db))

(rf/reg-event-db :skylobby/set-auto-unspec
  (fn [db [_t server-key auto-unspec]]
    (log/info "Setting auto-unspec in" server-key "to" auto-unspec)
    (chsk-send!
      [:skylobby/set-auto-unspec {:server-key server-key :auto-unspec auto-unspec}]
      5000)
    db))

(rf/reg-event-db :skylobby/set-auto-launch
  (fn [db [_t server-key auto-launch]]
    (log/info "Setting auto-launch for" server-key "to" auto-launch)
    (chsk-send!
      [:skylobby/assoc-in [[:auto-launch server-key] auto-launch]]
      5000)
    db))

(rf/reg-event-db :skylobby/set-battle-mode
  (fn [db [_t server-key mode]]
    (log/info "Setting battle mode in" server-key "to" mode)
    (chsk-send!
      [:skylobby/set-battle-mode {:server-key server-key :mode mode}]
      5000)
    db))

(rf/reg-event-db :skylobby/set-battle-ready
  (fn [db [_t server-key ready]]
    (log/info "Setting battle ready in" server-key "to" ready)
    (chsk-send!
      [:skylobby/set-battle-mode {:server-key server-key :ready ready}]
      5000)
    db))


(rf/reg-event-db :skylobby/assoc
  (fn [db [_t k v]]
    (log/trace "Assoc" k v)
    (assoc db k v)))

(rf/reg-event-db :skylobby/assoc-in
  (fn [db [_t path v]]
    (log/trace "Assoc in" path v)
    (assoc-in db path v)))

(rf/reg-event-db :skylobby/update-battle
  (fn [db [_t server-key battle]]
    (log/trace "Updating battle in" server-key battle)
    (let [current-route (:current-route db)
          {:keys [path-params query-params]} current-route]
      (when (= (get-server-key (:server-url path-params) (:username query-params))
               server-key)
        (when-let [new-route (or (when (and battle (not (get-in db [:by-server server-key :battle])))
                                   :skylobby/room)
                                 (when (and (not battle) (get-in db [:by-server server-key :battle]))
                                   :skylobby/battles))]
          (log/info "Replacing state" new-route path-params query-params)
          (rfe/replace-state new-route path-params query-params))))
    (assoc-in db [:by-server server-key :battle] battle)))


;

(rf/reg-sub :skylobby/current-route
  (fn [db]
    (:current-route db)))

(rf/reg-sub :skylobby/servers
  (fn [db]
    (:servers db)))

(rf/reg-sub :skylobby/battles
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :battles])))

(rf/reg-sub :skylobby/username-drafts
  (fn [db _t]
    (get db :username-drafts)))

(rf/reg-sub :skylobby/password-drafts
  (fn [db _t]
    (get db :password-drafts)))

(rf/reg-sub :skylobby/users
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :users])))

(rf/reg-sub :skylobby/battle
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :battle])))

(rf/reg-sub :skylobby/battle-title
  (fn [db [_t server-key]]
    (let [{:keys [battle battles]} (get-in db [:by-server server-key])
          {:keys [battle-id]} battle]
      (or (get-in battles [battle-id :battle-title])
          battle-id))))

(rf/reg-sub :skylobby/chat
  (fn [db [_t server-key channel-name]]
    (get-in db [:by-server server-key :chat channel-name])))

(rf/reg-sub :skylobby/my-channels
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :my-channels])))

(rf/reg-sub :skylobby/active-servers
  (fn [db]
    (get-in db [:servers :active-servers])))

(rf/reg-sub :skylobby/chat-message
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :chat-message])))

(rf/reg-sub :skylobby/minimap-size
  (fn [db]
    (:minimap-size db)))

(rf/reg-sub :skylobby/minimap-type
  (fn [db]
    (:minimap-type db)))

(rf/reg-sub :skylobby/auto-launch
  (fn [db [_t server-key]]
    (boolean (get-in db [:auto-launch server-key]))))

(rf/reg-sub :skylobby/auto-unspec
  (fn [db [_t server-key]]
    (boolean (get-in db [:by-server server-key :auto-unspec]))))


(rf/reg-sub :skylobby/filter-battles
  (fn [db]
    (:skylobby/filter-battles db)))

(rf/reg-sub :skylobby/hide-empty-battles
  (fn [db]
    (boolean (:skylobby/hide-empty-battles db))))

(rf/reg-sub :skylobby/hide-locked-battles
  (fn [db]
    (boolean (:skylobby/hide-locked-battles db))))

(rf/reg-sub :skylobby/hide-passworded-battles
  (fn [db]
    (boolean (:skylobby/hide-passworded-battles db))))


(defn listen [query-v]
  @(rf/subscribe query-v))


(defn server-page [_]
  [:div
   [servers-nav/servers-nav]
   [server-nav/server-nav]])


(rf/reg-fx :push-state
  (fn [route]
    (apply rfe/push-state route)))


(def routes
  ["/"
   {
    :controllers
    [{:start (fn [_]
               (log/info "Start root")
               (rf/dispatch [:skylobby/get-servers]))}
     {:stop (fn [_]
              (log/info "Stop root"))}]}
   [""
    {:name      :skylobby/servers
     :view      servers-view/servers-page
     :link-text "Servers"
     :controllers
     [{;; Do whatever initialization needed for home page
       ;; I.e (re-frame/dispatch [:skylobby/events/load-something-with-ajax])
       :start (fn [& _params]
                (log/info "Entering servers page")
                (rf/dispatch [:skylobby/get-servers]))
       ;; Teardown can be done here.
       :stop  (fn [& _params]
                (log/info "Leaving servers page"))}]}]
   ["direct/:server-key"
    {:name :skylobby/direct-battle
     :view battle-view/room-page
     :link-text "Direct"
     :controllers
     [{:parameters {:path [:server-key]}
       :start (fn [params]
                (let [server-key (edn/read-string (get-in params [:path :server-key]))]
                  (log/info "Entering direct connect battle" (pr-str server-key))
                  (rf/dispatch [:skylobby/get-battles server-key])
                  (rf/dispatch [:skylobby/get-users server-key])
                  (rf/dispatch [:skylobby/get-battle server-key])
                  (rf/dispatch [:skylobby/get-auto-launch server-key])
                  (rf/dispatch [:skylobby/get-auto-unspec server-key])))
       :stop (fn [params]
               (let [server-key (get-in params [:path :server-key])]
                 (log/info "Leaving direct connect battle" server-key)))}]}]
   ["server/:server-url"
    {
     :view      server-page
     :parameters {:path {:server-url string?}
                  :query {:username string?}}
     :controllers
     [{:parameters {:path [:server-url]
                    :query [:username]}
       :start (fn [params]
                (let [server-url (get-in params [:path :server-url])
                      username (get-in params [:query :username])
                      server-key (get-server-key server-url username)]
                  (log/info "Entering page server" server-url "as user" username)
                  (rf/dispatch [:skylobby/get-battles server-key])
                  (rf/dispatch [:skylobby/get-users server-key])
                  (rf/dispatch [:skylobby/get-battle server-key])
                  (rf/dispatch [:skylobby/get-auto-launch server-key])
                  (rf/dispatch [:skylobby/get-auto-unspec server-key])))
                  ;(rf/dispatch [:skylobby/poll :poll-battles [:skylobby/get-battles server-key]])
                  ;(rf/dispatch [:skylobby/poll :poll-battle [:skylobby/get-battle server-key]])
                  ;(rf/dispatch [:skylobby/poll :poll-users [:skylobby/get-users server-key]])))
       :stop  (fn [params]
                (let [server-url (get-in params [:path :server-url])
                      username (get-in params [:query :username])]
                  (log/info "Leaving page server" server-url "as user" username)))}]}
                ;(rf/dispatch [:skylobby/clear-poll :poll-battles])
                ;(rf/dispatch [:skylobby/clear-poll :poll-battle])
                ;(rf/dispatch [:skylobby/clear-poll :poll-users]))}]}
    ["/battles"
     {:name      :skylobby/battles
      :view      battles-view/battles-page
      :link-text "Battles"
      :controllers
      [{;; Do whatever initialization needed for home page
        ;; I.e (re-frame/dispatch [:skylobby/events/load-something-with-ajax])
        :parameters {:path [:server-url]
                     :query [:username]}
        :start (fn [params]
                 (let [server-url (get-in params [:path :server-url])
                       username (get-in params [:query :username])
                       server-key (get-server-key server-url username)]
                   (log/info "Entering battles page for" server-key)))
                   ;(rf/dispatch [:skylobby/get-battles server-key])
                   ;(rf/dispatch [:skylobby/get-users server-key])
                   ;(rf/dispatch [:skylobby/get-battle server-key])))
        ;; Teardown can be done here.
        :stop  (fn [& _params]
                 (log/info "Leaving battles page"))}]}]
    ["/chat"
     [""
      {:name      :skylobby/channels
       :view      chat-view/channels-page
       :controllers
       [{
         :parameters {:path [:server-url :channel-name]
                      :query [:username]}
         :start (fn [params]
                  (let [server-key (get-server-key (get-in params [:path :server-url]) (get-in params [:query :username]))]
                    (log/info "Entering channels page" server-key)
                    (rf/dispatch [:skylobby/get-my-channels server-key])))
         :stop  (fn [_params]
                  (log/info "Leaving channels page"))}]}]
     ["/:channel-name"
      {:name      :skylobby/chat
       :view      chat-view/chat-page
       :parameters {:path {:channel-name string?}}
       :controllers
       [{
         :parameters {:path [:server-url :channel-name]
                      :query [:username]}
         :start (fn [params]
                  (let [server-key (get-server-key (get-in params [:path :server-url]) (get-in params [:query :username]))
                        channel-name (get-in params [:path :channel-name])]
                    (log/info "Entering chat page" server-key channel-name)
                    ;(rf/dispatch [:skylobby/get-my-channels server-key])
                    (rf/dispatch [:skylobby/get-chat server-key channel-name])))
         :stop  (fn [_params]
                  (log/info "Leaving chat page")
                  (rf/dispatch [:skylobby/clear-poll :poll-chat]))}]}]]
    ["/room"
     {:name      :skylobby/room
      :view      battle-view/room-page
      :link-text "Room"
      :controllers
      [{
        :parameters {:path [:server-url]
                     :query [:username]}
        :start (fn [params]
                 (let [server-url (get-in params [:path :server-url])
                       username (get-in params [:query :username])
                       server-key (get-server-key server-url username)]
                   (log/info "Entering battle room for" server-key)))
                   ;(rf/dispatch [:skylobby/get-battles server-key])
                   ;(rf/dispatch [:skylobby/get-battle server-key])
                   ;(rf/dispatch [:skylobby/get-users server-key])))
        :stop  (fn [& _params]
                 (log/info "Leaving room"))}]}]]])
                 ;(rf/dispatch [:skylobby/clear-poll :poll-chat]))}]}]]])


(defn on-navigate [new-match]
  (when new-match
    (rf/dispatch [:skylobby/navigated new-match])))

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


(defn router-component [{:keys [router]}]
  (let [current-route (listen [:skylobby/current-route])]
    [:div
     (when current-route
       [(-> current-route :data :view) {:router router}])]))


(def debug? ^boolean goog.DEBUG)

(defn dev-setup []
  (when debug?
    (enable-console-print!)
    (println "dev mode")))


(defn ^:dev/after-load init []
  (println "init")
  (rf/clear-subscription-cache!)
  (rf/dispatch-sync [:skylobby/initialize-db])
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
