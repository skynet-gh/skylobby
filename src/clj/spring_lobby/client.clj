(ns spring-lobby.client
  (:require
    [aleph.tcp :as tcp]
    [clojure.core.async :as async]
    [clojure.string :as string]
    [gloss.core :as gloss]
    [gloss.io :as gio]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [taoensso.timbre :refer [info trace warn]])
  (:import
    (java.net InetAddress InetSocketAddress)
    (java.security MessageDigest)
    (java.util Base64)))


(def agent-string "alt-spring-lobby-0.1")
(def agent-string)

(def protocol
  (gloss/compile-frame
    (gloss/delimited-frame 
      ["\n"]
      (gloss/string :utf-8))
    str
    str)) ; TODO parse here


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



(defn ping-loop [c]
  (async/thread
    (info "ping loop thread started")
    (loop []
      (async/<!! (async/timeout 30000))
      (info "PING")
      (if @(s/put! c "PING \n")
        (recur)
        (info "ping loop ended")))))

(defn print-loop [c state]
  (async/thread
    (info "print loop thread started")
    (loop []
      (if-let [d (s/take! c)]
        (if-let [m @d]
          (do
            (info "message:" (str "'" m "'"))
            (handle c state m)
            (recur))
          (info "print loop ended"))
        (info "print loop ended")))))

(defn exit [c]
  (info "sending EXIT message")
  @(s/put! c "EXIT\n"))

(defn login [c local-addr username password]
  (let [pw-md5-base64 (base64-encode (md5-bytes password))
        git-ref "b6e84c6023cbffac"
        user-id (rand-int Integer/MAX_VALUE)
        compat-flags "sp u"
        msg (str "LOGIN " username " " pw-md5-base64 " 0 " local-addr
                 " " agent-string "\t" user-id " " git-ref "\t" compat-flags "\n")]
    (info "sending LOGIN message")
    @(s/put! c msg)))


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

(defn send-message [c m]
  @(s/put! c m))

(defn receive [c]
  @(s/take! c))

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
(receive c)
#_
(def p (print-loop c))
#_
(async/close! p)
