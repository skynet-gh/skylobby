(ns skylobby.js
  (:require
    [cljs.reader :as reader]
    [clojure.string :as string]
    [goog.string :as gstring]
    goog.string.format
    ["moment" :as moment]
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


(declare nav)


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


(defmethod -event-msg-handler :skylobby/servers
  [{:keys [?data]}]
  (log/trace "Servers: %s" ?data)
  (rf/dispatch [::assoc :servers ?data]))

(defmethod -event-msg-handler :skylobby/battles
  [{:keys [?data]}]
  (log/trace "Battles: %s" ?data)
  (let [{:keys [server-key battles]} ?data]
    (rf/dispatch [::assoc-in [:by-server server-key :battles] battles])))

(defmethod -event-msg-handler :skylobby/users
  [{:keys [?data]}]
  (log/trace "Users: %s" ?data)
  (let [{:keys [server-key users]} ?data]
    (rf/dispatch [::assoc-in [:by-server server-key :users] users])))

(defmethod -event-msg-handler :skylobby/battle
  [{:keys [?data]}]
  (log/trace "Battle: %s" ?data)
  (let [{:keys [server-key battle]} ?data]
    (rf/dispatch [::update-battle server-key battle])))

(defmethod -event-msg-handler :skylobby/chat
  [{:keys [?data]}]
  (log/trace "Chat: %s" ?data)
  (let [{:keys [channel-name server-key channel-data]} ?data]
    (rf/dispatch [::assoc-in [:by-server server-key :chat channel-name] channel-data])))

(defmethod -event-msg-handler :skylobby/auto-launch
  [{:keys [?data]}]
  (rf/dispatch [::assoc :auto-launch ?data]))

(defmethod -event-msg-handler :skylobby/auto-unspec
  [{:keys [?data]}]
  (let [{:keys [auto-unspec server-key]} ?data]
    (rf/dispatch [::assoc-in [:by-server server-key :auto-unspec] auto-unspec])))


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

(defn get-server-key [server-url username]
  (str username "@" server-url))

(rf/reg-event-db ::get-battles
  (fn [db [_t server-key]]
    (log/info "Getting battles for" server-key)
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :battles]]
      5000
      (fn [reply]
        (log/trace "Battles reply for" server-key reply)
        (if (sente/cb-success? reply)
          (rf/dispatch [::assoc-in [:by-server server-key :battles] reply])
          (log/error reply))))
    db))

(rf/reg-event-db ::get-users
  (fn [db [_t server-key]]
    (log/info "Getting users for" server-key)
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :users]]
      5000
      (fn [reply]
        (log/trace "Users reply for" server-key reply)
        (if (sente/cb-success? reply)
          (rf/dispatch [::assoc-in [:by-server server-key :users] reply])
          (log/error reply))))
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
          (rf/dispatch [::assoc-in [:by-server server-key :my-channels] reply])
          (log/error reply))))
    db))

(defn battle-channel-name [{:keys [battle-id channel-name]}]
  (or channel-name
      (str "__battle__" battle-id)))

(rf/reg-event-db ::get-battle
  (fn [db [_t server-key]]
    (log/info "Getting battle for" server-key)
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :battle]]
      5000
      (fn [reply]
        (log/trace "Battle reply for" server-key reply)
        (if (sente/cb-success? reply)
          (do
            (rf/dispatch [::update-battle server-key reply]))
            ;(rf/dispatch [::poll :poll-chat [::get-chat server-key (battle-channel-name reply)]]))
          (log/error reply))))
    db))

(rf/reg-event-db ::get-auto-unspec
  (fn [db [_t server-key]]
    (log/info "Getting auto unspec for" server-key)
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :auto-unspec]]
      5000
      (fn [reply]
        (if (sente/cb-success? reply)
          (rf/dispatch [::assoc-in [:by-server server-key :auto-unspec] reply])
          (log/error reply))))
    db))

(rf/reg-event-db ::get-auto-launch
  (fn [db [_t server-key]]
    (log/info "Getting auto launch for" server-key)
    (chsk-send!
      [:skylobby/get-in [:auto-launch server-key]]
      5000
      (fn [reply]
        (if (sente/cb-success? reply)
          (rf/dispatch [::assoc-in [:auto-launch server-key] reply])
          (log/error reply))))
    db))

