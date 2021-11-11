(ns skylobby.server
  (:require
    aleph.http
    [clojure.spec.alpha :as s]
    [clojure.java.io :as io]
    [hiccup.core :as hiccup]
    [muuntaja.core :as m]
    [reitit.ring :as ring]
    reitit.coercion.spec
    [reitit.ring.coercion :as rrc]
    [reitit.ring.middleware.exception :as exception]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [reitit.ring.middleware.parameters :as parameters]
    [ring.middleware.anti-forgery :as anti-forgery]
    [ring.middleware.content-type :as content-type]
    [skylobby.event :as event]
    [skylobby.fs :as fs]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [spring-lobby.fs.sdfz :as replay]
    [taoensso.sente :as sente]
    [taoensso.sente.server-adapters.aleph :refer (get-sch-adapter)]
    [taoensso.timbre :as log]))


(def ^:dynamic *state nil)


; sente  https://github.com/ptaoussanis/sente/blob/master/example-project/src/example/server.clj

(let [packer :edn
      chsk-server (sente/make-channel-socket-server!
                    (get-sch-adapter)
                    {:authorized?-fn nil
                     :csrf-token-fn nil ; TODO
                     :packer packer
                     :wrap-recv-evs? false})
      {:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]} chsk-server]
  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn)
  (def connected-uids connected-uids))


(add-watch connected-uids :connected-uids
  (fn [_ _ old-ids new-ids]
    (when (not= old-ids new-ids)
      (log/infof "Connected uids change: %s" new-ids))))


(defmulti -event-msg-handler :id)


(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (log/info id ?data event)
  (-event-msg-handler ev-msg)) ; Handle event-msgs on a single thread

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (log/warnf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-server event}))))


(def server-key "[Z]kynet@bar.teifion.co.uk:8201")

(defmethod -event-msg-handler
  :skylobby/get
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (log/info "Get" ?data)
    (when ?reply-fn
      (?reply-fn get @*state ?data))))

(defmethod -event-msg-handler
  :skylobby/get-in
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (log/info "Get in" ?data)
    (when ?reply-fn
      (?reply-fn (get-in @*state ?data)))))

(defmethod -event-msg-handler
  :skylobby/assoc-in
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        [path v] ?data]
    (log/info "Assoc in" path v)
    (swap! *state assoc-in path v)))

(defn servers-data [state]
  {:active-servers (->> state
                        :by-server
                        (map
                          (fn [[server-key server-data]]
                            (let [client-data (:client-data server-data)]
                              {:server-id server-key
                               :server-key server-key
                               :accepted (:accepted server-data)
                               :client? (boolean (:client client-data))
                               :client-deferred? (boolean (:client-deferred client-data))
                               :server-url (:server-url client-data)
                               :username (:username client-data)}))))
   :logins (:logins state)
   :servers (:servers state)})

(defmethod -event-msg-handler
  :skylobby/get-servers
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (log/info "Get servers data" ?data)
    (when ?reply-fn
      (?reply-fn (servers-data @*state)))))

(defmethod -event-msg-handler
  :skylobby/join-battle
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        [server-key battle-id] ?data
        server-data (get-in @*state [:by-server server-key])]
    (event/join-battle *state (assoc server-data :selected-battle battle-id))))

(defmethod -event-msg-handler
  :skylobby/leave-battle
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        [server-key] ?data
        server-data (get-in @*state [:by-server server-key])]
    (event/leave-battle *state server-data)))

(defmethod -event-msg-handler
  :skylobby/connect-server
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        {:keys [server-url]} ?data
        {:keys [logins servers]} @*state
        login (get logins server-url)
        username (:username login)
        server-key (u/server-key {:server-url server-url
                                  :username username})]
    (event/connect *state {:server [server-url (get servers server-url)]
                           :server-key server-key
                           :password (:password login)
                           :username username})))

(defmethod -event-msg-handler
  :skylobby/disconnect-server
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        {:keys [server-key]} ?data]
    (event/disconnect *state server-key)))

