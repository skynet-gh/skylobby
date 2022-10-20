(ns skylobby.client.tcp
  (:require
    [aleph.netty :as aleph-netty]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (java.net
      InetSocketAddress)
    (io.netty.channel 
      ChannelHandler
      ChannelInboundHandler
      ChannelPipeline)))


(set! *warn-on-reflection* true)


; copied from https://raw.githubusercontent.com/clj-commons/aleph/master/src/aleph/tcp.clj
; replace reify with defrecord to fix reflect warning in native image


(defrecord InboundHandler [d in raw-stream?]
  ChannelHandler
  ChannelInboundHandler

  (handlerAdded 
    [_ _])
  (handlerRemoved
    [_ _])
  (exceptionCaught
    [_ _ctx ex]
    (when-not (d/error! d ex)
      (log/warn ex "error in TCP client")))
  (channelRegistered
    [_ ctx]
    (.fireChannelRegistered ctx))
  (channelUnregistered
    [_ ctx]
    (.fireChannelUnregistered ctx))
  (channelInactive
    [_ ctx]
    (s/close! @in)
    (.fireChannelInactive ctx))
  (channelActive
    [_ ctx]
    (let [ch (.channel ctx)]
      (d/success! d
        (doto
          (s/splice
            (aleph-netty/sink ch true aleph-netty/to-byte-buf)
            (reset! in (aleph-netty/source ch)))
          (reset-meta! {:aleph/channel ch})))
      (.fireChannelActive ctx)))
  (channelRead
    [_ ctx msg]
    (aleph-netty/put! (.channel ctx) @in
      (if raw-stream?
        msg
        (aleph-netty/release-buf->array msg))))
  (channelReadComplete
    [_ ctx]
    (.fireChannelReadComplete ctx))
  (userEventTriggered
    [_ ctx evt]
    (.fireUserEventTriggered ctx evt))
  (channelWritabilityChanged
    [_ ctx]
    (.fireChannelWritabilityChanged ctx)))


(defn- client-channel-handler
  [{:keys [raw-stream?]}]
  (let [d (d/deferred)
        in (atom nil)]
    [d
     (map->InboundHandler
       {:d d
        :in in
        :raw-stream? raw-stream?})]))


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
      (aleph-netty/create-client
        (fn [^ChannelPipeline pipeline]
          (.addLast pipeline "handler" ^ChannelHandler handler)
          (when pipeline-transform
            (pipeline-transform pipeline)))
        (if ssl-context
          ssl-context
          (when ssl?
            (if insecure?
              (aleph-netty/insecure-ssl-client-context)
              (aleph-netty/ssl-client-context))))
        bootstrap-transform
        (or remote-address (InetSocketAddress. ^String host (int port)))
        local-address
        epoll?)
      (d/catch' #(d/error! s %)))
    s))