(rf/reg-event-db ::poll
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

(rf/reg-event-db ::clear-poll
  (fn [db [_t interval-key]]
    (when-let [interval (get db interval-key)]
      (js/clearInterval interval))
    (dissoc db interval-key)))


(rf/reg-event-db ::get-chat
  (fn [db [_t server-key channel-name]]
    (log/info "Getting chat for" server-key channel-name)
    (chsk-send!
      [:skylobby/get-in [:by-server server-key :channels channel-name]]
      5000
      (fn [reply]
        (log/trace "Chat reply for" server-key channel-name)
        (if (sente/cb-success? reply)
          (rf/dispatch [::assoc-in [:by-server server-key :chat channel-name] reply])
          (log/error reply))))
    db))

(rf/reg-event-db ::join-battle
  (fn [db [_t server-key battle-id]]
    (chsk-send!
      [:skylobby/join-battle [server-key battle-id]]
      5000)
    db))

(rf/reg-event-db ::leave-battle
  (fn [db [_t server-key]]
    (chsk-send!
      [:skylobby/leave-battle [server-key]]
      5000)
    db))

(rf/reg-event-db ::connect-server
  (fn [db [_t server-url]]
    (chsk-send!
      [:skylobby/connect-server {:server-url server-url}]
      5000)
    db))

(rf/reg-event-db ::disconnect-server
  (fn [db [_t server-key]]
    (chsk-send!
      [:skylobby/disconnect-server {:server-key server-key}]
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
    (assoc-in db [:by-server server-key :chat-message] "")))



(rf/reg-event-db ::set-away
  (fn [db [_t server-key away]]
    (log/info "Setting away in" server-key "to" away)
    (chsk-send!
      [:skylobby/set-away {:server-key server-key :away away}]
      5000)
    db))

(rf/reg-event-db ::set-auto-unspec
  (fn [db [_t server-key auto-unspec]]
    (log/info "Setting auto-unspec in" server-key "to" auto-unspec)
    (chsk-send!
      [:skylobby/set-auto-unspec {:server-key server-key :auto-unspec auto-unspec}]
      5000)
    db))

(rf/reg-event-db ::set-auto-launch
  (fn [db [_t server-key auto-launch]]
    (log/info "Setting auto-launch for" server-key "to" auto-launch)
    (chsk-send!
      [:skylobby/assoc-in [[:auto-launch server-key] auto-launch]]
      5000)
    db))

(rf/reg-event-db ::set-battle-mode
  (fn [db [_t server-key mode]]
    (log/info "Setting battle mode in" server-key "to" mode)
    (chsk-send!
      [:skylobby/set-battle-mode {:server-key server-key :mode mode}]
      5000)
    db))

(rf/reg-event-db ::set-battle-ready
  (fn [db [_t server-key ready]]
    (log/info "Setting battle ready in" server-key "to" ready)
    (chsk-send!
      [:skylobby/set-battle-mode {:server-key server-key :ready ready}]
      5000)
    db))


(rf/reg-event-db ::assoc
  (fn [db [_t k v]]
    (log/trace "Assoc" k v)
    (assoc db k v)))

(rf/reg-event-db ::assoc-in
  (fn [db [_t path v]]
    (log/trace "Assoc in" path v)
    (assoc-in db path v)))

(rf/reg-event-db ::update-battle
  (fn [db [_t server-key battle]]
    (log/trace "Updating battle in" server-key battle)
    (when-let [new-route (or (when (and battle (not (get-in db [:by-server server-key :battle])))
                               ::room)
                             (when (and (not battle) (get-in db [:by-server server-key :battle]))
                               ::battles))]
      (let [current-route (:current-route db)
            {:keys [path-params query-params]} current-route]
        (log/info "Replacing state" new-route path-params query-params)
        (rfe/replace-state new-route path-params query-params)))
    (assoc-in db [:by-server server-key :battle] battle)))


;

(rf/reg-sub ::current-route
  (fn [db]
    (:current-route db)))

(rf/reg-sub ::servers
  (fn [db]
    (:servers db)))

(rf/reg-sub ::battles
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :battles])))

(rf/reg-sub ::users
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :users])))

(rf/reg-sub ::battle
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :battle])))

(rf/reg-sub ::battle-title
  (fn [db [_t server-key]]
    (let [{:keys [battle battles]} (get-in db [:by-server server-key])
          {:keys [battle-id]} battle]
      (or (get-in battles [battle-id :battle-title])
          battle-id))))