(defmethod -event-msg-handler
  :skylobby/start-battle
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        {:keys [server-key]} ?data
        {:keys [by-server by-spring-root servers spring-isolation-dir]} @*state
        {:keys [battle client-data] :as server-data} (get by-server server-key)
        {:keys [server-url username]} client-data
        spring-root (get-in servers [server-url :spring-isolation-dir] spring-isolation-dir)
        my-battle-status (get-in battle [:users username :battle-status])]
    (event/start-battle *state (merge server-data
                                 {:am-host false ; TODO
                                  :am-spec true ; TODO
                                  :battle-status my-battle-status
                                  :channel-name (u/battle-channel-name battle)
                                  :host-ingame true
                                  :spring-isolation-dir spring-root}
                                 (resource/spring-root-resources spring-root by-spring-root)))))

(defmethod -event-msg-handler
  :skylobby/send-message
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        {:keys [server-key channel-name message]} ?data
        {:keys [by-server]} @*state
        {:keys [client-data]} (get by-server server-key)]
    (event/send-message *state {:channel-name channel-name
                                :client-data client-data
                                :message message
                                :server-key server-key})))

(defmethod -event-msg-handler
  :skylobby/set-battle-mode
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        {:keys [server-key mode]} ?data
        {:keys [by-server ready-on-unspec]} @*state
        {:keys [battle client-data username]} (get by-server server-key)
        {:keys [battle-status team-color]} (get-in battle [:users username])]
    (swap! *state assoc-in [:by-server server-key :auto-unspec] false)
    (event/set-battle-mode *state {:battle-status battle-status
                                   :client-data client-data
                                   :mode mode
                                   :ready-on-unspec ready-on-unspec
                                   :server-key server-key
                                   :team-color team-color})))

(defmethod -event-msg-handler
  :skylobby/set-auto-unspec
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        {:keys [server-key auto-unspec]} ?data
        {:keys [by-server ready-on-unspec]} @*state
        {:keys [battle client-data username]} (get by-server server-key)
        {:keys [battle-status team-color]} (get-in battle [:users username])]
    (event/set-auto-unspec *state {
                                   :auto-unspec auto-unspec
                                   :battle-status battle-status
                                   :client-data client-data
                                   :ready-on-unspec ready-on-unspec
                                   :server-key server-key
                                   :team-color team-color})))

(defmethod -event-msg-handler
  :skylobby/set-battle-ready
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        {:keys [server-key ready]} ?data
        {:keys [by-server]} @*state
        {:keys [battle client-data username]} (get by-server server-key)
        {:keys [battle-status team-color]} (get-in battle [:users username])]
    (event/set-battle-ready *state {:client-data client-data
                                    :battle-status battle-status
                                    :ready ready
                                    :server-key server-key
                                    :team-color team-color})))

(defmethod -event-msg-handler
  :skylobby/set-away
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)
        {:keys [server-key away]} ?data
        {:keys [by-server]} @*state
        {:keys [client-data username users]} (get by-server server-key)
        {:keys [client-status]} (get-in users [:users username])]
    (event/set-client-status *state {:client-data client-data
                                     :client-status (assoc client-status :away away)
                                     :server-key server-key})))


(sente/start-server-chsk-router!
  ch-chsk event-msg-handler)


