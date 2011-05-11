(ns picard.pool
  (:require
   [picard.netty :as netty])
  (:import
   [picard
    ChannelPool
    ChannelPoolCallback]
   [org.jboss.netty.channel
    Channel
    ChannelPipeline]
   [org.jboss.netty.handler.codec.http
    HttpRequestEncoder
    HttpResponseDecoder]
   [java.net
    InetSocketAddress]))

(defn- create-pipeline
  []
  (netty/create-pipeline
   :decoder (HttpResponseDecoder.)
   :encoder (HttpRequestEncoder.)))

(defn- return-conn
  [conn handler callback fresh?]
  (when (instance? Channel conn)
    (.. conn getPipeline (addLast "handler" handler)))
  (callback conn fresh?))

(defn checkout-conn
  "Calls success fn with the channel"
  [[_ pool client-factory] addr handler callback]
  (if-let [conn (.checkout pool (netty/mk-socket-addr addr))]
    (return-conn conn handler callback false)
    (netty/connect-client
     client-factory addr
     #(return-conn % handler callback true))))

(defn checkin-conn
  "Returns a connection to the pool"
  [[_ ^ChannelPool pool] ^Channel conn]
  (when (.isOpen conn)
    (.. conn getPipeline removeLast)
    (.checkin pool conn)))

(defn close-conn
  [_ ^Channel conn]
  (.close conn))

(def default-options
  {:expire-after                60
   :max-connections             1000
   :max-connections-per-address 200    ;; Not implemented yet
   :max-queued-connections      5000}) ;; Not implemented yet

(defn mk-pool
  ([]
     (mk-pool {}))
  ([options]
     (let [options (merge default-options options)
           state   (atom [0 {}])]
       [state
        (ChannelPool.
         (options :expire-after)
         (reify ChannelPoolCallback
           (channelClosed [_ addr] 1)))
        (netty/mk-client-factory
         create-pipeline options)])))
