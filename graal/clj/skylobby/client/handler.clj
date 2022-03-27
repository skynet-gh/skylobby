(ns skylobby.client.handler
  (:require 
    [clojure.core.async :as async]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    crypto.random
    [skylobby.client.message :as message]
    [skylobby.fs :as fs]
    [skylobby.spring :as spring]
    [skylobby.spring.script :as spring-script]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defmulti handle
  (fn [_state-atom _server-key m]
    (-> m
        (string/split #"\s")
        first)))


(defmethod handle "MOTD" [_state-atom _server-key m]
  (log/trace "motd" m))

(defmethod handle "PONG" [state-atom server-key _m]
  (swap! state-atom update-in [:by-server server-key]
    (fn [{:keys [last-ping] :as state}]
      (let [now (u/curr-millis)]
        (cond-> (assoc state :last-pong now)
                (number? last-ping)
                (assoc :rtt (- now last-ping)))))))


(defmethod handle "LOGININFOEND" [state-atom server-key _m]
  (let [state @state-atom
        server-data (-> state :by-server (get server-key))
        client-data (:client-data server-data)
        my-channels (concat
                      (-> state :my-channels (get server-key))
                      (:global-chat-channels state))]
    (log/info "End of login info, sending initial commands")
    (async/<!! (async/timeout 1000))
    (message/send state-atom client-data "PING")
    (async/<!! (async/timeout 1000))
    (message/send state-atom client-data "CHANNELS")
    (async/<!! (async/timeout 1000))
    (message/send state-atom client-data "FRIENDLIST")
    (async/<!! (async/timeout 1000))
    (message/send state-atom client-data "FRIENDREQUESTLIST")
    (async/<!! (async/timeout 1000))
    (when (u/matchmaking? server-data)
      (async/<!! (async/timeout 1000))
      (message/send state-atom client-data "c.matchmaking.list_all_queues"))
    (doseq [channel my-channels]
      (let [[channel-name _] channel]
        (if (and channel-name
                 (not (u/battle-channel-name? channel-name))
                 (not (u/user-channel-name? channel-name)))
          (do
            (async/<!! (async/timeout 1000))
            (message/send state-atom client-data (str "JOIN " channel-name)))
          (swap! state-atom update-in [:my-channels server-key] dissoc channel-name))))))


(defmethod handle "SETSCRIPTTAGS" [state-atom server-key m]
  (let [[_all script-tags-raw] (re-find #"\w+ (.*)" m)
        parsed (spring-script/parse-scripttags script-tags-raw)]
    (swap! state-atom update-in [:by-server server-key :battle :scripttags] u/deep-merge parsed)))


(defn parse-adduser [m]
  (re-find #"\w+ ([^\s]+) ([^\s]+)( ([\d]+))? ([\d]+|None)( (.+))?" m))

(defmethod handle "ADDUSER" [state-atom server-key m]
  (if-let [[_all username country _cpu cpu user-id _user-agent user-agent] (parse-adduser m)]
    (let [user {:username username
                :country country
                :cpu cpu
                :user-id user-id
                :user-agent user-agent}
          channel-name (u/user-channel-name username)]
      (swap! state-atom update-in [:by-server server-key]
        (fn [server-data]
          (cond-> server-data
                  true
                  (assoc-in [:users username] user)
                  (contains? (:my-channels server-data) channel-name)
                  (update-in [:channels channel-name :messages] conj {:text ""
                                                                      :timestamp (u/curr-millis)
                                                                      :message-type :join
                                                                      :username username})))))
    (log/warn "Unable to parse ADDUSER" (pr-str m))))


(defn start-game-if-synced
  [state-atom {:keys [by-spring-root servers spring-isolation-dir] :as state} server-data]
  (let [{:keys [battle battles username]} server-data
        spring-root (or (-> servers (get (-> server-data :client-data :server-url)) :spring-isolation-dir)
                        spring-isolation-dir)
        {:keys [engines maps mods]} (-> by-spring-root (get (fs/canonical-path spring-root)))
        battle-detail (-> battles (get (:battle-id battle)))
        {:keys [battle-map battle-modname battle-version]} battle-detail
        has-engine (->> engines (filter (comp #{battle-version} :engine-version)) first)
        has-mod (->> mods (filter (comp #{battle-modname} :mod-name)) first)
        has-map (->> maps (filter (comp #{battle-map} :map-name)) first)
        my-sync-status (get-in battle [:users username :battle-status :sync])]
    (if (or (= 1 my-sync-status)
            (and has-engine has-mod has-map))
      (do
        (log/info "Starting game to join host")
        (future
          (try
            (spring/start-game
              state-atom
              (merge
                (dissoc state :engine-version)
                server-data
                {:spring-isolation-dir spring-root
                 :engines engines
                 :maps maps
                 :mods mods}))
            (catch Exception e
              (log/error e "Error starting spring")))))
      (log/info
        (str "Missing engine, mod, or map\n"
             (with-out-str
               (pprint
                 {:engine has-engine
                  :mod has-mod
                  :map has-map})))))))

(defn parse-client-status [m]
  (re-find #"\w+ ([^\s]+) (\w+)" m))

(defmethod handle "CLIENTSTATUS" [state-atom server-key m]
  (let [[_all username client-status] (parse-client-status m)
        _ (require 'skylobby.client.gloss)
        decode-client-status-fn (var-get (find-var 'skylobby.client.gloss/decode-client-status)) 
        decoded-status (decode-client-status-fn client-status)
        now (u/curr-millis)
        [prev-state _curr-state] (swap-vals! state-atom update-in [:by-server server-key :users username]
                                   (fn [user-data]
                                     (let [prev-status (:client-status user-data)]
                                       (cond-> user-data
                                         true
                                         (assoc :client-status decoded-status)
                                         (and prev-status (not (:ingame prev-status)) (:ingame decoded-status))
                                         (assoc :game-start-time now)
                                         (and prev-status (not (:away prev-status)) (:away decoded-status))
                                         (assoc :away-start-time now)))))
        auto-launch (if (contains? (:auto-launch prev-state) server-key)
                      (get-in prev-state [:auto-launch server-key])
                      true)
        {:keys [battle battles users] :as server-data} (-> prev-state :by-server (get server-key))]
    (if-not (= (get-in battles [(:battle-id battle) :host-username]) username)
      (log/debug "Short circuiting CLIENTSTATUS handler since not battle host")
      (if-not (contains? (:users battle) username)
        (log/debug "Short circuiting CLIENTSTATUS handler since user not in battle")
        (let [
              prev-status (-> users (get username) :client-status)
              my-username (:username server-data)
              my-status (-> users (get my-username) :client-status)
              my-battle-status (-> battle :users (get my-username) :battle-status)
              am-spec (not (:mode my-battle-status))]
          (log/debug "CLIENTSTATUS" username decoded-status)
          (cond
            (not battle) (log/debug "Not in a battle")
            (not (:ingame decoded-status)) (log/debug "Not in game")
            (= (:ingame prev-status) (:ingame decoded-status)) (log/debug "Not a game status change")
            (= username my-username) (log/debug "Ignoring own game start")
            (:ingame my-status) (log/debug "Already in game")
            (and (not auto-launch) am-spec)
            (log/info "Not auto starting game" (pr-str {:spec am-spec :auto-launch auto-launch}))
            :else
            (start-game-if-synced state-atom prev-state server-data)))))))


(defmethod handle "CHANNEL" [state-atom server-url m]
  (let [[_all channel-name user-count _ topic] (re-find #"\w+ ([^\s]+) (\w+)(?: (.+))?" m)]
    (swap! state-atom update-in [:by-server server-url :channels channel-name]
           merge {:channel-name channel-name
                  :user-count user-count
                  :topic topic})))

(defmethod handle "CHANNELTOPIC" [state-atom server-url m]
  (let [[_all channel-name topic] (re-find #"\w+ ([^\s]+) (\w+)" m)]
    (swap! state-atom update-in [:by-server server-url :channels channel-name] merge {:topic topic})))

(defmethod handle "ENDOFCHANNELS" [_state-atom _server-url _m]
  (log/debug "Ignore ENDOFCHANNELS message"))


(defmethod handle "CHANNELS" [_state-atom _server-url _m]
  (log/info "Ignoring unused CHANNELS command"))


(defmethod handle "FRIENDLISTBEGIN" [_state-atom _server-key _m]
  (log/info "Ignoring unused FRIENDLISTBEGIN command"))

(defmethod handle "FRIENDLISTEND" [_state-atom _server-key _m]
  (log/info "Ignoring unused FRIENDLISTEND command"))

(defmethod handle "FRIENDREQUESTLISTBEGIN" [_state-atom _server-key _m]
  (log/info "Ignoring unused FRIENDREQUESTLISTBEGIN command"))

(defmethod handle "FRIENDREQUESTLISTEND" [_state-atom _server-key _m]
  (log/info "Ignoring unused FRIENDREQUESTLISTEND command"))


(defn parse-battleopened [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+)\s+([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)(\t([^\t]+))?" m))

(defmethod handle "BATTLEOPENED" [state-atom server-key m]
  (if-let [[_all battle-id battle-type battle-nat-type host-username battle-ip battle-port battle-maxplayers battle-passworded battle-rank battle-maphash battle-engine battle-version battle-map battle-title battle-modname _ channel-name] (parse-battleopened m)]
    (let [battle {:battle-id battle-id
                  :battle-type battle-type
                  :battle-nat-type battle-nat-type
                  :host-username host-username
                  :battle-ip battle-ip
                  :battle-port battle-port
                  :battle-maxplayers battle-maxplayers
                  :battle-passworded battle-passworded
                  :battle-rank battle-rank
                  :battle-maphash battle-maphash
                  :battle-engine battle-engine
                  :battle-version battle-version
                  :battle-map battle-map
                  :battle-title battle-title
                  :battle-modname battle-modname
                  :channel-name channel-name
                  :users {host-username {}}}
          {:keys [last-battle] :as state} (swap! state-atom assoc-in [:by-server server-key :battles battle-id] battle)
          last-battle (get last-battle server-key)
          {:keys [client-data username]} (-> state :by-server (get server-key))]
      (when (and (:auto-rejoin-battle state)
                 (not= host-username username)
                 (= host-username (:host-username last-battle))
                 (:should-rejoin last-battle))
        (message/send state-atom client-data
          (str "JOINBATTLE " battle-id
               (if battle-passworded
                 (str " " (:battle-password state))
                 (str " *"))
               " " (crypto.random/hex 6)))))
    (log/warn "Unable to parse BATTLEOPENED" (pr-str m))))

(defn parse-updatebattleinfo [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) (.+)" m))

(defmethod handle "UPDATEBATTLEINFO" [state-atom server-url m]
  (let [[_all battle-id battle-spectators battle-locked battle-maphash battle-map] (parse-updatebattleinfo m)]
    (swap! state-atom update-in [:by-server server-url]
      (fn [{:keys [battle username] :as state}]
        (let [my-battle-id (:battle-id battle)
              old-battle-map (-> state (get :battles) (get battle-id) :battle-map)
              my-battle (= my-battle-id battle-id)
              map-changed (not= old-battle-map battle-map)]
          (cond-> state
                  true
                  (update-in [:battles battle-id] assoc
                    :battle-id battle-id
                    :battle-spectators battle-spectators
                    :battle-locked battle-locked
                    :battle-maphash battle-maphash
                    :battle-map battle-map)
                  (and my-battle map-changed)
                  (assoc-in [:battle :users username :battle-status :sync] 0)))))))
