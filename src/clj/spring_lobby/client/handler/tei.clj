(ns spring-lobby.client.handler.tei
  (:require
    [clojure.string :as string]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.util :as u]))


; matchmaking

(defn parse-queue-id-name [queue-id-name]
  (when-let [[_all id queue-name] (re-find #"([^:]*):([^:]*)" queue-id-name)]
    [id {:queue-name queue-name}]))

(defmethod handler/handle "s.matchmaking.full_queue_list" [_client state m]
  (let [[_all queues-str] (re-find #"[^\s]+ (.*)" m)
        queue-id-names (->> (string/split queues-str #"\t")
                            (map parse-queue-id-name)
                            (into {}))
        queue-ids (set (keys queue-id-names))]
    (swap! state update :matchmaking-queues
           (fn [matchmaking-queues]
             (into {}
               (concat
                 (filter (comp queue-ids first) matchmaking-queues)
                 queue-id-names))))))

(defmethod handler/handle "s.matchmaking.your_queue_list" [_client state m]
  (let [[_all queues-str] (re-find #"[^\s]+ (.*)" m)
        queue-id-names (->> (string/split queues-str #"\t")
                            (map parse-queue-id-name)
                            (map (fn [[k v]] [k (assoc v :am-in true)]))
                            (into {}))]
    (swap! state update :matchmaking-queues
           (fn [matchmaking-queues]
             (into {}
               (concat
                 (map
                   (fn [[k v]] [k (assoc v :am-in false)])
                   matchmaking-queues)
                 queue-id-names))))))

(defmethod handler/handle "s.matchmaking.queue_info" [_client state m]
  (let [[_all queue-info] (re-find #"[^\s]+ (.*)" m)
        [queue-name search-time size] (string/split queue-info #"\t")]
    (swap! state update-in [:matchmaking-queues queue-name]
           assoc
           :current-search-time (u/to-number search-time)
           :current-size (u/to-number size))))

(defmethod handler/handle "s.matchmaking.ready_check" [_client state m]
  (let [[_all queue-id-name] (re-find #"[^\s]+ (.*)" m)
        [queue-id queue-name] (string/split queue-id-name #":")]
    (swap! state update-in [:matchmaking-queues queue-id] assoc :ready-check true :queue-name queue-name)))

(defmethod handler/handle "s.matchmaking.match_cancelled" [_client state m]
  (let [[_all queue-id-name] (re-find #"[^\s]+ (.*)" m)
        [queue-id queue-name] (string/split queue-id-name #":")]
    (swap! state update-in [:matchmaking-queues queue-id] assoc :ready-check false :queue-name queue-name)))
