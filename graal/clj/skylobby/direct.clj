(ns skylobby.direct
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    [hiccup.core :as hiccup]
    [muuntaja.core :as m]
    [org.httpkit.server :as http-kit]
    reitit.coercion.spec
    [reitit.ring :as ring]
    [reitit.ring.coercion :as rrc]
    [reitit.ring.middleware.exception :as exception]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [reitit.ring.middleware.parameters :as parameters]
    [ring.middleware.content-type :as content-type]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.params :refer [wrap-params]]
    [skylobby.cli.util :as cu]
    skylobby.core
    [skylobby.event.battle :as event.battle]
    [skylobby.fs :as fs]
    [skylobby.resource :as resource]
    [skylobby.spring :as spring]
    [skylobby.util :as u]
    [taoensso.sente :as sente]
    [taoensso.sente.interfaces :as interfaces]
    [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
    [taoensso.timbre :as log])
  (:gen-class))


(set! *warn-on-reflection* false)


(defmulti -event-msg-handler (fn [_state-atom _server-key message] (:id message)))

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [state-atom server-key]
  (fn [{:as ev-msg :keys [id ?data event]}]
    (when-not (= :chsk/ws-ping id)
      (log/info id ?data event))
    (-event-msg-handler state-atom server-key ev-msg))) ; Handle event-msgs on a single thread

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [_state-atom _server-key {:keys [event ?reply-fn]}]
  (when-not (= :chsk/ws-ping (first event))
    (log/warnf "Unhandled event: %s" event))
  (when ?reply-fn
    (?reply-fn {:umatched-event-as-echoed-from-server event})))


(defn available [taken-set start]
  (if-not (contains? taken-set start)
    start (recur taken-set (inc start))))

