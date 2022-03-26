(ns skylobby.server
  (:require
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.spec.alpha :as s]
    [hiccup.core :as hiccup]
    [muuntaja.core :as m]
    org.httpkit.server
    [reitit.ring :as ring]
    reitit.coercion.spec
    [reitit.ring.coercion :as rrc]
    [reitit.ring.middleware.exception :as exception]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [reitit.ring.middleware.parameters :as parameters]
    [ring.middleware.anti-forgery :as anti-forgery]
    [ring.middleware.content-type :as content-type]
    ring.util.response
    [skylobby.event :as event]
    [skylobby.fs :as fs]
    [skylobby.fs.sdfz :as replay]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [taoensso.sente :as sente]
    [taoensso.sente.interfaces :as interfaces]
    [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


; sente  https://github.com/ptaoussanis/sente/blob/master/example-project/src/example/server.clj

(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (log/info id ?data event)
  (-event-msg-handler ev-msg)) ; Handle event-msgs on a single thread

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:keys [event ?reply-fn]}]
  (log/warnf "Unhandled event: %s" event)
  (when ?reply-fn
    (?reply-fn {:umatched-event-as-echoed-from-server event})))


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


; https://github.com/ptaoussanis/sente/blob/master/src/taoensso/sente.cljc#L240-L243
; the default edn packer has issues in graalvm
(deftype EdnPacker []
  interfaces/IPacker
  (pack   [_ x] (pr-str          x))
  (unpack [_ x] (edn/read-string x)))


