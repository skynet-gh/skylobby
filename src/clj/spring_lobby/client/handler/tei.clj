(ns spring-lobby.client.handler.tei
  (:require
    [clojure.string :as string]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.client.message :as message]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]))


; matchmaking

(defn parse-queue-id-name [queue-id-name]
  (when-let [[_all id queue-name] (re-find #"([^:]*):([^:]*)" queue-id-name)]
    [id {:queue-name queue-name}]))

(defmethod handler/handle "s.matchmaking.full_queue_list" [state-atom server-key m]
  (let [[_all queues-str] (re-find #"[^\s]+ (.*)" m)
        queue-id-names (->> (string/split queues-str #"\t")
                            (map parse-queue-id-name)
                            (into {}))
        queue-ids (set (keys queue-id-names))]
    (swap! state-atom update-in [:by-server server-key :matchmaking-queues]
           (fn [matchmaking-queues]
             (u/deep-merge
               (into {}
                 (filter (comp queue-ids first) matchmaking-queues))
               queue-id-names)))))

(defmethod handler/handle "s.matchmaking.your_queue_list" [state-atom server-key m]
  (let [[_all queues-str] (re-find #"[^\s]+ (.*)" m)
        queue-id-names (->> (string/split queues-str #"\t")
                            (remove string/blank?)
                            (map parse-queue-id-name)
                            (filter some?)
                            (map (fn [[k v]] [k (assoc v :am-in true)]))
                            (into {}))]
    (swap! state-atom update-in [:by-server server-key :matchmaking-queues]
           (fn [matchmaking-queues]
             (u/deep-merge
               (into {}
                 (map
                   (fn [[k v]] [k (assoc v :am-in false)])
                   matchmaking-queues))
               queue-id-names)))))

(defmethod handler/handle "s.matchmaking.queue_info" [state-atom server-key m]
  (let [[_all queue-info] (re-find #"[^\s]+ (.*)" m)
        [queue-id queue-name search-time size] (string/split queue-info #"\t")]
    (swap! state-atom update-in [:by-server server-key :matchmaking-queues queue-id]
           assoc
           :queue-name queue-name
           :current-search-time (u/to-number search-time)
           :current-size (u/to-number size))))

(defmethod handler/handle "s.matchmaking.ready_check" [state-atom server-key m]
  (let [[_all queue-id-name] (re-find #"[^\s]+ (.*)" m)
        [queue-id queue-name] (string/split queue-id-name #":")]
    (swap! state-atom update-in [:by-server server-key :matchmaking-queues queue-id]
           assoc :ready-check true :queue-name queue-name)))

(defmethod handler/handle "s.matchmaking.match_cancelled" [state-atom server-key m]
  (let [[_all queue-id-name] (re-find #"[^\s]+ (.*)" m)
        [queue-id queue-name] (string/split queue-id-name #":")]
    (swap! state-atom update-in [:by-server server-key :matchmaking-queues queue-id]
           assoc :ready-check false :queue-name queue-name)))


; token


(defmethod handler/handle "s.user.user_token" [state-atom server-key m]
  (if-let [[_all _username auth-token] (re-find #"[^\s]+ ([^\t]+)\t([^\t]+)" m)]
    (let [state (swap! state-atom assoc-in [:by-server server-key :auth-token] auth-token)
          client (-> state :by-server (get server-key) :client-data :client)]
      (message/send-message client (str "c.user.login " auth-token "\t" (u/agent-string))))
    (log/error "Error parsing user token message")))
