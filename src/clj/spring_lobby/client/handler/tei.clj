(ns spring-lobby.client.handler.tei
  (:require
    [clojure.string :as string]
    [spring-lobby.client.handler :as handler]
    [spring-lobby.util :as u]))


; matchmaking

(defmethod handler/handle "s.matchmaking.full_queue_list" [_client state m]
  (let [[_all queues-str] (re-find #"[^\s]+ (.*)" m)
        queue-names (set (string/split queues-str #"\s+"))]
    (swap! state update :matchmaking-queues
           (fn [matchmaking-queues]
             (into {}
               (concat
                 (filter (comp queue-names first) matchmaking-queues)
                 (map (juxt identity (constantly {})) queue-names)))))))

(defmethod handler/handle "s.matchmaking.your_queue_list" [_client state m]
  (let [[_all queues-str] (re-find #"[^\s]+ (.*)" m)
        queue-names (set (string/split queues-str #"\s+"))]
    (swap! state update :matchmaking-queues
           (fn [matchmaking-queues]
             (->> matchmaking-queues
                  (map
                    (fn [[k v]]
                      [k (assoc v :am-in (contains? queue-names k))]))
                  (into {}))))))

(defmethod handler/handle "s.matchmaking.queue_info" [_client state m]
  (let [[_all queue-info] (re-find #"[^\s]+ (.*)" m)
        [queue-name search-time size] (string/split queue-info #"\s+")]
    (swap! state update-in [:matchmaking-queues queue-name]
           assoc
           :current-search-time (u/to-number search-time)
           :current-size (u/to-number size))))

(defmethod handler/handle "s.matchmaking.ready_check" [_client state m]
  (let [[_all queue-name] (re-find #"[^\s]+ (.*)" m)]
    (swap! state assoc-in [:matchmaking-queues queue-name :ready-check] true)))

(defmethod handler/handle "s.matchmaking.match_cancelled" [_client state m]
  (let [[_all queue-name] (re-find #"[^\s]+ (.*)" m)]
    (swap! state assoc-in [:matchmaking-queues queue-name :ready-check] false)))