(rf/reg-sub ::chat
  (fn [db [_t server-key channel-name]]
    (get-in db [:by-server server-key :chat channel-name])))

(rf/reg-sub ::my-channels
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :my-channels])))

(rf/reg-sub ::active-servers
  (fn [db]
    (get-in db [:servers :active-servers])))

(rf/reg-sub ::chat-message
  (fn [db [_t server-key]]
    (get-in db [:by-server server-key :chat-message])))

(rf/reg-sub ::minimap-size
  (fn [db]
    (:minimap-size db)))

(rf/reg-sub ::minimap-type
  (fn [db]
    (:minimap-type db)))

(rf/reg-sub ::auto-launch
  (fn [db [_t server-key]]
    (boolean (get-in db [:auto-launch server-key]))))

(rf/reg-sub ::auto-unspec
  (fn [db [_t server-key]]
    (boolean (get-in db [:by-server server-key :auto-unspec]))))


(defn listen [query-v]
  @(rf/subscribe query-v))


(defn servers-page [_]
  (let [{:keys [active-servers logins servers]} (listen [::servers])
        active-server-keys (set (map :server-key active-servers))]
    [:div
     [nav]
     [:div {:class "flex justify-center"}
      [:table
       [:thead
        [:tr
         [:th "Alias"]
         [:th "URL"]
         [:th "Username"]
         [:th "Password"]
         [:th "Actions"]]]
       [:tbody
        (for [[server-url server-config] servers]
          (let [username (get-in logins [server-url :username])
                server-key (get-server-key server-url username)]
            ^{:key server-url}
            [:tr
             [:td (:alias server-config)]
             [:td server-url]
             [:td
              [:input
               {:read-only true
                :value username}]]
             [:td
              [:input
               {:read-only true
                :type "password"
                :value (get-in logins [server-url :password])}]]
             [:td
              (let [logged-in (contains? active-server-keys server-key)]
                [:button
                 {:class "f6 link dim ph3 pv1 mb2 dib white bg-near-black"
                  :on-click #(rf/dispatch
                               (if logged-in
                                 [::disconnect-server server-key]
                                 [::connect-server server-url]))}
                 (if logged-in
                   "Disconnect"
                   "Login")])]]))]]]]))


(defn href
  "Return relative url for given route. Url can be used in HTML links."
  ([k]
   (href k nil nil))
  ([k params]
   (href k params nil))
  ([k params query]
   (rfe/href k params query)))

(def header-class
  "f6 ba br-pill no-underline pv2 ph3 dib")

(def server-route-names
  {::battles "Battles"
   ::channels "Chat"})

