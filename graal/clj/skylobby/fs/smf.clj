(ns skylobby.fs.smf
  (:require
    [byte-streams]
    [clojure.java.io :as io]
    [org.clojars.smee.binary.core :as b]
    [skylobby.util :as u])
  (:import
    (gr.zdimensions.jsquish Squish Squish$CompressionType)
    (java.awt.image BufferedImage)
    (java.nio ByteBuffer ByteOrder)))


(set! *warn-on-reflection* true)


(def header-length 84)

; https://github.com/enetheru/smf_tools/blob/master/src/smf.h#L13-L16
(def minimap-length 699048)

(def minimap-size 1024)

(def minimap-display-size 512)

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
    :features-offset :int-le
    :extra-header-count :int-le))

(def extra-header-protocol
  (b/header
    (b/ordered-map
      :size :int-le
      :type :int-le)
    (fn [{:keys [size]}]
      (b/blob :length size)) ; (- size 8) ; size of header
    (constantly nil) ; TODO writing maps
    :keep-header? true))

; https://github.com/AledLLEvans/BALobby/blob/7b018ba3e25d95e88b19e5c42446038a6adec5de/spring.lua#L166-L167
(def map-protocol
  (b/header
    map-header
    (fn [{:keys [extra-header-count]}]
      (b/ordered-map
        :extra-headers (b/repeated extra-header-protocol :length extra-header-count)
        :smf-body (b/blob)))
    (constantly nil) ; TODO writing maps
    :keep-header? true))


(defn decode-map-header
  [input-stream]
  (into (sorted-map)
    (b/decode map-header input-stream)))


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

(defn heightmap-image [map-width map-height heightmap-bytes]
  (when (and (number? map-width)
             (number? map-height)
             heightmap-bytes)
    (let [width (inc map-width)
          height (inc map-height)
          bi (BufferedImage. width height BufferedImage/TYPE_BYTE_GRAY)]
      (.setDataElements
        (.getRaster bi)
        0 0 width height
        heightmap-bytes)
      bi)))

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


(defn subbytes [bs offset length]
  (:data
    (b/decode
      (b/ordered-map
        :offset (b/blob :length offset)
        :data (b/blob :length length))
      (io/input-stream bs))))

; https://github.com/AledLLEvans/BALobby/blob/7b018ba3e25d95e88b19e5c42446038a6adec5de/spring.lua#L192-L202
(defn decode-heightmap [bs]
  (byte-array
    (into-array
      (map-indexed
        (fn [_i [_a b]]
          (byte b))
        (partition 2 (seq bs))))))


(defn scale-minimap-image [minimap-width minimap-height minimap-image]
  (when minimap-image
    (let [scaled (BufferedImage. minimap-width minimap-height BufferedImage/TYPE_INT_RGB)
          graphics2d (.createGraphics scaled)]
      (.drawImage graphics2d minimap-image 0 0 minimap-width minimap-height nil)
      (.dispose graphics2d)
      scaled)))

(defn minimap-dimensions [map-smf-header]
  (let [{:keys [map-width map-height]} map-smf-header]
    (when (and map-height map-width)
      (let [ratio-x (/ minimap-display-size map-width)
            ratio-y (/ minimap-display-size map-height)
            min-ratio (min ratio-x ratio-y)
            normal-x (/ ratio-x min-ratio)
            normal-y (/ ratio-y min-ratio)
            invert-x (/ min-ratio ratio-x)
            invert-y (/ min-ratio ratio-y)
            convert-x (if (< ratio-y ratio-x) invert-x normal-x)
            convert-y (if (< ratio-x ratio-y) invert-y normal-y)
            minimap-width (* minimap-display-size convert-x)
            minimap-height (* minimap-display-size convert-y)]
        {:minimap-width (or minimap-width minimap-display-size)
         :minimap-height (or minimap-height minimap-display-size)}))))

(defn decode-map
  ([input-stream]
   (decode-map input-stream nil))
  ([input-stream {:keys [header-only map-images]}]
   (let [all-bytes (u/slurp-bytes input-stream)
         header (b/decode map-header (io/input-stream all-bytes))]
     (merge
       {:header header}
       (when-not header-only
         (let [
               {:keys [heightmap-offset map-height map-width metalmap-offset minimap-offset]} header
               heightmap-length (* 2 (inc map-width) (inc map-height))
               metalmap-length (* (quot map-width 2) (quot map-height 2))
               {:keys [minimap-width minimap-height]} (minimap-dimensions header)]
           (merge
             {:minimap-height minimap-height
              :minimap-width minimap-width}
             (when map-images
               (let [
                     ^"[B" heightmap-bytes (decode-heightmap (subbytes all-bytes heightmap-offset heightmap-length))
                     minimap-bytes (subbytes all-bytes minimap-offset minimap-length)
                     metalmap-bytes (subbytes all-bytes metalmap-offset metalmap-length)
                     minimap-image (decompress-minimap minimap-bytes)]
                 {
                  :heightmap-image (heightmap-image map-width map-height heightmap-bytes)
                  :metalmap-image (metalmap-image map-width map-height metalmap-bytes)
                  :minimap-image minimap-image})))))))))
