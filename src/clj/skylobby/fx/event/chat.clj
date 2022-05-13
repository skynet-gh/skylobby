(ns skylobby.fx.event.chat
  (:require
    [clojure.string :as string]
    [skylobby.client.message :as message]
    [skylobby.util :as u]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* false)


(defn set-ignore
  ([state-atom server-key username ignore]
   (set-ignore state-atom server-key username ignore nil))
  ([state-atom server-key username ignore {:keys [channel-name]}]
   (swap! state-atom
     (fn [state]
       (let [channel-name (or channel-name
                              (u/visible-channel state server-key))]
         (-> state
             (assoc-in [:ignore-users server-key username] ignore)
             (update-in [:by-server server-key :channels channel-name :messages]
                        conj
                        {:text (str (if ignore "Ignored " "Unignored ") username)
                         :timestamp (u/curr-millis)
                         :message-type :info})))))))


(def default-history-index -1)

(defn add-methods
  [multifn state-atom] ; TODO need to move event handler out of spring-lobby ns
  (defmethod multifn ::send [{:keys [channel-name message no-clear-draft no-history server-key] :as e}]
    (let [
          {:keys [by-server]} (swap! state-atom
                                (fn [state]
                                  (cond-> (update-in state [:by-server server-key]
                                            (fn [server-data]
                                              (cond-> server-data
                                                      (not no-history)
                                                      (update-in [:channels channel-name :sent-messages] conj message)
                                                      (not no-history)
                                                      (assoc-in [:channels channel-name :history-index] default-history-index))))
                                          (not no-clear-draft)
                                          (update-in [:message-drafts server-key] dissoc channel-name))))
          {:keys [client-data client server] :as server-data} (get by-server server-key)
          server-type (u/server-type server-key)
          now (u/curr-millis)
          messages-path [:by-server server-key :channels channel-name :messages]]
      (future
        (try
          (cond
            (string/blank? channel-name)
            (log/info "Skipping message" (pr-str message) "to empty channel" (pr-str channel-name))
            (string/blank? message)
            (log/info "Skipping empty message" (pr-str message) "to" (pr-str channel-name))
            :else
            (cond
              (re-find #"^/ingame" message)
              (when (= :spring-lobby server-type)
                (message/send state-atom client-data "GETUSERINFO"))
              (re-find #"^/ignore" message)
              (let [[_all username] (re-find #"^/ignore\s+([^\s]+)\s*" message)]
                (set-ignore state-atom server-key username true {:channel-name channel-name}))
              (re-find #"^/unignore" message)
              (let [[_all username] (re-find #"^/unignore\s+([^\s]+)\s*" message)]
                (set-ignore state-atom server-key username false {:channel-name channel-name}))
              (or (re-find #"^/msg" message) (re-find #"^/message" message))
              (let [[_all user message] (re-find #"^/msg\s+([^\s]+)\s+(.+)" message)]
                @(multifn
                   (merge e
                     {:event/type ::send
                      :channel-name (str "@" user)
                      :message message
                      :no-clear-draft true})))
              (re-find #"^/rename" message)
              (let [[_all new-username] (re-find #"^/rename\s+([^\s]+)" message)]
                (when (= :spring-lobby server-type)
                  (swap! state-atom update-in messages-path conj {:text (str "Renaming to " new-username)
                                                                  :timestamp now
                                                                  :message-type :info}
                   (message/send state-atom client-data (str "RENAMEACCOUNT " new-username)))))
              :else
              (let [[private-message username] (re-find #"^@(.*)$" channel-name)
                    unified (-> client-data :compflags (contains? "u"))
                    is-battle-channel (u/battle-channel-name? channel-name)
                    legacy-springlobby-battle (and (not unified) is-battle-channel)
                    [is-ex message] (if-let [[_all message] (re-find #"^/me (.*)$" message)]
                                      [true message]
                                      [false message])
                    chat-data {:channel-name channel-name
                               :message-type (when is-ex :ex)
                               :text message
                               :timestamp now
                               :username (:username server-data)}]
                (case server-type
                  :direct-host
                  (if is-battle-channel
                    (do
                      (swap! state-atom update-in messages-path conj chat-data)
                      ((:broadcast-fn server) [:skylobby.direct/chat chat-data]))
                    (log/warn "TODO direct host send non-battle message:" message))
                  :direct-client
                  (if is-battle-channel
                    ((:send-fn client) [:skylobby.direct.client/chat chat-data])
                    (log/warn "TODO direct client send non-battle message:" message))
                  :spring-lobby
                  (if is-ex
                    (if private-message
                      (message/send state-atom client-data (str "SAYPRIVATEEX " username " " message))
                      (if legacy-springlobby-battle
                        (message/send state-atom client-data (str "SAYBATTLEEX " message))
                        (message/send state-atom client-data (str "SAYEX " channel-name " " message))))
                    (if private-message
                      (message/send state-atom client-data (str "SAYPRIVATE " username " " message))
                      (if legacy-springlobby-battle
                        (message/send state-atom client-data (str "SAYBATTLE " message))
                        (message/send state-atom client-data (str "SAY " channel-name " " message)))))))))
          (catch Exception e
            (log/error e "Error sending message" message "to channel" channel-name)))))))
