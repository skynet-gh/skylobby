(ns skylobby.server
  (:require
    aleph.http
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
    [skylobby.fs :as fs]
    [spring-lobby.fs.sdfz :as replay]
    [taoensso.sente :as sente]
    [taoensso.sente.server-adapters.aleph :refer (get-sch-adapter)]
    [taoensso.timbre :as log]))


; sente  https://github.com/ptaoussanis/sente/blob/master/example-project/src/example/server.clj

(let [packer :edn
      chsk-server (sente/make-channel-socket-server!
                    (get-sch-adapter)
                    {:authorized?-fn nil
                     :csrf-token-fn nil ; TODO
                     :packer packer})
      {:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]} chsk-server]
  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn)
  (def connected-uids connected-uids))


(add-watch connected-uids :connected-uids
  (fn [_ _ old-ids new-ids]
    (when not= old-ids new-ids
      (log/infof "Connected uids change: %s" new-ids))))


(defmulti -event-msg-handler :id)


(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg)) ; Handle event-msgs on a single thread

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (log/debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-server event}))))


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
      [:p [:strong "Step 2: "] " observe std-out (for server output) and below (for client output):"]
      [:textarea#output {:style "width: 100%; height: 200px;"}]
      [:link {:rel "stylesheet" :href "https://unpkg.com/tachyons@4.12.0/css/tachyons.min.css"}]
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

(defn handler [state-atom]
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
