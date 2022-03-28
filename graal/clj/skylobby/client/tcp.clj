(ns skylobby.client.tcp
  (:require
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [aleph.netty :as netty]
    [clojure.tools.logging :as log])
  (:import
    [java.net
     InetSocketAddress]
    [io.netty.channel
     ChannelHandler
     ChannelPipeline]))


; copied from https://raw.githubusercontent.com/clj-commons/aleph/master/src/aleph/tcp.clj
; removing things that cause native image issues


(defn- ^ChannelHandler client-channel-handler
  [{:keys [raw-stream?]}]
  (let [d (d/deferred)
        in (atom nil)]
    [d

     (netty/channel-inbound-handler

       :exception-caught
       ([_ ctx ex]
        (when-not (d/error! d ex)
          (log/warn ex "error in TCP client")))

       :channel-inactive
       ([_ ctx]
        (s/close! @in)
        (.fireChannelInactive ctx))

       :channel-active
       ([_ ctx]
        (let [ch (.channel ctx)]
          (d/success! d
            (doto
              (s/splice
                (netty/sink ch true netty/to-byte-buf)
                (reset! in (netty/source ch)))
              (reset-meta! {:aleph/channel ch}))))
        (.fireChannelActive ctx))

       :channel-read
       ([_ ctx msg]
        (netty/put! (.channel ctx) @in
          (if raw-stream?
            msg
            (netty/release-buf->array msg))))

       :close
       ([_ ctx promise]
        (.close ctx promise)
        (d/error! d (IllegalStateException. "unable to connect"))))]))

(defn client
  "Given a host and port, returns a deferred which yields a duplex stream that can be used
   to communicate with the server.

   |:---|:----
   | `host` | the hostname of the server.
   | `port` | the port of the server.
   | `remote-address` | a `java.net.SocketAddress` specifying the server's address.
   | `local-address` | a `java.net.SocketAddress` specifying the local network interface to use.
   | `ssl-context` | an explicit `io.netty.handler.ssl.SslHandler` to use. Defers to `ssl?` and `insecure?` configuration if omitted.
   | `ssl?` | if true, the client attempts to establish a secure connection with the server.
   | `insecure?` | if true, the client will ignore the server's certificate.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.Bootstrap` object, which represents the client, and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `raw-stream?` | if true, messages from the stream will be `io.netty.buffer.ByteBuf` objects rather than byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users."
  [{:keys [host port remote-address local-address ssl-context ssl? insecure? pipeline-transform bootstrap-transform epoll?]
    :or {bootstrap-transform identity
         epoll? false}
    :as options}]
  (let [[s handler] (client-channel-handler options)]
    (->
      (netty/create-client
        (fn [^ChannelPipeline pipeline]
          (.addLast pipeline "handler" ^ChannelHandler handler)
          (when pipeline-transform
            (pipeline-transform pipeline)))
        (if ssl-context
          ssl-context
          (when ssl?
            (if insecure?
              (netty/insecure-ssl-client-context)
              (netty/ssl-client-context))))
        bootstrap-transform
        (or remote-address (InetSocketAddress. ^String host (int port)))
        local-address
        epoll?)
      (d/catch' #(d/error! s %)))
    s))
