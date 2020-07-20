(ns spring-lobby.client
  (:require
    [aleph.tcp :as tcp]
    [byte-streams]
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [gloss.core :as gloss]
    [gloss.io :as gio]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [org.clojars.smee.binary.core :as b]
    [taoensso.timbre :refer [info trace warn]])
  (:import
    (java.net InetAddress InetSocketAddress)
    (java.nio ByteBuffer)
    (java.security MessageDigest)
    (java.util Base64)))


(def agent-string "alt-spring-lobby-0.1")

(def protocol
  (gloss/compile-frame
    (gloss/delimited-frame
      ["\n"]
      (gloss/string :utf-8))
    str
    str)) ; TODO parse here

(def client-status-protocol
  (gloss/compile-frame
    (gloss/bit-map :prefix 1 :bot 1 :access 1 :rank 3 :away 1 :ingame 1)))

(def battle-status-protocol
  (gloss/compile-frame
    (gloss/bit-map :prefix 6 :side 2 :sync 2 :pad 4 :handicap 7 :mode 1 :ally 4 :id 4 :ready 1 :suffix 1)))

(defn decode-client-status [status-str]
  (dissoc
    (gio/decode client-status-protocol
      (byte-streams/convert
        (.array
          (.put
            (ByteBuffer/allocate 1)
            (Byte/parseByte status-str)))
        ByteBuffer))
    :prefix))

#_
(decode-client-status "8")
#_
(decode-client-status "127")

(defn decode-battle-status [status-str]
  (dissoc
    (gio/decode battle-status-protocol
      (byte-streams/convert
        (.array
          (.putInt
            (ByteBuffer/allocate (quot Integer/SIZE Byte/SIZE))
            (Integer/parseInt status-str)))
        ByteBuffer))
    :prefix :pad :suffix))

#_
(let [f (gloss/compile-frame
          (gloss/bit-map :prefix 6 :side 2 :sync 2 :pad 4 :handicap 7 :mode 1 :ally 4 :id 4 :ready 1 :suffix 1))]
  (gio/decode f (byte-streams/convert (.toByteArray (.toBigInteger (bigint "8389632"))) ByteBuffer)))
#_
(decode-battle-status "8389632")
#_
(count (Integer/toBinaryString 8389632))
#_
(io/input-stream (.toByteArray (.toBigInteger (bigint "8389632"))))

; https://stackoverflow.com/a/39188819/984393
(defn base64-encode [bs]
  (.encodeToString (Base64/getEncoder) bs))

; https://gist.github.com/jizhang/4325757
(defn md5-bytes [s]
  (let [algorithm (MessageDigest/getInstance "MD5")]
    (.digest algorithm (.getBytes s))))


; https://aleph.io/examples/literate.html#aleph.examples.tcp

