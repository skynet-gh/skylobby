(ns spring-lobby.client.util
  (:require
    byte-streams
    [gloss.core :as gloss]
    [gloss.io :as gio])
  (:import
    (java.nio ByteBuffer)))


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
