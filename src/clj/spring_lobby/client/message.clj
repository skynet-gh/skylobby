(ns spring-lobby.client.message
  (:require
    [manifold.stream :as s]
    [taoensso.timbre :as log]))


(defn send-message [c m]
  (when c
    (log/info ">" (str "'" m "'"))
    @(s/put! c (str m "\n"))))
