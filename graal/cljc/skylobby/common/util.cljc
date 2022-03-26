(ns skylobby.common.util)


(defn server-type
  "Returns a keyword representing the type of server given its key. Used for dispatching actions
  based on these broad server groups."
  [server-key]
  (cond
    (string? server-key) :spring-lobby
    (= :local server-key) :singleplayer
    (map? server-key)
    (if (:host server-key)
      :direct-host
      :direct-client)
    :else nil))

(defn is-direct? [server-key]
  (#{:direct-host :direct-client} (server-type server-key)))
