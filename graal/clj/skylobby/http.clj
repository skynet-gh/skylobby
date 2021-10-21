(ns skylobby.http
  (:require
    [clj-http.client :as http]
    [clojure.java.io :as io]
    [diehard.core :as dh]
    [me.raynes.fs :as raynes-fs]
    [skylobby.fs :as fs]
    [taoensso.timbre :as log])
  (:import
    (org.apache.commons.io.input CountingInputStream)))


(declare limit-download-status)


(dh/defratelimiter limit-download-status {:rate 1}) ; one update per second


; https://github.com/dakrone/clj-http/pull/220/files

(defn- insert-at
  "Addes value into a vector at an specific index."
  [v idx value]
  (-> (subvec v 0 idx)
      (conj value)
      (into (subvec v idx))))

(defn- insert-after
  "Finds an item into a vector and adds val just after it.
   If needle is not found, the input vector will be returned."
  [^clojure.lang.APersistentVector v needle value]
  (let [index (.indexOf v needle)]
    (if (neg? index)
      v
      (insert-at v (inc index) value))))

; https://github.com/dakrone/clj-http/blob/3.x/examples/progress_download.clj
(defn- wrap-downloaded-bytes-counter
  "Middleware that provides an CountingInputStream wrapping the stream output"
  [http-client]
  (fn [req]
    (let [resp (http-client req)
          counter (CountingInputStream. (:body resp))]
      (merge resp {:body                     counter
                   :downloaded-bytes-counter counter}))))

(defn download-file [state-atom url dest-file]
  (swap! state-atom assoc-in [:http-download url] {:running true})
  (log/info "Request to download" url "to" dest-file)
  (try
    (fs/make-parent-dirs dest-file)
    (http/with-middleware
      (-> http/default-middleware
          (insert-after http/wrap-url wrap-downloaded-bytes-counter)
          (conj http/wrap-lower-case-headers))
      (let [request (http/get url {:as :stream})
            ^String content-length (get-in request [:headers "content-length"] "0")
            length (Integer/valueOf content-length)
            buffer-size (* 1024 10)]
        (swap! state-atom update-in [:http-download url]
               merge
               {:current 0
                :total length})
        (with-open [^java.io.InputStream input (:body request)
                    output (io/output-stream dest-file)]
          (let [buffer (make-array Byte/TYPE buffer-size)
                ^CountingInputStream counter (:downloaded-bytes-counter request)]
            (loop []
              (let [size (.read input buffer)]
                (when (pos? size)
                  (.write output buffer 0 size)
                  (when counter
                    (try
                      (dh/with-rate-limiter {:ratelimiter limit-download-status
                                             :max-wait-ms 0}
                        (let [current (.getByteCount counter)]
                          (swap! state-atom update-in [:http-download url]
                                 merge
                                 {:current current
                                  :total length})))
                      (catch Exception e
                        (when-not (:throttled (ex-data e))
                          (log/warn e "Error updating download status")))))
                  (recur))))))))
    (catch Exception e
      (log/error e "Error downloading" url "to" dest-file)
      (raynes-fs/delete dest-file))
    (finally
      (swap! state-atom assoc-in [:http-download url :running] false)
      (fs/update-file-cache! state-atom dest-file)
      (log/info "Finished downloading" url "to" dest-file))))
