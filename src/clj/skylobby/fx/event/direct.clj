(ns skylobby.fx.event.direct
  (:require
    [skylobby.direct :as direct]
    [skylobby.direct.client :as direct.client]
    [skylobby.fs :as fs]
    [skylobby.util :as u]
    [taoensso.sente :as sente]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* false)


(defn add-methods
  [multifn state-atom] ; TODO need to move event handler out of spring-lobby ns
  (defmethod multifn ::host
    [params]
    (swap! state-atom assoc-in [:login-error :direct-host] nil)
    (future
      (try
        (direct/host-direct-connect state-atom params)
        (catch Exception e
          (log/error e "Error starting direct connect server")))))
  (defmethod multifn ::join
    [{:keys [direct-connect-ip direct-connect-port direct-connect-protocol direct-connect-username]}]
    (swap! state-atom assoc-in [:login-error :direct-client] nil)
    (future
      (try
        (let [server-key {:server-type :direct
                          :protocol :skylobby
                          :host false
                          :hostname direct-connect-ip
                          :port direct-connect-port
                          :username direct-connect-username}
              ;; Serializtion format, must use same val for client + server:
              packer :edn
              {:keys [ch-recv send-fn] :as client}
              (sente/make-channel-socket-client!
                "/chsk"
                nil ; ?csrf-token
                {
                 :host direct-connect-ip
                 :port direct-connect-port
                 :protcol (or direct-connect-protocol :http)
                 :type   :ws
                 :packer packer
                 :wrap-recv-evs? false})
              msg-handler (direct.client/event-msg-handler state-atom server-key)
              client-close-fn (sente/start-client-chsk-router! ch-recv msg-handler)
              client-close-fn (fn []
                                (send-fn [::direct.client/close])
                                (client-close-fn))
              data {:battle {:battle-id :direct
                             :users {}}
                    :battles {:direct {}}
                    :client client
                    :client-close-fn client-close-fn
                    :username direct-connect-username}]
          (swap! state-atom
            (fn [state]
              (-> state
                  (assoc-in [:by-server server-key] data)
                  (assoc :selected-server-tab (str server-key))))))
        (catch Exception e
          (log/error e "Error joining direct connect"))))))
