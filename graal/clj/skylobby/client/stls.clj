(ns skylobby.client.stls
  (:require
    [taoensso.timbre :as log])
  (:import
    (io.netty.channel ChannelPipeline)
    (io.netty.handler.ssl SslContextBuilder SslHandler)
    (io.netty.handler.ssl.util InsecureTrustManagerFactory)))


(set! *warn-on-reflection* true)


(defn upgrade-pipeline [^ChannelPipeline pipeline]
  (if pipeline
    (let [; https://github.com/clj-commons/aleph/blob/master/src/aleph/netty.clj#L721-L724
          ssl-context-builder (SslContextBuilder/forClient)
          _ (.trustManager ssl-context-builder InsecureTrustManagerFactory/INSTANCE)
          _ (.startTls ssl-context-builder true)
          ssl-context (.build ssl-context-builder)
          ch (.channel pipeline)
          engine (.newEngine ssl-context (.alloc ch))
          handler (SslHandler. engine false)
          handshake-future (.handshakeFuture handler)]
      (.addFirst pipeline "ssl" handler)
      (log/info "Added SslHandler to TCP pipeline")
      (log/info "Waiting for SSL handshake")
      (let [handshake @handshake-future]
        (log/info "SSL handshake finished" handshake)
        true))
    (log/warn "No TCP pipeline to upgrade to TLS!")))
