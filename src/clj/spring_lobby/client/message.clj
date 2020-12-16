(ns spring-lobby.client.message
  "Separate ns to avoid circular dependency for now."
  (:require
    [manifold.stream :as s]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defn send-message [c m]
  (when c
    (log/info ">" (str "'" m "'"))
    @(s/put! c (str m "\n"))))
