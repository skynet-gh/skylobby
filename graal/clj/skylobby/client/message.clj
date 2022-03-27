(ns skylobby.client.message
  (:require
    [clojure.string :as string]
    [manifold.stream :as s]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn send
  ([state-atom client-data message]
   (send state-atom client-data message nil))
  ([state-atom client-data message {:keys [log-message] :or {log-message message}}]
   (if-let [client (:client client-data)]
     (let [server-key (u/server-key client-data)]
       (log/info (str "[" server-key "] > '" log-message "'"))
       @(s/put! client (str message "\n"))
       (let [k (-> message
                   (string/split #"\s")
                   first)]
         (swap! state-atom
           (fn [state]
             (cond-> ((u/append-console-log-fn server-key :client log-message) state)
                     (= "PING" k)
                     (assoc-in [:by-server server-key :last-ping] (u/curr-millis))
                     (= "MYBATTLESTATUS" k)
                     (assoc-in
                       [:by-server server-key :expecting-responses k]
                       {:sent-message message
                        :sent-millis (u/curr-millis)}))))))
     (log/error (ex-info "No client to send message" {:message log-message})))))