(def illegal-direct-connect-username-re
  #"[^a-zA-Z_0-9\[\]]")

(defmethod -event-msg-handler
  :skylobby.direct.client/join
  [state-atom server-key {:keys [?data ?reply-fn send-fn uid] :as m}]
  (let [invalid-username (or (not ?data)
                             (re-find illegal-direct-connect-username-re ?data))
        [old-state new-state] (swap-vals! state-atom update-in [:by-server server-key]
                                (fn [server-data]
                                  (if (or invalid-username
                                          (contains? (:users (:battle server-data)) ?data))
                                    server-data
                                    (let [users (get-in server-data [:battle :users])
                                          battle-statuses (map :battle-status (vals users))
                                          available-team (available (set (map :id battle-statuses)) 0)
                                          available-ally (available (set (map :ally battle-statuses)) 0)]
                                      (-> server-data
                                          (assoc-in [:battle :users ?data] {:battle-status {:ally available-ally
                                                                                            :id available-team
                                                                                            :mode true
                                                                                            :ready true
                                                                                            :side 0}
                                                                            :team-color (u/random-color)})
                                          (assoc-in [:client-username uid] ?data))))))]
    (if (= old-state new-state) ; user not added
      (let [
            reason (if invalid-username
                     (str "illegal username: " (pr-str ?data))
                     "username in use")
            response {:response :deny
                      :reason reason}]
        (log/warn "Denying join" m "because" reason)
        (if ?reply-fn
          (?reply-fn response)
          (do
            (log/warn "No reply fn for join" m)
            (send-fn uid [::close {:reason reason}]))))
      (let [{:keys [battle-id scripttags] :as battle} (get-in new-state [:by-server server-key :battle])
            battle-details (get-in new-state [:by-server server-key :battles battle-id])]
        (send-fn uid [::battle-details battle-details])
        (send-fn uid [::battle-scripttags scripttags])
        (if-let [broadcast-fn (get-in new-state [:by-server server-key :server :broadcast-fn])]
          (do
            (broadcast-fn [::battle-users (:users battle)])
            (broadcast-fn [::battle-bots (:bots battle)]))
          (log/warn "No broadcast-fn found for server" server-key))))))

(defn handle-close [state-atom server-key {:keys [send-fn uid]}]
  (let [[old-state {:keys [by-server]}] (swap-vals! state-atom update-in [:by-server server-key]
                                          (fn [server-data]
                                            (if-let [username (get-in server-data [:client-username uid])]
                                              (-> server-data
                                                  (update-in [:battle :users] dissoc username))
                                              server-data)))
        {:keys [battle server]} (get by-server server-key)
        {:keys [broadcast-fn]} server
        username (get-in old-state [:by-server server-key :client-username uid])]
    (if username
      (do
        (log/info "Handling close for user" username)
        (when send-fn
          (send-fn uid [::close {:reason "completed"}])))
      (log/warn "No client-id found for client" uid))
    (if broadcast-fn
      (broadcast-fn [::battle-users (:users battle)])
      (log/warn "No broadcast fn to send close"))))

(defmethod -event-msg-handler
  :skylobby.direct.client/close
  [state-atom server-key message-data]
  (handle-close state-atom server-key message-data))

(defmethod -event-msg-handler
  :chsk/uidport-close
  [state-atom server-key message-data]
  (handle-close state-atom server-key message-data))


(defmethod -event-msg-handler
  :skylobby.direct.client/player-state
  [state-atom server-key {:keys [?data uid]}]
  (let [[_old-state {:keys [by-server]}] (swap-vals! state-atom update-in [:by-server server-key]
                                           (fn [server-data]
                                             (if-let [username (get-in server-data [:client-username uid])]
                                               (update-in server-data [:battle :users username] u/deep-merge ?data)
                                               server-data)))
        {:keys [battle server]} (get by-server server-key)
        {:keys [broadcast-fn]} server]
    (broadcast-fn [::battle-users (:users battle)])))

(defmethod -event-msg-handler
  :skylobby.direct.client/bot-state
  [state-atom server-key {:keys [?data uid]}]
  (let [{:keys [bot-name]} ?data
        [_old-state {:keys [by-server]}] (swap-vals! state-atom update-in [:by-server server-key]
                                           (fn [server-data]
                                             (let [username (get-in server-data [:client-username uid])]
                                               (if (and username
                                                        bot-name
                                                        (= username
                                                           (get-in server-data [:battle :bots bot-name :owner])))
                                                 (update-in server-data [:battle :bots bot-name] u/deep-merge ?data)
                                                 server-data))))
        {:keys [battle server]} (get by-server server-key)
        {:keys [broadcast-fn]} server]
    (broadcast-fn [::battle-bots (:bots battle)])))

(defmethod -event-msg-handler
  :skylobby.direct.client/user-state
  [state-atom server-key {:keys [?data uid]}]
  (let [[_old-state {:keys [by-server]}] (swap-vals! state-atom update-in [:by-server server-key]
                                           (fn [server-data]
                                             (if-let [username (get-in server-data [:client-username uid])]
                                               (update-in server-data [:users username] u/deep-merge ?data)
                                               server-data)))
        {:keys [server users]} (get by-server server-key)
        {:keys [broadcast-fn]} server]
    (broadcast-fn [::users users])))

(defmulti chat-msg-handler
  (fn [_state-atom _server-key message]
    (-> (or (:text message) "")
        (string/split #"\s+")
        first
        string/lower-case)))

(defmethod chat-msg-handler :default
  [_state-atom _server-key message]
  (log/info "No handler for message" message))

(defmethod -event-msg-handler
  :skylobby.direct.client/chat
  [state-atom server-key {:keys [?data]}]
  (let [{:keys [channel-name]} ?data
        {:keys [by-server direct-connect-chat-commands]} (swap! state-atom update-in [:by-server server-key :channels channel-name :messages] conj ?data)
        {:keys [server]} (get by-server server-key)
        {:keys [broadcast-fn]} server]
    (broadcast-fn [::chat ?data])
    (if direct-connect-chat-commands
      (chat-msg-handler state-atom server-key ?data)
      (log/info "Direct connect chat commands disabled"))))

(defmethod -event-msg-handler
  :skylobby.direct.client/add-bot
  [state-atom server-key {:keys [?data]}]
  (let [{:keys [bot-name]} ?data
        {:keys [by-server]} (swap! state-atom update-in [:by-server server-key :battle :bots]
                              (fn [bots]
                                (if (contains? bots bot-name)
                                  bots
                                  (assoc bots bot-name ?data))))
        {:keys [battle server]} (get by-server server-key)
        {:keys [broadcast-fn]} server]
    (broadcast-fn [::battle-bots (:bots battle)])))

(defmethod -event-msg-handler
  :skylobby.direct.client/remove-bot
  [state-atom server-key {:keys [?data uid]}]
  (let [{:keys [bot-name]} ?data
        {:keys [by-server]} (swap! state-atom update-in [:by-server server-key]
                              (fn [server-data]
                                (let [username (get-in server-data [:client-username uid])]
                                  (-> server-data
                                      (update-in [:battle :bots]
                                        (fn [bots]
                                          (if (= username
                                                 (get-in bots [bot-name :owner]))
                                            (dissoc bots bot-name)
                                            bots)))))))
        {:keys [battle server]} (get by-server server-key)
        {:keys [broadcast-fn]} server]
    (broadcast-fn [::battle-bots (:bots battle)])))


(defmethod chat-msg-handler "!engine"
  [state-atom server-key message]
  (log/info "Request to set engine" message)
  ; TODO check if chat commands are allowed
  (let [[_all engine-version] (re-find #"\w+ (.+)" (:text message))
        engine-version (string/trim engine-version)]
    (event.battle/engine-changed state-atom {
                                             :battle-id :direct
                                             :engine-version engine-version
                                             :server-key server-key})))

(defmethod chat-msg-handler "!map"
  [state-atom server-key message]
  (log/info "Request to set map" message)
  (let [[_all map-name] (re-find #"\w+ (.+)" (:text message))
        map-name (string/trim map-name)]
    (event.battle/map-changed state-atom {
                                          :battle-id :direct
                                          :map-name map-name
                                          :server-key server-key})))

(defmethod chat-msg-handler "!mod"
  [state-atom server-key message]
  (log/info "Request to set mod" message)
  (let [[_all mod-name] (re-find #"\w+ (.+)" (:text message))
        mod-name (string/trim mod-name)]
    (event.battle/mod-changed state-atom {
                                          :battle-id :direct
                                          :mod-name mod-name
                                          :server-key server-key})))

(defmethod chat-msg-handler "!game"
  [state-atom server-key message]
  (log/info "Request to set mod" message)
  (let [[_all mod-name] (re-find #"\w+ (.+)" (:text message))
        mod-name (string/trim mod-name)]
    (event.battle/mod-changed state-atom {
                                          :battle-id :direct
                                          :mod-name mod-name
                                          :server-key server-key})))

(defmethod chat-msg-handler "!bset"
  [state-atom server-key message]
  (log/info "Battle setting" message)
  (let [[_all k v] (re-find #"\w+ ([^\s]+)\s+([^\s]+)" (:text message))
        path (if (#{"startpostype"} k) ; TODO map options, validate
               [:by-server server-key :battle :scripttags "game" k]
               [:by-server server-key :battle :scripttags "game" "modoptions" k])
        state (swap! state-atom assoc-in path v)
        {:keys [by-server]} state
        {:keys [battle server]} (get by-server server-key)
        {:keys [broadcast-fn]} server]
    (broadcast-fn [::battle-scripttags (:scripttags battle)])))

(defmethod chat-msg-handler "!aiprofile"
  [state-atom server-key message]
  (log/info "AI profile change" message)
  (let [[_all bot-name options-json] (re-find #"\w+ ([^\s]+)\s+(.+)" (:text message))
        options (json/parse-string options-json)
        state (swap! state-atom update-in [:by-server server-key :battle :scripttags "game" "bots" bot-name "options"] u/deep-merge options)
        {:keys [by-server]} state
        {:keys [battle server]} (get by-server server-key)
        {:keys [broadcast-fn]} server]
    (broadcast-fn [::battle-scripttags (:scripttags battle)])))

(defmethod chat-msg-handler "!start"
  [state-atom server-key message]
  (log/info "Request to start battle game" message)
  ; TODO dedupe with client
  (let [state @state-atom
        {:keys [by-server by-spring-root engine-overrides spring-isolation-dir]} state
        {:keys [battle battles username] :as server-data} (get by-server server-key)
        my-battle-status (get-in battle [:users username :battle-status])]
    (future
      (try
        (spring/start-game
          state-atom
          (merge server-data
            {:am-host false ; TODO
             :am-spec true ; TODO
             :battle (assoc battle :battle-ip (:hostname server-key))
             :battles battles
             :battle-status my-battle-status
             :channel-name (u/battle-channel-name battle)
             :engine-overrides engine-overrides
             :host-ingame true
             :server-key server-key
             :spring-isolation-dir spring-isolation-dir}
            (dissoc
              (resource/spring-root-resources spring-isolation-dir by-spring-root)
              :engine-version :map-name :mod-name)))
        (catch Exception e
          (log/error e "Error starting game"))))))

(defmethod chat-msg-handler "!split"
  [state-atom server-key message]
  (log/info "Request to split boxes" message)
  (let [[_all split-type percent-str] (re-find #"\w+ ([^\s]+)\s+([^\s]+)" (:text message))
        percent (int (u/to-number percent-str))]
    (event.battle/split-boxes state-atom {:server-key server-key
                                          :split-percent percent
                                          :split-type split-type})))


; https://github.com/ptaoussanis/sente/blob/master/src/taoensso/sente.cljc#L240-L243
; the default edn packer has issues in graalvm
(deftype EdnPacker []
  interfaces/IPacker
  (pack   [_ x] (pr-str          x))
  (unpack [_ x] (edn/read-string x)))

(defn init [state-atom server-key]
  (let [packer (EdnPacker.)
        chsk-server (sente/make-channel-socket-server!
                      (get-sch-adapter)
                      {:authorized?-fn nil
                       :csrf-token-fn nil ; TODO
                       :packer packer
                       :user-id-fn :client-id
                       :wrap-recv-evs? false})
        {:keys [ch-recv send-fn connected-uids]} chsk-server
        broadcast-fn (fn [message]
                       (let [uids (:any @connected-uids)]
                         (log/info "Broadcasting message" (first message) "to" (count uids) "clients:" (pr-str uids))
                         (doseq [uid uids]
                           (send-fn uid message))))
        msg-handler (event-msg-handler state-atom server-key)]
    (remove-watch connected-uids :connected-uids)
    (add-watch connected-uids :connected-uids
      (fn [_ _ old-ids new-ids]
        (when (not= old-ids new-ids)
          (log/infof "Connected uids change: %s" new-ids))))
   (let [stop-fn (sente/start-server-chsk-router! ch-recv msg-handler)]
     (assoc chsk-server
            :broadcast-fn broadcast-fn
            :stop-fn stop-fn))))

(defn login-handler
  "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
  [ring-req]
  (let [{:keys [session params]} ring-req
        {:keys [user-id]} params]
    (log/debugf "Login request: %s" params)
    {:status 200 :session (assoc session :uid user-id)}))


(defn index [_]
  {:status 200
   :body
   (hiccup/html
     [:head
      [:meta {:charset "utf-8"}]]
     [:body])
   :headers {"Content-Type" "text/html"}})

(defn direct-connect-handler
  [state-atom server-key]
  (let [{:keys [ajax-post-fn ajax-get-or-ws-handshake-fn] :as server} (init state-atom server-key)]
    (swap! state-atom assoc-in [:by-server server-key :server] server)
    {:chsk-server server
     :handler
     (ring/ring-handler
       (ring/router
         [
          ["/chsk" {:get {:handler ajax-get-or-ws-handshake-fn}
                    :post {:handler ajax-post-fn}}]
          ["/login" {:post {:handler login-handler}}]]
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
          :not-acceptable (fn [r] (assoc (index r) :status 406))}))}))

(defn- start-direct-connect
  [state-atom server-key {:keys [port]}]
  (let [port (or port u/default-server-port)]
    (if (u/is-port-open? port)
      (do
        (log/info "Starting direct connect server on port" port)
        (let [{:keys [chsk-server handler]} (direct-connect-handler state-atom server-key)
              server (http-kit/run-server
                       (-> handler
                           wrap-keyword-params
                           wrap-params)
                       {:port port})]
          (fn []
            (if-let [stop-fn (:stop-fn chsk-server)]
              (stop-fn)
              (log/warn "No stop-fn to stop server"))
            (server))))
      (do
        (swap! state-atom assoc-in [:login-error :direct-host] (str "Port " port " unavailable"))
        (log/warn "Direct connect server port unavailable" port)))))


(defn host-direct-connect
  [state-atom
   {:keys [password port username spectate spring-port]}]
  (let [port (or port u/default-server-port)
        server-key {:server-type :direct
                    :protocol :skylobby
                    :host true
                    :hostname "localhost"
                    :port port
                    :username username}
        _ (log/info "Hosting direct connect server" server-key)
        server-close-fn (start-direct-connect state-atom server-key {:port port})
        {:keys [direct-connect-engine direct-connect-mod direct-connect-map]} @state-atom
        server-data {:battle {:battle-id :direct
                              :scripttags {"game"
                                           (merge
                                             {"startpostype" 1}
                                             (when spring-port
                                               {"hostport" spring-port}))}
                              :users {username
                                      {:battle-status
                                       {:ally 0
                                        :id 0
                                        :mode (not spectate)
                                        :ready true
                                        :side 0}
                                       :team-color (u/random-color)}}}
                     :battles {:direct
                               {:host-username username
                                :battle-map direct-connect-map
                                :battle-modname direct-connect-mod
                                :battle-version direct-connect-engine}}
                     :password password
                     :server-close-fn server-close-fn
                     :username username}]
    (if server-close-fn
      (do
        (swap! state-atom
          (fn [state]
            (-> state
                (update-in [:by-server server-key] merge server-data)
                (assoc :selected-server-tab (str server-key)))))
        true)
      (do
        (log/warn "Direct connect server failed to start")
        (swap! state-atom update :by-server dissoc server-key)
        false))))


(def cli-options
  [[nil "--port PORT" "Port to use for direct connect server / client"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 65536) "Must be a from 1 through 65535"]]
   [nil "--password PASSWORD" "Password to use for direct connect server / client"]
   [nil "--username USERNAME" "Username to use for direct connect server / client"
    :missing "Username is required"
    :validate [#(and (string? %)
                     (not (string/blank? %)))
               "Must be a non-empty string"]]
   [nil "--protocol PROTOCOL" "Protocol to use for direct connect server (http or https)"]
   [nil "--cert CERT_FILE" "Cert file to use for direct connect server https"]
   [nil "--spring-port SPRING_PORT" "Set the port that spring will use when hosting"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 65536) "Must be a from 1 through 65535"]]
   [nil "--spring-root SPRING_ROOT" "Set the spring-root config to the given directory"]
   [nil "--spring-type SPRING_TYPE" "Set the spring engine executable type to use, \"dedicated\" or \"headless\", default is \"headless\"."
    :parse-fn keyword
    :default :headless]
   [nil "--not-wsl" "Override WSL detection for running as Linux in Windows"]])


(defn -main [& args]
  (let [{:keys [errors options]} (cli/parse-opts args cli-options)]
    (cond
      errors
      (apply cu/print-and-exit 1
        "Error parsing arguments:\n"
        errors)
      :else
      (do
        (log/info "Starting headless direct connect server")
        (when-let [spring-type (:spring-type options)]
          (alter-var-root #'spring/spring-type (constantly spring-type)))
        (when (contains? options :not-wsl)
          (alter-var-root #'fs/is-wsl-override (constantly false)))
        (let [initial-state (skylobby.core/initial-state)
              state (merge
                      initial-state
                      (when (contains? options :spring-root)
                        (let [f (fs/file (:spring-root options))]
                          {:spring-isolation-dir f
                           ::spring-root-arg f}))
                      {
                       :direct-connect-chat-commands true
                       :disable-tasks false
                       :disable-tasks-while-in-game false
                       :ipc-server-enabled false
                       :refresh-replays-after-game false})]
          (reset! skylobby.core/*state state))
        (skylobby.core/init skylobby.core/*state)
        (if (host-direct-connect skylobby.core/*state (assoc options :spectate true))
          (println "Direct connect server started")
          (cu/print-and-exit 1 "Error starting direct connect server"))))))
