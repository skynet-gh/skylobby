(ns spring-lobby.fs.smf
  (:require
    [org.clojars.smee.binary.core :as b]))


(set! *warn-on-reflection* true)


(def header-length 80)

(def minimap-length 699048)

(def map-header
  (b/ordered-map
    :magic (b/blob :length 16)
    :version :int-le
    :id :uint-le
    :map-width :int-le
    :map-height :int-le
    :square-width :int-le
    :square-textels :int-le
    :tile-size :int-le
    :floor :float-le
    :ceiling :float-le
    :heightmap-offset :int-le
    :type-offset :int-le
    :tiles-offset :int-le
    :minimap-offset :int-le
    :metalmap-offset :int-le
    :features-offset :int-le))

; https://github.com/AledLLEvans/BALobby/blob/7b018ba3e25d95e88b19e5c42446038a6adec5de/spring.lua#L166-L167
(def map-protocol
  (b/header
    map-header
    (fn [{:keys [map-width map-height heightmap-offset minimap-offset]}]
      (let [extra-headers-length (- heightmap-offset header-length)
            heightmap-length (* (inc map-width) (inc map-height))
            type-and-tiles-length (- minimap-offset heightmap-offset heightmap-length)
            metalmap-length (* (quot map-width 2) (quot map-height 2))]
        (b/ordered-map
          :extra-header (b/blob :length extra-headers-length)
          :heightmap (b/blob :length heightmap-length)
          :type-and-tiles (b/blob :length type-and-tiles-length)
          :minimap (b/blob :length minimap-length)
          :metalmap (b/blob :length metalmap-length)
          :features (b/blob))))
    (constantly nil) ; TODO writing maps
    :keep-header? true))


(defn decode-map-header
  [input-stream]
  (into (sorted-map)
    (b/decode map-header input-stream)))

(defn decode-map
  [input-stream]
  (b/decode map-protocol input-stream))


(defn image-pixels [pixels width height]
  (let [image (java.awt.image.BufferedImage. width height java.awt.image.BufferedImage/TYPE_INT_ARGB)]
    (.setRGB image 0 0 width height pixels 0 0)
    image))


(defn int-bytes [i]
  (.array
    (.putInt
      (java.nio.ByteBuffer/allocate (quot Integer/SIZE Byte/SIZE))
      (int i))))

(defn concat-bytes [& bs]
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (doseq [b bs]
      (.write baos b))
    (.toByteArray baos)))

; https://github.com/AledLLEvans/BALobby/blob/master/spring.lua#L8-L36
; not working
(def minimap-header
  (apply
    concat-bytes
    (concat
      [(.getBytes "DDS ")
       (int-bytes 124)
       (int-bytes (+ 8 4096 4194304))
       (int-bytes 1024)
       (int-bytes 1024)
       (int-bytes (* 8 0x10000))
       (int-bytes 0)
       (int-bytes 8)]
      (repeat 11 (int-bytes 0))
      [(int-bytes 32)
       (int-bytes 4)
       (.getBytes "DXT1")
       (int-bytes 0)
       (int-bytes 0)
       (int-bytes 0)
       (int-bytes 0)
       (int-bytes 0)
       (int-bytes 0x401008)
       (int-bytes 0)
       (int-bytes 0)
       (int-bytes 0)
       (int-bytes 0)])))