(defn init [state-atom]
  (let [packer (EdnPacker.)
        chsk-server (sente/make-channel-socket-server!
                      (get-sch-adapter)
                      {:authorized?-fn nil
                       :csrf-token-fn nil ; TODO
                       :packer packer
                       :wrap-recv-evs? false})
        {:keys [ch-recv send-fn connected-uids]} chsk-server
        broadcast (fn [uids message]
                    (log/debug "Broadcasting message" (first message) "to" (count uids) "clients:" (pr-str uids))
                    (doseq [uid uids]
                      (send-fn uid message)))
        push-channel (async/chan (async/sliding-buffer 1))
        _ (future
            (loop []
              (when-let [[old-state new-state] (async/<!! push-channel)]
                (let [uids (:any @connected-uids)]
                  (when-not (empty? uids)
                    (let [new-servers-data (servers-data new-state)]
                      (when (not= (servers-data old-state)
                                  new-servers-data)
                        (broadcast uids [:skylobby/servers new-servers-data])))
                    (let [new-auto-launch (:auto-launch new-state)]
                      (when (not= (:auto-launch old-state)
                                  new-auto-launch)
                        (broadcast uids [:skylobby/auto-launch new-auto-launch])))
                    (let [new-logins (:logins new-state)]
                      (when (not= (:logins old-state)
                                  new-logins)
                        (broadcast uids [:skylobby/logins new-logins])))
                    (doseq [[server-key server-data] (:by-server new-state)]
                      (let [{:keys [auto-unspec battle battles channels users]} server-data]
                        (when (not= auto-unspec
                                    (get-in old-state [:by-server server-key :auto-unspec]))
                          (broadcast uids [:skylobby/auto-unspec {:server-key server-key :auto-unspec auto-unspec}]))
                        (when (not= battle
                                    (get-in old-state [:by-server server-key :battle]))
                          (broadcast uids [:skylobby/battle {:server-key server-key :battle battle}]))
                        (when (not= battles
                                    (get-in old-state [:by-server server-key :battles]))
                          (broadcast uids [:skylobby/battles {:server-key server-key :battles battles}]))
                        (when (not= users
                                    (get-in old-state [:by-server server-key :users]))
                          (broadcast uids [:skylobby/users {:server-key server-key :users users}]))
                        (doseq [[channel-name channel-data] channels]
                          (when (not= channel-data
                                      (get-in old-state [:by-server server-key :channels channel-name]))
                            (broadcast uids [:skylobby/chat {:server-key server-key :channel-name channel-name :channel-data channel-data}])))))))
                (recur))))
        push-watcher (fn [_ref _k old-state new-state]
                       (async/>!! push-channel [old-state new-state]))]
    (remove-watch state-atom :push-watcher)
    (add-watch state-atom :push-watcher push-watcher)
    (remove-watch connected-uids :connected-uids)
    (add-watch connected-uids :connected-uids
      (fn [_ _ old-ids new-ids]
        (when (not= old-ids new-ids)
          (log/infof "Connected uids change: %s" new-ids))))
    (defmethod -event-msg-handler
      :skylobby/get
      [{:keys [?data ?reply-fn]}]
      (log/info "Get" ?data)
      (when ?reply-fn
        (?reply-fn get @state-atom ?data)))
    (defmethod -event-msg-handler
      :skylobby/get-in
      [{:keys [?data ?reply-fn]}]
      (log/info "Get in" ?data)
      (when ?reply-fn
        (?reply-fn (get-in @state-atom ?data))))
    (defmethod -event-msg-handler
      :skylobby/assoc-in
      [{:keys [?data]}]
      (let [[path v] ?data]
        (log/info "Assoc in" path v)
        (swap! state-atom assoc-in path v)))
    (defmethod -event-msg-handler
      :skylobby/get-servers
      [{:keys [?data ?reply-fn]}]
      (log/info "Get servers data" ?data)
      (when ?reply-fn
        (?reply-fn (servers-data @state-atom))))
    (defmethod -event-msg-handler
      :skylobby/join-battle
      [{:keys [?data]}]
      (let [[server-key battle-id] ?data
            server-data (get-in @state-atom [:by-server server-key])]
        (event/join-battle state-atom (assoc server-data :selected-battle battle-id))))
    (defmethod -event-msg-handler
      :skylobby/leave-battle
      [{:keys [?data]}]
      (let [[server-key] ?data
            server-data (get-in @state-atom [:by-server server-key])]
        (event/leave-battle state-atom server-data)))
    (defmethod -event-msg-handler
      :skylobby/connect-server
      [{:keys [?data]}]
      (let [
            {:keys [server-url username password]} ?data
            {:keys [logins servers]} @state-atom
            login (get logins server-url)
            username (or username
                         (:username login))
            password (or password
                         (:password login))
            server-key (u/server-key {:server-url server-url
                                      :username username})]
        (event/connect state-atom {:server [server-url (get servers server-url)]
                                   :server-key server-key
                                   :password password
                                   :username username})))
    (defmethod -event-msg-handler
      :skylobby/disconnect-server
      [{:keys [?data]}]
      (let [
            {:keys [server-key]} ?data]
        (event/disconnect state-atom server-key)))
    (defmethod -event-msg-handler
      :skylobby/start-battle
      [{:keys [?data]}]
      (let [
            {:keys [server-key]} ?data
            {:keys [by-server by-spring-root engine-overrides servers spring-isolation-dir]} @state-atom
            {:keys [battle client-data] :as server-data} (get by-server server-key)
            {:keys [server-url username]} client-data
            spring-root (or (get-in servers [server-url :spring-isolation-dir])
                            spring-isolation-dir)
            my-battle-status (get-in battle [:users username :battle-status])]
        (event/start-battle state-atom (merge server-data
                                         {:am-host false ; TODO
                                          :am-spec true ; TODO
                                          :battle-status my-battle-status
                                          :channel-name (u/battle-channel-name battle)
                                          :engine-overrides engine-overrides
                                          :host-ingame true
                                          :spring-isolation-dir spring-root}
                                         (dissoc
                                           (resource/spring-root-resources spring-root by-spring-root)
                                           :engine-version :map-name :mod-name)))))
    (defmethod -event-msg-handler
      :skylobby/send-message
      [{:keys [?data]}]
      (let [
            {:keys [server-key channel-name message]} ?data
            {:keys [by-server]} @state-atom
            {:keys [client-data]} (get by-server server-key)]
        (event/send-message state-atom {:channel-name channel-name
                                        :client-data client-data
                                        :message message
                                        :server-key server-key})))
    (defmethod -event-msg-handler
      :skylobby/set-battle-mode
      [{:keys [?data]}]
      (let [
            {:keys [server-key mode]} ?data
            {:keys [by-server ready-on-unspec]} @state-atom
            {:keys [battle client-data username]} (get by-server server-key)
            {:keys [battle-status team-color]} (get-in battle [:users username])]
        (swap! state-atom assoc-in [:by-server server-key :auto-unspec] false)
        (event/set-battle-mode state-atom {:battle-status battle-status
                                           :client-data client-data
                                           :mode mode
                                           :ready-on-unspec ready-on-unspec
                                           :server-key server-key
                                           :team-color team-color})))
    (defmethod -event-msg-handler
      :skylobby/set-auto-unspec
      [{:keys [?data]}]
      (let [
            {:keys [server-key auto-unspec]} ?data
            {:keys [by-server ready-on-unspec]} @state-atom
            {:keys [battle client-data username]} (get by-server server-key)
            {:keys [battle-status team-color]} (get-in battle [:users username])]
        (event/set-auto-unspec state-atom {
                                           :auto-unspec auto-unspec
                                           :battle-status battle-status
                                           :client-data client-data
                                           :ready-on-unspec ready-on-unspec
                                           :server-key server-key
                                           :team-color team-color})))
    (defmethod -event-msg-handler
      :skylobby/set-battle-ready
      [{:keys [?data]}]
      (let [
            {:keys [server-key ready]} ?data
            {:keys [by-server]} @state-atom
            {:keys [battle client-data username]} (get by-server server-key)
            {:keys [battle-status team-color]} (get-in battle [:users username])]
        (event/set-battle-ready state-atom {:client-data client-data
                                            :battle-status battle-status
                                            :ready ready
                                            :server-key server-key
                                            :team-color team-color})))
    (defmethod -event-msg-handler
      :skylobby/set-away
      [{:keys [?data]}]
      (let [
            {:keys [server-key away]} ?data
            {:keys [by-server]} @state-atom
            {:keys [client-data username users]} (get by-server server-key)
            {:keys [client-status]} (get-in users [:users username])]
        (event/set-client-status state-atom {:client-data client-data
                                             :client-status (assoc client-status :away away)
                                             :server-key server-key})))
    (sente/start-server-chsk-router! ch-recv event-msg-handler)
    chsk-server))


