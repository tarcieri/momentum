(ns picard.pool
  (:require
   [picard.netty :as netty])
  (:import
   [picard
    ChannelPool]
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
  [[pool client-factory] addr handler callback]
  (if-let [conn (.checkout pool (netty/mk-socket-addr addr))]
    (return-conn conn handler callback false)
    (netty/connect-client
     client-factory addr
     #(return-conn % handler callback true))))

(defn checkin-conn
  "Returns a connection to the pool"
  [[^ChannelPool pool] ^Channel conn]
  (when (.isOpen conn)
    (.. conn getPipeline removeLast)
    (.checkin pool conn)))

(defn close-conn
  [_ ^Channel conn]
  (.close conn))

(def default-options
  {:expire-after 60})

(defn mk-pool
  ([]
     (mk-pool {}))
  ([options]
     (let [options (merge default-options options)]
       [(ChannelPool. (options :expire-after))
        (netty/mk-client-factory
         create-pipeline options)])))
