(ns spring-lobby.client.handler
  (:require
    byte-streams
    [clojure.string :as string]
    [gloss.core :as gloss]
    [gloss.io :as gio]
    [spring-lobby.battle :as battle]
    [spring-lobby.client.message :as message]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.nio ByteBuffer)))


(set! *warn-on-reflection* true)


(def default-battle-status
  {:ready false
   :ally 0
   :handicap 0
   :mode 1
   :sync 1
   :id 0
   :side 0})

(def default-client-status "0")

(def client-status-protocol
  (gloss/compile-frame
    (gloss/bit-map
      :prefix 1
      :bot 1
      :access 1
      :rank 3
      :away 1
      :ingame 1)))

(def battle-status-protocol
  (gloss/compile-frame
    (gloss/bit-map
      :prefix 6
      :side 2
      :sync 2
      :pad 4
      :handicap 7
      :mode 1
      :ally 4
      :id 4
      :ready 1
      :suffix 1)))


(def default-scripttags ; TODO read these from lua in map, mod/game, and engine
  {:game
   {:startpostype 1
    :modoptions {}}})


(defn decode-client-status [status-str]
  (dissoc
    (gio/decode client-status-protocol
      (byte-streams/convert
        (.array
          (.put
            (ByteBuffer/allocate 1)
            (Byte/parseByte status-str)))
        ByteBuffer))
    :prefix))

(defn decode-battle-status [status-str]
  (dissoc
    (gio/decode battle-status-protocol
      (byte-streams/convert
        (.array
          (.putInt
            (ByteBuffer/allocate (quot Integer/SIZE Byte/SIZE))
            (Integer/parseInt status-str)))
        ByteBuffer))
    :prefix :pad :suffix))

(defn encode-battle-status [battle-status]
  (str
    (.getInt
      ^ByteBuffer
      (gio/to-byte-buffer
        (gio/encode battle-status-protocol
          (assoc
            (merge default-battle-status battle-status)
            :prefix 0
            :pad 0
            :suffix 0))))))


(defmulti handle
  (fn [_client _state m]
    (-> m
        (string/split #"\s")
        first)))

(defmethod handle "CLIENTSTATUS" [_c state-atom m]
  (let [[_all username client-status] (re-find #"\w+ (\w+) (\w+)" m)
        decoded-status (decode-client-status client-status)
        {:keys [battle battles] :as prev-state} @state-atom
        _ (swap! state-atom assoc-in [:users username :client-status] decoded-status)
        prev-status (-> prev-state :users (get username) :client-status)
        my-status (-> prev-state :users (get (:username prev-state)) :client-status)
        battle-detail (-> battles (get (:battle-id battle)))]
    (cond
      (not (:ingame decoded-status)) (log/info "Not a game start")
      (not= (:ingame prev-status) (:ingame decoded-status)) (log/info "Not a game status change")
      (= username (:username prev-state)) (log/info "Ignoring own game start")
      (:ingame my-status) (log/info "Already in game")
      (not battle) (log/debug "Not in a battle")
      (not= (:host-username battle-detail) username) (log/info "Not the host game start")
      :else
      (do
        (log/info "Starting game to join host" username)
        (spring/start-game prev-state)))))

(defmethod handle "REMOVESCRIPTTAGS" [_c state m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        scripttag-keys-parsed (map spring-script/parse-scripttag-key (string/split remaining #"\s+"))]
    (doseq [kws scripttag-keys-parsed]
      (when (seq kws)
        (swap! state
          (fn [state]
            (-> state
                (update-in (concat [:scripttags] (drop-last kws)) dissoc (last kws))
                (update-in (concat [:battle :scripttags] (drop-last kws)) dissoc (last kws)))))))))


(defn parse-addbot [m]
  (re-find #"\w+ (\w+) (\w+) (\w+) (\w+) (\w+) ([^\s]+)" m))

(defmethod handle "ADDBOT" [_c state-atom m]
  (let [[_all battle-id bot-name owner battle-status team-color ai] (parse-addbot m)
        [_all ai-name ai-version] (when ai (re-find #"([^\s]+)\|([^\s]+)" ai))
        bot {:bot-name bot-name
             :owner owner
             :battle-status (decode-battle-status battle-status)
             :team-color team-color
             :ai-name ai-name
             :ai-version ai-version}]
    (swap! state-atom
      (fn [state]
        (let [state (assoc-in state [:battles battle-id :bots bot-name] bot)]
          (if (= battle-id (-> state :battle :battle-id))
            (assoc-in state [:battle :bots bot-name] bot)
            state))))))


(defn parse-joinbattle [m]
  (re-find #"\w+ ([^\s]+) ([^\s]+) ([^\s]+)" m))

(defmethod handle "JOINBATTLE" [_c state-atom m]
  (let [[_all battle-id hash-code channel-name] (parse-joinbattle m)]
    (swap! state-atom assoc :battle {:battle-id battle-id
                                     :hash-code hash-code
                                     :channel-name channel-name
                                     :scripttags default-scripttags})))

(defmethod handle "REQUESTBATTLESTATUS" [client state-atom _m]
  (let [{:keys [battle preferred-color]} @state-atom
        battle-status (assoc default-battle-status
                             :id (battle/available-team-id battle)
                             :ally (battle/available-ally battle)
                             :mode true)
        color (or preferred-color
                  (u/random-color))
        msg (str "MYBATTLESTATUS " (encode-battle-status battle-status) " " color)]
    (message/send-message client msg)))

(defmethod handle "BATTLECLOSED" [_c state-atom m]
  (let [[_all battle-id] (re-find #"\w+ (\w+)" m)]
    (swap! state-atom
      (fn [state]
        (let [curr-battle-id (-> state :battle :battle-id)
              next-state (update state :battles dissoc battle-id)]
          (if (= battle-id curr-battle-id)
            (dissoc next-state :battle)
            next-state))))))
