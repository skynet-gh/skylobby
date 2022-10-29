(ns skylobby.spring.autohost
  "AutoHostInterface for spring"
  (:require
    [aleph.udp :as udp]
    [clojure.core.async :as async]
    [manifold.stream :as s]
    [org.clojars.smee.binary.core :as b]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.io ByteArrayInputStream)
    (java.net InetSocketAddress)))


; https://github.com/beyond-all-reason/spring/blob/BAR105/rts/Net/AutohostInterface.h


(def message-type-names
  {0 :server-started
   1 :server-quit
   2 :server-startplaying
   3 :server-gameover
   4 :server-message
   5 :server-warning
   10 :player-joined
   11 :player-left
   12 :player-ready
   13 :player-chat
   14 :player-defeated
   20 :game-luamessage
   60 :game-teamstat})

(def protocol
  (b/header
    :ubyte
    (fn [message-type]
      (case message-type
        ; 0 server started
        ; 1 server quit
        ; 2 server startplaying
        ; 3 server gameover
        ; 4 server message
        4 (b/c-string "UTF-8")
        ; 5 server warning
        5 (b/c-string "UTF-8")
        ; 10 player joined
        10
        (b/ordered-map
          :playernumber :ubyte
          :name (b/c-string "UTF-8"))
        ; 11 player left
        11
        (b/ordered-map
          :playernumber :ubyte
          :reason :ubyte)
        ; 12 player ready
        12
        (b/ordered-map
          :playernumber :ubyte
          :state :ubyte)
        ; 13 player chat
        13
        (b/ordered-map
          :playernumber :ubyte
          :destination :ubyte
          :text (b/c-string "UTF-8"))
        ; 14 player defeated
        14
        (b/ordered-map
          :playernumber :ubyte)
        (b/blob)))
    (constantly nil) ; TODO writing
    :keep-header? true))


(defn parse [{:keys [message]}]
  (log/trace "Parsing message" message)
  (with-open [bais (ByteArrayInputStream. message)]
    (b/decode protocol bais)))

(def ignore-message-types
  #{20})

(defn start-server [state-atom server-key]
  (log/info "Starting AutoHostInterface server for" server-key)
  (let [port (u/open-port)
        _ (log/info "Found open port" port)
        server-socket @(udp/socket {:socket-address (InetSocketAddress. "127.0.0.1" port)})]
    (log/info "Server socket" server-socket)
    (->> server-socket
         (s/map parse)
         (s/consume
           (fn [{:keys [header] :as message}]
             (when-not (ignore-message-types header)
               (let [with-type (assoc message :message-type (get message-type-names header))]
                 (log/info "Consuming message" message))))))
    {
     :autohost-close-fn #(s/close! server-socket)
     :autohost-port port
     :autohost-server-socket server-socket}))
