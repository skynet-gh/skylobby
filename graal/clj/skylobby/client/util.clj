(ns skylobby.client.util
  (:require
    byte-streams
    [gloss.core :as gloss]
    [gloss.io :as gio])
  (:import
    (java.nio ByteBuffer)))


(set! *warn-on-reflection* true)


; https://springrts.com/dl/LobbyProtocol/ProtocolDescription.html#MYBATTLESTATUS:client
(def default-battle-status
  {:ready true
   :ally 0
   :handicap 0
   :mode false
   :sync 2 ; unsynced
   :id 0
   :side 0})

(def default-client-status-str "0")


(def client-status-protocol
  (gloss/compile-frame
    (gloss/bit-map
      :prefix 1
      :bot 1
      :access 1
      :rank 3
      :away 1
      :ingame 1)))

(def battle-status-protocol
  (gloss/compile-frame
    (gloss/bit-map
      :prefix 5
      :side 3
      :sync 2
      :pad 4
      :handicap 7
      :mode 1
      :ally 4
      :id 4
      :ready 1
      :suffix 1)))


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


(def default-client-status
  (decode-client-status default-client-status-str))


(defn encode-client-status [client-status]
  (str
    (.get
      ^ByteBuffer
      (gio/to-byte-buffer
        (gio/encode client-status-protocol
          (assoc
            (merge default-client-status client-status)
            :prefix false)))
      0)))

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

(defn encode-battle-status [battle-status]
  (str
    (.getInt
      ^ByteBuffer
      (gio/to-byte-buffer
        (gio/encode battle-status-protocol
          (assoc
            (merge default-battle-status battle-status)
            :prefix 0
            :pad 0
            :suffix 0))))))