(defn wrap-duplex-stream
  [protocol s]
  (let [out (s/stream)]
    (s/connect
      (s/map #(gio/encode protocol %) out)
      s)
    (s/splice
      out
      (gio/decode-stream s protocol))))

(defn client
  [host port]
  (d/chain (tcp/client {:host host
                        :port port})
    #(wrap-duplex-stream protocol %)))


(defn send-message [c m]
  (info ">" (str "'" m "'"))
  @(s/put! c (str m "\n")))

(defmulti handle
  (fn [c state m]
    (-> m
        (string/split #"\s")
        first)))

(defmethod handle :default [c state m]
  (trace "no handler for message" (str "'" m "'")))

(defn parse-adduser [m]
  (re-find #"\w+ (\w+) ([^\s]+) (\w+) (.*)" m))

#_
(parse-adduser "ADDUSER skynet ?? 8 SpringLobby 0.270 (win x32)")
#_
(parse-adduser "ADDUSER ChanServ US None ChanServ")

(defmethod handle "ADDUSER" [c state m]
  (info (str "'" m "'"))
  (let [[all username country id user-agent] (parse-adduser m)
        user {:username username
              :country country
              :user-id id
              :user-agent user-agent}]
    (swap! state assoc-in [:users username] user)))

(defmethod handle "REMOVEUSER" [c state m]
  (info (str "'" m "'"))
  (let [[all username] (re-find #"\w+ (\w+)" m)]
    (swap! state update :users dissoc username)))


(defn parse-battleopened [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)\t([^\t]+)" m))

#_
(parse-battleopened "BATTLEOPENED 1 0 0 skynet 192.168.1.6 8452 8 1 0 -1706632985 Spring	104.0.1-1510-g89bb8e3 maintenance	Mini_SuperSpeedMetal	deth	Balanced Annihilation V10.24	__battle__8")

(defmethod handle "BATTLEOPENED" [c state m]
  (if-let [[all battle-id battle-type battle-nat-type host-username battle-ip battle-port battle-maxplayers battle-passworded battle-rank battle-maphash battle-engine battle-version battle-map battle-title battle-modname battle-name] (parse-battleopened m)]
    (let [battle {:battle-id battle-id
                  :battle-type battle-type
                  :battle-nat-type battle-nat-type
                  :host-username host-username
                  :battle-ip battle-ip
                  :battle-port battle-port
                  :battle-maxplayers battle-maxplayers
                  :battle-passworded battle-passworded
                  :battle-rank battle-rank
                  :battle-maphash battle-maphash
                  :battle-engine battle-engine
                  :battle-version battle-version
                  :battle-map battle-map
                  :battle-title battle-title
                  :battle-modname battle-modname
                  :battle-name battle-name}]
      (swap! state assoc-in [:battles battle-id] battle))
    (warn "Unable to parse BATTLEOPENED")))

(defn parse-updatebattleinfo [m]
  (re-find #"[^\s]+ ([^\s]+) ([^\s]+) ([^\s]+) ([^\s]+) (.+)" m))

#_
(parse-updatebattleinfo "UPDATEBATTLEINFO 1 0 0 1465550451 Archers_Valley_v6")

(defmethod handle "UPDATEBATTLEINFO" [c state m]
  (let [[all battle-id battle-spectators battle-locked battle-maphash battle-map] (parse-updatebattleinfo m)
        battle {:battle-id battle-id
                :battle-spectators battle-spectators
                :battle-locked battle-locked
                :battle-maphash battle-maphash
                :battle-map battle-map}]
    (swap! state update-in [:battles battle-id] merge battle)))

(defmethod handle "BATTLECLOSED" [c state m]
  (let [[all battle-id] (re-find #"\w+ (\w+)" m)]
    (swap! state update :battles dissoc battle-id)))

(defmethod handle "CLIENTSTATUS" [c state m]
  (let [[all username client-status] (re-find #"\w+ (\w+) (\w+)" m)]
    (swap! state assoc-in [:users username :client-status] (decode-client-status client-status))))

(defmethod handle "JOIN" [c state m]
  (let [[all battle-name] (re-find #"\w+ (\w+)" m)]
    (swap! state assoc-in [:battle :battle-name] battle-name)))

(defmethod handle "JOINED" [c state m]
  (let [[all battle-name username] (re-find #"\w+ (\w+) (\w+)" m)]
    (swap! state assoc-in [:battle :users username] {})))

(defmethod handle "REQUESTBATTLESTATUS" [c state m]
  (send-message c "MYBATTLESTATUS 0 0")) ; TODO real status

(defmethod handle "CLIENTBATTLESTATUS" [c state m]
  (let [[all username battle-status team-color] (re-find #"\w+ (\w+) (\w+) (\w+)" m)]
    (swap! state update-in [:battle :users username] merge {:battle-status (decode-battle-status battle-status)
                                                            :team-color team-color})))

(defn ping-loop [c]
  (async/thread
    (info "ping loop thread started")
    (loop []
      (async/<!! (async/timeout 30000))
      (if (send-message c "PING")
        (recur)
        (info "ping loop ended")))))

(defn print-loop [c state]
  (async/thread
    (info "print loop thread started")
    (loop []
      (if-let [d (s/take! c)]
        (if-let [m @d]
          (do
            (info "<" (str "'" m "'"))
            (handle c state m)
            (recur))
          (info "print loop ended"))
        (info "print loop ended")))))

(defn exit [c]
  (send-message c "EXIT"))

(defn login [c local-addr username password]
  (let [pw-md5-base64 (base64-encode (md5-bytes password))
        git-ref "b6e84c6023cbffac"
        user-id (rand-int Integer/MAX_VALUE)
        compat-flags "sp u"
        msg (str "LOGIN " username " " pw-md5-base64 " 0 " local-addr
                 " " agent-string "\t" user-id " " git-ref "\t" compat-flags)]
    (send-message c msg)))


(defn connect [state]
  (let [address "192.168.1.6"
        c @(client address 8200)]
    (print-loop c state)
    (login c address "skynet9001" "1234dogs")
    (ping-loop c)
    c))

(defn disconnect [c]
  (info "disconnecting")
  (exit c)
  (.close c)
  (info "connection closed?" (.isClosed c)))

(defn open-battle [c {:keys [battle-type nat-type battle-password host-port max-players mod-hash rank map-hash
                             engine engine-version map-name title mod-name]
                      :or {battle-type 0
                           nat-type 0
                           battle-password "*"
                           host-port 8452
                           max-players 8
                           rank 0
                           engine "Spring"}
                      :as opts}]
  (send-message c
    (str "OPENBATTLE " battle-type " " nat-type " " battle-password " " host-port " " max-players
         " " mod-hash " " rank " " map-hash " " engine "\t" engine-version "\t" map-name "\t" title
         "\t" mod-name)))

#_
(do
  (let [s (atom {})]
    (def c (connect s))
    (def state s)))
#_
(disconnect c)
#_
(-> state deref :users)
#_
(-> state deref :battles)
#_
(swap! state assoc :battles {})
#_
(send-message c "LISTCOMPFLAGS\n")
#_
(send-message c "CHANNELS\n")
#_
(send-message c "GETCHANNELMESSAGES\n")
#_
(send-message c "GETUSERINFO skynet9001\n")
#_
(send-message c "IGNORELIST skynet9001\n")
#_
(def p (print-loop c))
#_
(async/close! p)