(defn server-nav []
  (let [current-route (listen [::current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (get-server-key server-url username)
        battle (listen [::battle server-key])
        route-names (concat
                      (when battle
                        [::room])
                      [::battles ::channels])
        battle-title (str "Battle: " (listen [::battle-title server-key]))]
    [:div {:class "flex justify-center"}
     (for [route-name route-names]
       ^{:key route-name}
       [:div {:key route-name
              :class "pa3"}
        [:a
         {:class (str header-class " "
                   (if (or (= route-name (-> current-route :data :name))
                           (and (= ::chat route-name)
                                (= ::channels (-> current-route :data :name))))
                     "gold"
                     "gray"))
          :href (href route-name {:server-url server-url} {:username username})}
         (or (get server-route-names route-name)
             (str "Battle: " battle-title))]])]))

(defn format-hours [moment]
  (.format moment "HH:mm:ss"))

(defn battles-page [_]
  (let [current-route (listen [::current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (get-server-key server-url username)
        current-battle (listen [::battle server-key])
        battles (->> (listen [::battles server-key])
                     (sort-by (comp count :users second))
                     reverse)
        users (listen [::users server-key])]
    [:div
     [nav]
     [server-nav]
     [:div {:class "flex justify-center"}
      [:table
       {:style {:flex-grow 1}}
       [:thead
        [:tr
         [:th "Actions"]
         [:th "ID"]
         [:th "Title"]
         [:th "Status"]
         [:th "Map"]
         [:th "Play (Spec)"]
         [:th "Game"]
         [:th "Engine"]
         [:th "Owner"]]]
       [:tbody
        (for [[battle-id battle] battles]
          (let [{:keys [host-username]} battle
                {:keys [game-start-time] :as user-data} (get users host-username)
                ingame (-> user-data :client-status :ingame)]
            ^{:key battle-id}
            [:tr
             (let [in-battle (= battle-id (:battle-id current-battle))]
               [:td
                [:button
                 {:class "f6 link dim ph3 pv1 mb2 dib white bg-near-black"
                  :on-click #(rf/dispatch
                               (if in-battle
                                 [::leave-battle server-key]
                                 [::join-battle server-key battle-id]))}
                 (if in-battle
                   "Leave"
                   "Join")]])
             [:td battle-id]
             [:td (:battle-title battle)]
             [:td
              {:class "flex ib items-center"}
              (if (or (= "1" (:battle-locked battle))
                      (= "1" (:battle-passworded battle)))
                [:span.material-icons "lock"]
                [:span
                 {:style
                  {
                   :width "24px"
                   :height "24px"}}])
              (if ingame
                [:span.material-icons
                 {:color "red"}
                 "radio_button_checked"]
                [:span.material-icons
                 {:color "green"}
                 "radio_button_unchecked"])
              (when (and ingame game-start-time)
                (let [diff (- (.now js/Date) game-start-time)
                      duration (moment/duration diff "milliseconds")]
                  [:span.ml1 (str (format-hours (moment/utc (.asMilliseconds duration))))]))]
             [:td (:battle-map battle)]
             (let [total-user-count (count (:users battle))
                   spec-count (:battle-spectators battle)]
               [:td (str (- total-user-count spec-count)
                         " (" spec-count ")")])
             [:td (:battle-modname battle)]
             [:td (str (:battle-engine battle) " " (:battle-version battle))]
             [:td (:host-username battle)]]))]]]]))

(defn my-channels-nav [{:keys [server-key server-url username] :as params}]
  [:div {:class "flex justify-center"}
   (for [[channel-name _] (listen [::my-channels server-key])]
     [:div {:key channel-name
            :class "pa3"}
      [:a
       {:class (str header-class " "
                 (if (= channel-name (:channel-name params))
                   "green"
                   "gray"))
        :href (href ::chat {:server-url server-url :channel-name channel-name} {:username username})}
       channel-name]])])

(defn chat-history [{:keys [server-key channel-name]}]
  (let [chat (listen [::chat server-key channel-name])
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
         (str "[" (format-hours (.local (moment/utc timestamp))) "] ")]
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
                     (rf/dispatch [::send-message server-key channel-name message])
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
      :on-change #(rf/dispatch [::assoc-in [:by-server server-key :chat-message] (-> % .-target .-value)])
      :style {:flex-grow 1}
      :type "text"
      :value (listen [::chat-message server-key])}]]])

(defn chat-view [params]
  [:div
   [:div
    [auto-scroll-chat-history params]]
   [chat-input params]])


