(ns spring-lobby.fs.smf
  (:require
    [byte-streams]
    [org.clojars.smee.binary.core :as b])
  (:import
    (gr.zdimensions.jsquish Squish Squish$CompressionType)
    (java.awt.image BufferedImage)
    (java.nio ByteBuffer ByteOrder)))


(set! *warn-on-reflection* true)


(def header-length 84)

; https://github.com/enetheru/smf_tools/blob/master/src/smf.h#L13-L16
(def minimap-length 699048)

(def minimap-size 1024)

; https://springrts.com/wiki/Mapdev:SMF_format

(def map-header
  (b/ordered-map
    :magic (b/string "ISO-8859-1" :length 16)
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
    (fn [{:keys [map-width map-height minimap-offset metalmap-offset]}]
      (let [metalmap-length (* (quot map-width 2) (quot map-height 2))]
        (if (< minimap-offset metalmap-offset)
          (let [before-minimap (- minimap-offset header-length)
                pad-length (- metalmap-offset
                              (+ minimap-length minimap-offset))]
            (b/ordered-map
              :prefix (b/blob :length before-minimap)
              :minimap (b/blob :length minimap-length)
              :pad (b/blob :length pad-length)
              :metalmap (b/blob :length metalmap-length)
              :rest (b/blob)))
          (let [before-metalmap (- metalmap-offset header-length)
                pad-length (- minimap-offset
                              (+ metalmap-length metalmap-offset))]
            (b/ordered-map
              :prefix (b/blob :length before-metalmap)
              :metalmap (b/blob :length metalmap-length)
              :pad (b/blob :length pad-length)
              :minimap (b/blob :length minimap-length)
              :rest (b/blob))))))
    (constantly nil) ; TODO writing maps
    :keep-header? true))


(defn decode-map-header
  [input-stream]
  (into (sorted-map)
    (b/decode map-header input-stream)))

(defn decode-map
  [input-stream]
  (b/decode map-protocol input-stream))


(defn int-bytes [i]
  (let [buffer (ByteBuffer/allocate (quot Integer/SIZE Byte/SIZE))]
    (.order buffer ByteOrder/LITTLE_ENDIAN)
    (.array
      (.putInt
        buffer
        (int i)))))

(defn concat-bytes [& byte-arrays]
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (doseq [^bytes ba byte-arrays]
      (.write baos ba))
    (.toByteArray baos)))

; https://github.com/AledLLEvans/BALobby/blob/master/spring.lua#L8-L36
; not working
; https://docs.microsoft.com/en-us/windows/win32/direct3ddds/dds-header
(def minimap-header
  (apply
    concat-bytes
    (concat
      [(.getBytes "DDS ")                     ; magic
       (int-bytes 124)                        ; dwSize
       (int-bytes (+ 1 2 4 0x1000 0x20000))   ; dwFlags
       ; ^ https://github.com/christliu/Texture/blob/bcb57f95ca67fe0614b9b6f2f0ec6993ff38a1da/dds/src/dds_helper.cpp#L444
       (int-bytes 1024)                       ; dwHeight
       (int-bytes 1024)                       ; dwWidth
       (int-bytes (* 8 0x10000))              ; dwPitchOrLinearSize
       (int-bytes 0)                          ; dwDepth
       (int-bytes 9)]                         ; dwMipMapCount
      (repeat 11 (int-bytes 0))               ; dwReserved1
      ; start ddspf https://docs.microsoft.com/en-us/windows/win32/direct3ddds/dds-pixelformat
      [(int-bytes 32)                         ; size
       (int-bytes 0x4)                        ; flags
       (.getBytes "DXT1")                     ; format
       (int-bytes 0)                          ;
       (int-bytes 0)                          ;
       (int-bytes 0)                          ;
       (int-bytes 0)                          ;
       (int-bytes 0)                          ; end ddspf
       (int-bytes (+ 0x8 0x1000 0x400000))    ; dwCaps
       (int-bytes 0)                          ; dwCaps2
       (int-bytes 0)                          ; dwCaps3
       (int-bytes 0)                          ; dwCaps4
       (int-bytes 0)])))                      ; dwReserved2

; https://stackoverflow.com/a/18105498/984393
(defn decompress-minimap [minimap-compressed]
  (let [rgba (Squish/decompressImage nil minimap-size minimap-size minimap-compressed Squish$CompressionType/DXT1)
        bi (BufferedImage. minimap-size minimap-size BufferedImage/TYPE_4BYTE_ABGR)]
    (.setDataElements
      (.getRaster bi)
      0 0 minimap-size minimap-size
      rgba)
    bi))

(defn metalmap-image [map-width map-height metalmap-bytes]
  (when (and (number? map-width)
             (number? map-height)
             metalmap-bytes)
    (let [width (quot map-width 2)
          height (quot map-height 2)
          bi (BufferedImage. width height BufferedImage/TYPE_BYTE_GRAY)]
      (.setDataElements
        (.getRaster bi)
        0 0 width height
        metalmap-bytes)
      bi)))