(defn index [_]
  {:status 200
   :body
   (hiccup/html
     [:head
      [:meta {:charset "utf-8"}]]
     [:body
      [:div#root
       (let [csrf-token (force anti-forgery/*anti-forgery-token*)]
         [:div#sente-csrf-token {:data-csrf-token csrf-token}])]
      [:link {:rel "stylesheet" :href "https://unpkg.com/tachyons@4.12.0/css/tachyons.min.css"}]
      [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/icon?family=Material+Icons"}]
      [:script {:src "/js/main.js"}]])
   :headers {"Content-Type" "text/html"}})

(defn login-handler
  "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
  [ring-req]
  (let [{:keys [session params]} ring-req
        {:keys [user-id]} params]
    (log/debugf "Login request: %s" params)
    {:status 200 :session (assoc session :uid user-id)}))


(defn broadcast [message]
  (let [uids (:any @connected-uids)]
    (log/info "Broadcasting message" (first message) "to" (count uids) "clients:" (pr-str uids))
    (doseq [uid uids]
      (chsk-send! uid message))))

(defn push-watcher [_ref _k old-state new-state]
  (let [new-servers-data (servers-data new-state)]
    (when (not= (servers-data old-state)
                new-servers-data)
      (broadcast [:skylobby/servers new-servers-data])))
  (let [new-auto-launch (:auto-launch new-state)]
    (when (not= (:auto-launch old-state)
                new-auto-launch)
      (broadcast [:skylobby/auto-launch new-auto-launch])))
  (doseq [[server-key server-data] (:by-server new-state)]
    (let [{:keys [auto-unspec battle battles channels users]} server-data]
      (when (not= auto-unspec
                  (get-in old-state [:by-server server-key :auto-unspec]))
        (broadcast [:skylobby/auto-unspec {:server-key server-key :auto-unspec auto-unspec}]))
      (when (not= battle
                  (get-in old-state [:by-server server-key :battle]))
        (broadcast [:skylobby/battle {:server-key server-key :battle battle}]))
      (when (not= battles
                  (get-in old-state [:by-server server-key :battles]))
        (broadcast [:skylobby/battles {:server-key server-key :battles battles}]))
      (when (not= users
                  (get-in old-state [:by-server server-key :users]))
        (broadcast [:skylobby/users {:server-key server-key :users users}]))
      (doseq [[channel-name channel-data] channels]
        (when (not= channel-data
                    (get-in old-state [:by-server server-key :channels channel-name]))
          (broadcast [:skylobby/chat {:server-key server-key :channel-name channel-name :channel-data channel-data}]))))))


(s/def ::map-name string?)
(s/def ::minimap-type string?)

(defn handler [state-atom]
  (alter-var-root #'*state (constantly state-atom))
  (add-watch *state :push-watcher push-watcher)
  (ring/ring-handler
    (ring/router
      [
       ["/chsk" {:get {:handler ring-ajax-get-or-ws-handshake}
                 :post {:handler ring-ajax-post}}]
       ["/login" {:post {:handler login-handler}}]
       ["/api"
        ["/math" {:get {:parameters {:query {:x int? :y int?}}
                        :responses  {200 {:body {:total int?}}}
                        :handler    (fn [{{{:keys [x y]} :query} :parameters}]
                                      {:status 200
                                       :body   {:total (+ x y)}})}}]]
       ["/ipc"
        ["/replay" {:get {:parameters {:query {:path string?}}}
                    :responses {200 {:body {:path string?}}}
                    :handler (fn [{{{:keys [path]} :query} :parameters}]
                               (if-let [file (fs/file path)]
                                 (let [parsed-replay (replay/parse-replay file)]
                                   (log/info "Loading replay from IPC" path)
                                   (swap! state-atom
                                     (fn [state]
                                       (-> state
                                           (assoc :show-replays true
                                                  :selected-replay parsed-replay
                                                  :selected-replay-file file)
                                           (assoc-in [:parsed-replays-by-path (fs/canonical-path file)] parsed-replay)))))
                                 (log/warn "Unable to coerce to file" path)))}]]
       ["/minimap-image" {:get {:parameters {:query (s/keys :req-un [::map-name]
                                                            :opt-un [::minimap-type])}}
                          :handler (fn [{{{:keys [map-name minimap-type]} :query} :parameters}]
                                     (log/info "Serving minimap image for" map-name "type" minimap-type)
                                     (try
                                       (let [f (fs/minimap-image-cache-file map-name (when minimap-type {:minimap-type minimap-type}))]
                                         {:status 200
                                          :headers {"Content-Type" "image/png"}
                                          :body (io/input-stream f)})
                                       (catch Exception e
                                         (log/error e "Error in minimap image handler")
                                         {:status 500
                                          :headers {"Content-Type" "text/html"}
                                          :body (str "Error getting minimap image for" map-name "type" minimap-type)})))}]
       ["/*" (ring/create-resource-handler {:not-found-handler index})]]
      {:conflicts (constantly nil)
       :data {:coercion   reitit.coercion.spec/coercion
              :muuntaja   m/instance
              :middleware [parameters/parameters-middleware
                           rrc/coerce-request-middleware
                           ;anti-forgery/wrap-anti-forgery
                           exception/exception-middleware
                           content-type/wrap-content-type
                           muuntaja/format-response-middleware
                           rrc/coerce-response-middleware]}})
    (ring/create-default-handler
      {:not-found index
       :method-not-allowed index
       :not-acceptable index})))
