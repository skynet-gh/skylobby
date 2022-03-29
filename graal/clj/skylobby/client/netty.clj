(ns skylobby.client.netty
  (:require
    [aleph.netty :as netty]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [manifold.stream.core :as manifold]
    [taoensso.timbre :as log])
  (:import
    (io.netty.channel 
      Channel
      ChannelHandler
      ChannelHandlerContext
      ChannelInboundHandler)
    (java.io IOException)
    (java.util.concurrent ConcurrentHashMap)
    (java.util.concurrent.atomic AtomicLong)))


#_
(declare 
  create-client
  insecure-ssl-client-context
  put!
  release-buf->array
  sink
  ssl-client-context
  to-byte-buf)



#_
(defmacro channel-inbound-handler
  [& {:as handlers}]
  `(reify
     ChannelHandler
     ChannelInboundHandler

     (handlerAdded
       ~@(or (:handler-added handlers) `([_# _#])))
     (handlerRemoved
       ~@(or (:handler-removed handlers) `([_# _#])))
     (exceptionCaught
       ~@(or (:exception-caught handlers)
           `([_# ctx# cause#
              (.fireExceptionCaught ctx# cause#)])))
     (channelRegistered
       ~@(or (:channel-registered handlers)
           `([_# ctx#
              (.fireChannelRegistered ctx#)])))
     (channelUnregistered
       ~@(or (:channel-unregistered handlers)
           `([_# ctx#
              (.fireChannelUnregistered ctx#)])))
     (channelActive
       ~@(or (:channel-active handlers)
           `([_# ctx#
              (.fireChannelActive ctx#)])))
     (channelInactive
       ~@(or (:channel-inactive handlers)
           `([_# ctx#
              (.fireChannelInactive ctx#)])))
     (channelRead
       ~@(or (:channel-read handlers)
           `([_# ctx# msg#
              (.fireChannelRead ctx# msg#)])))
     (channelReadComplete
       ~@(or (:channel-read-complete handlers)
           `([_# ctx#
              (.fireChannelReadComplete ctx#)])))
     (userEventTriggered
       ~@(or (:user-event-triggered handlers)
           `([_# ctx# evt#
              (.fireUserEventTriggered ctx# evt#)])))
     (channelWritabilityChanged
       ~@(or (:channel-writability-changed handlers)
           `([_# ctx#
              (.fireChannelWritabilityChanged ctx#)])))))


#_
(defn close [x]
  (if (instance? Channel x)
    (.close ^Channel x)
    (.close ^ChannelHandlerContext x)))

#_
(def sink-close-marker ::sink-close)


#_
(defn write-and-flush
  [x msg]
  (if (instance? Channel x)
    (.writeAndFlush ^Channel x msg)
    (.writeAndFlush ^ChannelHandlerContext x msg)))


#_
(defn- connection-stats [^Channel ch inbound?]
  (merge
    {:local-address (str (.localAddress ch))
     :remote-address (str (.remoteAddress ch))
     :writable? (.isWritable ch)
     :readable? (-> ch .config .isAutoRead)
     :closed? (not (.isActive ch))}
    (let [^ConcurrentHashMap throughput (if inbound?
                                          netty/channel-inbound-throughput
                                          netty/channel-outbound-throughput)]
      (when-let [^AtomicLong throughput (.get throughput ch)]
        {:throughput (.get throughput)}))))

#_
(manifold/def-sink ChannelSink
  [coerce-fn
   downstream?
   ^Channel ch
   additional-description]
  (close [this]
    (when downstream?
      (close ch))
    (.markClosed this)
    true)
  (description [_]
    (let [ch (netty/channel ch)]
      (merge
        {:type       "netty"
         :closed?    (not (.isActive ch))
         :sink?      true
         :connection (assoc (connection-stats ch false)
                       :direction :outbound)}
        (additional-description))))
  (isSynchronous [_]
    false)
  (put [this msg blocking?]
    (if (s/closed? this)
      (if blocking?
        false
        (d/success-deferred false))
      (let [msg (try
                  (coerce-fn msg)
                  (catch Exception e
                    (log/error e
                      (str "cannot coerce "
                        (.getName (class msg))
                        " into binary representation"))
                    (close ch)))
            d (cond
                (nil? msg)
                (d/success-deferred true)

                (identical? sink-close-marker msg)
                (do
                  (.markClosed this)
                  (d/success-deferred false))

                :else
                (let [^ChannelFuture f (write-and-flush ch msg)]
                  (-> f
                    netty/wrap-future
                    (d/chain' (fn [_] true))
                    (d/catch' IOException (fn [_] false)))))]
        (if blocking?
          @d
          d))))
  (put [this msg blocking? timeout timeout-value]
    (.put this msg blocking?)))

#_
(defn sink
  ([ch]
   (sink ch true identity (fn [])))
  ([ch downstream? coerce-fn]
   (sink ch downstream? coerce-fn (fn [])))
  ([ch downstream? coerce-fn additional-description]
   (let [sink (->ChannelSink
                coerce-fn
                downstream?
                ch
                additional-description)]

     (d/chain'
       (netty/wrap-future (.closeFuture (netty/channel ch)))
       (fn [_] (s/close! sink)))

     (doto sink (reset-meta! {:aleph/channel ch})))))

#_
(defn source
  [^Channel ch]
  (let [src (s/stream*
              {:description
               (fn [m]
                 (assoc m
                   :type "netty"
                   :direction :inbound
                   :connection (assoc (connection-stats ch true)
                                 :direction :inbound)))})]
    (doto src (reset-meta! {:aleph/channel ch}))))
