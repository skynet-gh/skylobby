(ns spring-lobby.client.handler
  (:require
    [clojure.string :as string]
    [spring-lobby.client :as client]
    [spring-lobby.spring :as spring]
    [spring-lobby.spring.script :as spring-script]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(defmethod client/handle "CLIENTSTATUS" [_c state m]
  (let [[_all username client-status] (re-find #"\w+ (\w+) (\w+)" m)
        decoded-status (client/decode-client-status client-status)
        state-data @state]
    (swap! state assoc-in [:users username :client-status] decoded-status)
    (when (and (:battle state)
               (= (:host-username (:battle state)) username)
               (:ingame decoded-status))
      (log/info "Starting game to join host")
      (spring/start-game state-data))))

(defmethod client/handle "REMOVESCRIPTTAGS" [_c state m]
  (let [[_all remaining] (re-find #"\w+ (.*)" m)
        scripttag-keys-parsed (map spring-script/parse-scripttag-key (string/split remaining #"\s+"))]
    (doseq [kws scripttag-keys-parsed]
      (when (seq kws)
        (swap! state
          (fn [state]
            (-> state
                (update-in (concat [:scripttags] (drop-last kws)) dissoc (last kws))
                (update-in (concat [:battle :scripttags] (drop-last kws)) dissoc (last kws)))))))))