(defn channels-page [_]
  (let [current-route (listen [::current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        channel-name (-> parameters :path :channel-name)
        username (-> parameters :query :username)
        server-key (get-server-key server-url username)]
    [:div
     [nav]
     [server-nav]
     [:div {:class "flex justify-center"}
      [my-channels-nav {:channel-name channel-name :server-key server-key :server-url server-url :username username}]]]))

(defn chat-page [_]
  (let [current-route (listen [::current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        channel-name (-> parameters :path :channel-name)
        username (-> parameters :query :username)
        server-key (get-server-key server-url username)]
    [:div
     {:class "flex"
      :style {:flex-flow "column"
              :height "100%"}}
     [nav]
     [server-nav]
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

(defn spring-color-to-web [spring-color]
  (let [i (cond
            (string? spring-color)
            (js/parseInt spring-color)
            (number? spring-color)
            (int spring-color)
            :else 0)
        ; https://stackoverflow.com/a/11866980
        b (bit-and i 0xFF)
        g (unsigned-bit-shift-right (bit-and i 0xFF00) 8)
        r (unsigned-bit-shift-right (bit-and i 0xFF0000) 16)]
    ; string colors reverse r and b
    (str "#" (.padStart (.toString b 16) 2 "0")
             (.padStart (.toString g 16) 2 "0")
             (.padStart (.toString r 16) 2 "0"))))

(def minimap-sizes
  [256 384 512])
(def default-minimap-size
  (first minimap-sizes))

(def minimap-types
  ["minimap" "metalmap" "heightmap"])
(def default-minimap-type "minimap")

(defn to-number [x]
  (when (boolean? x)
    (if (true? x)
      1 0)))

(defn room-page [_]
  (let [current-route (listen [::current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (get-server-key server-url username)
        {:keys [scripttags] :as battle} (listen [::battle server-key])
        users (listen [::users server-key])
        minimap-size (or (listen [::minimap-size]) default-minimap-size)
        minimap-px (str minimap-size "px")
        minimap-type (or (listen [::minimap-type]) default-minimap-type)]
    [:div
     {:class "flex"
      :style {:flex-flow "column"
              :height "100%"}}
     [nav]
     [server-nav]
     [:div
      {:class "flex justify-center"}
      [:button
       {:on-click (fn [] (rf/dispatch [::leave-battle server-key]))}
       "Leave Battle"]]
     (let [battles (listen [::battles server-key])
           battle-details (get battles (:battle-id battle))
           map-name (:battle-map battle-details)
           users-parsed (->> battle
                             :users
                             (map
                               (fn [[username user]]
                                 (let [username-lc (string/lower-case username)
                                       {:strs [skill skilluncertainty]} (get-in scripttags ["game" "players" username-lc])
                                       uncertainty (js/parseInt skilluncertainty)
                                       user-details (get users username)
                                       color (spring-color-to-web (:team-color user))]
                                   [username
                                    (assoc user
                                           :user-details user-details
                                           :color color
                                           :username-lc username-lc
                                           :skill skill
                                           :uncertainty uncertainty)])))
                             (sort-by (juxt (comp not :mode :battle-status second)
                                            (comp (fnil - 0) :bot :client-status :user second)
                                            (comp (fnil int 0) :ally :battle-status second)
                                            (comp (fnil int 0) :id :battle-status second)
                                            (comp (fnil int 0) :skill second)
                                            first)))]
       [:div
        {:class "flex justify-center"
         :style {:flex "0 1 auto"
                 :height minimap-px}}
        [:table
         {:class "flex"
          :style
          {:flex-grow 1
           :overflow-y "auto"
           :width "100%"
           :display "block"}}
         [:thead
          [:tr
           [:th "Nickname"]
           [:th "Skill"]
           [:th "Status"]
           [:th "Ally"]
           [:th "Team"]
           [:th "Color"]
           [:th "Spectator"]
           [:th "Faction"]
           [:th "Rank"]
           [:th "Country"]
           [:th "Bonus"]]]
         [:tbody
          (for [[username user] users-parsed]
            (let [
                  {:keys [battle-status color user-details]} user
                  {:keys [client-status]} user-details
                  sync-status (int (or (:sync battle-status) 0))]
              ^{:key username}
              [:tr
               {:style {:white-space "nowrap"}}
               [:td
                {:style
                 (merge
                   {:width "90%"
                    :text-align "center"
                    :text-shadow "1px 1px #000000"
                    :font-weight (if (:mode battle-status) "bold" "normal")}
                   (when (:mode battle-status)
                     {:color color}))}
                username]
               (let [{:keys [skill uncertainty]} user]
                 [:td
                  {:style
                   {:width "10%"}}
                  skill
                  " "
                  (when (number? uncertainty)
                    (apply str (repeat uncertainty "?")))])
               [:td
                [:span.material-icons
                 (cond
                   (:bot client-status)
                   "smart_toy"
                   (not (:mode battle-status))
                   "search"
                   :else
                   "person")]
                [:span.material-icons
                 {:color
                  (case sync-status
                    1 "green"
                    2 "red"
                    "gold")}
                 (case sync-status
                   1 "sync"
                   2 "sync_disabled"
                   "sync_problem")]
                (when (:ingame client-status)
                  [:span.material-icons
                   {:color "red"}
                   "sports_esports"])
                (when (:away client-status)
                  [:span.material-icons
                   {:color "grey"}
                   "bed"])]
               [:td (:ally battle-status)]
               [:td (:id battle-status)]
               [:td
                {:style {:background color}}]
                ; color])
               [:td (str (not (:mode battle-status)))]
               [:td (str (:side battle-status))]
               [:td (str (:rank client-status))]
               [:td (str (:country user-details))]
               [:td (str (:handicap battle-status))]]))]]
        [:div
         {:style {:min-width "300px"}}
         [:div
          (str (:battle-version battle-details))]
         [:div
          (str (:battle-modname battle-details))]
         [:div
          (str (:battle-map battle-details))]]
        [:div
         {:class "flex justify-center"
          :style {
                  :min-width minimap-px
                  :min-height minimap-px
                  :max-width minimap-px
                  :max-height minimap-px}}
         (when map-name
           [:img
            {:src (str "http://localhost:12345/minimap-image?map-name=" map-name
                       (when minimap-type
                         (str "&minimap-type=" minimap-type)))
             :alt (str map-name)
             :style {:max-width "100%"
                     :max-height "100%"
                     :object-fit "contain"}}])]])
     (let [my-battle-status (get-in battle [:users username :battle-status])]
       [:div {:class "flex justify-center"}
        [:div {:class "flex items-center mb2 mh2"}
         [:input
          {:class "mr2"
           :type "checkbox"
           :on-change #(rf/dispatch [::set-auto-launch server-key (-> % .-target .-checked)])
           :checked (listen [::auto-launch server-key])}]
         [:label {:class "lh-copy"} " Auto launch "]]
        [:select
         {:class "mv2 mh2 ph1 pv1"
          :on-change #(rf/dispatch [::set-away server-key (= "true" (-> % .-target .-value))])
          :value (boolean (get-in users [username :client-status :away]))}
         [:option
          {:value false}
          "Here"]
         [:option
          {:value true}
          "Away"]]
        [:select
         {:class "mv2 mh2 ph1 pv1"
          :on-change #(rf/dispatch [::set-battle-mode server-key (= "true" (-> % .-target .-value))])
          :value (boolean (:mode my-battle-status))}
         [:option
          {:value true}
          "Playing"]
         [:option
          {:value false}
          "Spectating"]]
        [:div {:class "flex items-center mb2 mh2"}
         [:input
          {:class "mr2"
           :type "checkbox"
           :on-change #(rf/dispatch [::set-auto-unspec server-key (-> % .-target .-checked)])
           :checked (listen [::auto-unspec server-key])}]
         [:label {:class "lh-copy"} " Auto unspec "]]
        (when (:mode my-battle-status)
          [:div {:class "flex items-center mb2 mh2"}
           [:input
            {:class "mr2"
             :on-change #(rf/dispatch [::set-battle-ready server-key (-> % .-target .-checked)])
             :type "checkbox"
             :checked (boolean (:raedy my-battle-status))}]
           [:label {:class "lh-copy"} " Ready "]])
        [:span {:style {:flex-grow 1}}]
        [:button
         {:class "f3 mh4 mv2"
          :on-click #(rf/dispatch [::start-battle server-key])}
         "Join Game"]
        [:span {:style {:flex-grow 1}}]
        [:div {:class "flex items-center mb2 mh2"}
         [:label " Minimap type: "]
         [:select
          {:class "mv2 mh2 ph1 pv1"
           :on-change #(rf/dispatch [::assoc :minimap-type (-> % .-target .-value)])
           :value minimap-type}
          (for [minimap-type minimap-types]
            ^{:key minimap-type}
            [:option (str minimap-type)])]]
        [:div {:class "flex items-center mb2 mh2"}
         [:label " Minimap size: "]
         [:select
          {:class "mv2 mh2 ph1 pv1"
           :on-change #(rf/dispatch [::assoc :minimap-size (-> % .-target .-value js/parseInt)])
           :value minimap-size}
          (for [size minimap-sizes]
            ^{:key size}
            [:option (str size)])]]])
     [:div
      {:class "flex flex-column"
       :style {
               :flex "1 1 auto"
               :flex-flow "column"
               :overflow-y "auto"}}
      ;[chat-view {:channel-name (battle-channel-name battle) :server-key server-key}]
      [auto-scroll-chat-history {:channel-name (battle-channel-name battle) :server-key server-key}]]
     [:div {:class "flex flex-column"}
      [chat-input {:channel-name (battle-channel-name battle) :server-key server-key}]]]))


(defn server-page [_]
  [:div
   [nav]
   [server-nav]])


(rf/reg-fx :push-state
  (fn [route]
    (apply rfe/push-state route)))


(def routes
  ["/"
   {
    :controllers
    [{:start (fn [_]
               (log/info "Start root")
               (rf/dispatch [::get-servers]))}
     {:stop (fn [_]
              (log/info "Stop root"))}]}
   [""
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
       :start (fn [params]
                (let [server-url (get-in params [:path :server-url])
                      username (get-in params [:query :username])
                      server-key (get-server-key server-url username)]
                  (log/info "Entering page server" server-url "as user" username)
                  (rf/dispatch [::get-battles server-key])
                  (rf/dispatch [::get-users server-key])
                  (rf/dispatch [::get-battle server-key])
                  (rf/dispatch [::get-auto-launch server-key])
                  (rf/dispatch [::get-auto-unspec server-key])))
                  ;(rf/dispatch [::poll :poll-battles [::get-battles server-key]])
                  ;(rf/dispatch [::poll :poll-battle [::get-battle server-key]])
                  ;(rf/dispatch [::poll :poll-users [::get-users server-key]])))
       :stop  (fn [params]
                (let [server-url (get-in params [:path :server-url])
                      username (get-in params [:query :username])]
                  (log/info "Leaving page server" server-url "as user" username)))}]}
                ;(rf/dispatch [::clear-poll :poll-battles])
                ;(rf/dispatch [::clear-poll :poll-battle])
                ;(rf/dispatch [::clear-poll :poll-users]))}]}
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
                       username (get-in params [:query :username])
                       server-key (get-server-key server-url username)]
                   (log/info "Entering battles page for" server-key)))
                   ;(rf/dispatch [::get-battles server-key])
                   ;(rf/dispatch [::get-users server-key])
                   ;(rf/dispatch [::get-battle server-key])))
        ;; Teardown can be done here.
        :stop  (fn [& _params]
                 (log/info "Leaving battles page"))}]}]
    ["/chat"
     [""
      {:name      ::channels
       :view      channels-page
       :controllers
       [{
         :parameters {:path [:server-url :channel-name]
                      :query [:username]}
         :start (fn [params]
                  (let [server-key (get-server-key (get-in params [:path :server-url]) (get-in params [:query :username]))]
                    (log/info "Entering channels page" server-key)
                    (rf/dispatch [::get-my-channels server-key])))
         :stop  (fn [_params]
                  (log/info "Leaving channels page"))}]}]
     ["/:channel-name"
      {:name      ::chat
       :view      chat-page
       :parameters {:path {:channel-name string?}}
       :controllers
       [{
         :parameters {:path [:server-url :channel-name]
                      :query [:username]}
         :start (fn [params]
                  (let [server-key (get-server-key (get-in params [:path :server-url]) (get-in params [:query :username]))
                        channel-name (get-in params [:path :channel-name])]
                    (log/info "Entering chat page" server-key channel-name)
                    ;(rf/dispatch [::get-my-channels server-key])
                    (rf/dispatch [::get-chat server-key channel-name])))
         :stop  (fn [_params]
                  (log/info "Leaving chat page")
                  (rf/dispatch [::clear-poll :poll-chat]))}]}]]
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
                       username (get-in params [:query :username])
                       server-key (get-server-key server-url username)]
                   (log/info "Entering battle room for" server-key)))
                   ;(rf/dispatch [::get-battles server-key])
                   ;(rf/dispatch [::get-battle server-key])
                   ;(rf/dispatch [::get-users server-key])))
        :stop  (fn [& _params]
                 (log/info "Leaving room"))}]}]]])
                 ;(rf/dispatch [::clear-poll :poll-chat]))}]}]]])


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


(defn nav []
  (let [current-route (listen [::current-route])
        {:keys [parameters]} current-route
        server-url (-> parameters :path :server-url)
        username (-> parameters :query :username)
        server-key (get-server-key server-url username)]
    [:div
     [:div {:class "flex justify-center"}
      (let [route-name ::servers]
        [:div {:key route-name
               :class "pa3"}
         [:a
          {:class (str header-class " "
                    (if (= route-name (-> current-route :data :name))
                      "purple"
                      "gray"))
           :href (href route-name)}
          "Servers"]])
      (for [{:keys [server-id server-url username]} (filter :server-id (listen [::active-servers]))]
        [:div {:key server-id
               :class "pa3"}
         [:a
          {
           :class (str header-class " "
                    (if (= server-id server-key)
                      "purple"
                      "gray"))
           :href (href ::battles {:server-url server-url} {:username username})}
          server-id]])]]))


(defn router-component [{:keys [router]}]
  (let [current-route (listen [::current-route])]
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
  (rf/dispatch-sync [::initialize-db])
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