(defn index [_]
  {:status 200
   :body
   (hiccup/html
     [:head
      [:meta {:charset "utf-8"}]
      [:title "skylobby"]]
     [:body
      [:div#root
       (let [csrf-token (force anti-forgery/*anti-forgery-token*)]
         [:div#sente-csrf-token {:data-csrf-token csrf-token}])]
      [:link {:rel "stylesheet" :href "/css/tachyons.min.css"}]
      [:link {:rel "stylesheet" :href "/iconfont/material-icons.css"}]
      [:script {:src (str "/js/main.js?v=" (u/curr-millis))}]])
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


(s/def ::map-name string?)
(s/def ::minimap-type string?)

; https://cljdoc.org/d/metosin/reitit/0.5.15/api/reitit.ring.middleware.exception
(defn eception-handler [message exception request]
  {:status 500
   :body {:message message
          :exception (str exception)
          :uri (:uri request)}})


; https://github.com/ring-clojure/ring/pull/447/files
(defn- connection-content-length [^java.net.URLConnection conn]
  (let [len (.getContentLength conn)]
    (when (<= 0 len) len)))

(defn- connection-last-modified [^java.net.URLConnection conn]
  (let [last-mod (.getLastModified conn)]
    (when-not (zero? last-mod)
      (java.util.Date. last-mod))))

(defmethod ring.util.response/resource-data :resource
  [^java.net.URL url]
  ;; GraalVM resource scheme
  (let [resource     (.openConnection url)]
    {:content        (.getInputStream resource)
     :content-length (connection-content-length resource)
     :last-modified  (connection-last-modified resource)}))


(defmulti handle-direct-connect (fn [_state-atom request] (get-in request [:body-params :command])))

(defmethod handle-direct-connect :default [_state-atom request]
  (log/info "No method to handle direct connect request" (with-out-str (pprint request))))

(defmethod handle-direct-connect :join [state-atom request]
  (let [{:keys [direct-connect-password direct-connect-username]} (get-in request [:body-params :opts])
        [old-state new-state] (swap-vals! state-atom update-in [:by-server :direct]
                                (fn [direct]
                                  (cond-> direct
                                          (= direct-connect-password (:password direct))
                                          (update-in [:battle :users]
                                            (fn [users]
                                              (if (contains? users direct-connect-username)
                                                users
                                                (assoc users direct-connect-username {})))))))]
    (if (= old-state new-state)
      {:deny (if (not= direct-connect-password (get-in old-state [:by-server :direct :password]))
               :wrong-password
               :username-taken)}
      {:ok :joined
       :state (get-in new-state [:by-server :direct])})))


(defn direct-connect-handler-fn [state-atom]
  (fn [request]
    (let [debug (dissoc request :reitit.core/match)
          response (or (handle-direct-connect state-atom request)
                       {:state (get-in @state-atom [:by-server :direct])})]
      (log/info (with-out-str (pprint debug)))
      {:status 200
       :body response})))

(defn handler [state-atom]
  (let [{:keys [ajax-post-fn ajax-get-or-ws-handshake-fn]} (init state-atom)]
    (ring/ring-handler
      (ring/router
        [
         ["/chsk" {:get {:handler ajax-get-or-ws-handshake-fn}
                   :post {:handler ajax-post-fn}}]
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
         ["/favicon.ico" {:handler (fn [_request]
                                     (ring.util.response/redirect "images/favicon.ico"))}]
         ["/js/*" (ring/create-resource-handler {:root "public/js"
                                                 :not-found-handler index})]
         ["/css/*" (ring/create-resource-handler {:root "public/css"
                                                  :not-found-handler index})]
         ["/images/*" (ring/create-resource-handler {:root "public/images"
                                                     :not-found-handler index})]
         ["/iconfont/*" (ring/create-resource-handler {:root "public/iconfont"
                                                       :not-found-handler index})]]
        {:conflicts (constantly nil)
         :data {:coercion   reitit.coercion.spec/coercion
                :muuntaja   m/instance
                :middleware [parameters/parameters-middleware
                             rrc/coerce-request-middleware
                             ;anti-forgery/wrap-anti-forgery
                             (exception/create-exception-middleware
                               (merge
                                 exception/default-handlers
                                 {::exception/wrap (fn [handler e request]
                                                     (log/error e "Http handler exception")
                                                     (handler e request))}))
                             content-type/wrap-content-type
                             muuntaja/format-middleware
                             rrc/coerce-response-middleware]}})
      (ring/create-default-handler
        {:not-found (fn [r] (assoc (index r) :status 404))
         :method-not-allowed (fn [r] (assoc (index r) :status 405))
         :not-acceptable (fn [r] (assoc (index r) :status 406))}))))
