(ns spring-lobby.client.message
  "Separate ns to avoid circular dependency for now."
  (:require
    [manifold.stream :as s]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn send-message
  ([c m]
   (if c
     (do
       (log/info ">" (str "'" m "'"))
       @(s/put! c (str m "\n"))
       (log/error (ex-info "Old client message fn" {})))
     (log/error (ex-info "No client to send message" {}))))
  ([state-atom client-data message]
   (send-message state-atom client-data message nil))
  ([state-atom client-data message {:keys [log-message] :or {log-message message}}]
   (if-let [client (:client client-data)]
     (let [server-key (u/server-key client-data)]
       (log/info (str "[" server-key "] > '" log-message "'"))
       @(s/put! client (str message "\n"))
       (u/append-console-log state-atom server-key :client log-message))
     (log/error (ex-info "No client to send message" {:message log-message})))))
