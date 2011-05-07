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

(defn- mk-conn
  [[host port] success error]
  (netty/connect-client (create-pipeline) host port success))

(defn- return-conn
  [conn handler success]
  (.. conn getPipeline (addLast "handler" handler))
  (success conn))

(defn checkout-conn
  "Calls success fn with the channel"
  ([pool addr handler success] (checkout-conn pool addr handler success nil))
  ([pool [host port :as  addr] handler success error]
     (if-let [conn (.checkout pool (InetSocketAddress. host port))]
       (return-conn conn handler success)
       (mk-conn addr #(return-conn % handler success) error))))

(defn checkin-conn
  "Returns a connection to the pool"
  [^ChannelPool pool ^Channel conn]
  (when (.isOpen conn)
    (.. conn getPipeline removeLast)
    (.checkin pool conn)))

(defn close-conn
  [^ChannelPool pool ^Channel conn]
  (.close conn))

(defn mk-pool
  ([]
     (mk-pool 60))
  ([expire-after]
     (ChannelPool. expire-after)))
