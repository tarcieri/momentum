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

(defn- increment-count-for
  [state addr]
  (swap!
   state
   (fn [[total by-addrs]]
     [(inc total)
      (update-in by-addrs [addr] #(if % (inc %) 1))])))

(defn- decrement-count-for
  [state addr]
  (swap!
   state
   (fn [[total by-addrs]]
     [(dec total)
      (if (> (by-addrs addr 0) 1)
        (assoc by-addrs addr (inc (by-addrs addr)))
        (dissoc by-addrs addr))])))

(defn- return-conn
  [[state] conn handler callback fresh?]
  (when (instance? Channel conn)
    (.. conn getPipeline (addLast "handler" handler))
    (when fresh?
      (increment-count-for state (.getRemoteAddress conn))))
  (callback conn fresh?))

(defn- checkout-conn*
  [[_ pool] addr]
  (.checkout pool (netty/mk-socket-addr addr)))

(defn- connect-client
  [[_ _ factory] addr callback]
  (netty/connect-client factory addr callback))

(defn checkout-conn
  "Calls success fn with the channel"
  [pool addr handler callback]
  (if-let [conn (checkout-conn* pool addr)]
    (return-conn pool conn handler callback false)
    (connect-client pool addr #(return-conn pool % handler callback true))))

(defn checkin-conn
  "Returns a connection to the pool"
  [[state ^ChannelPool pool] ^Channel conn]
  (if (.isOpen conn)
    (do (.. conn getPipeline removeLast)
        (.checkin pool conn))
    (decrement-count-for state (.getRemoteAddress conn))))

(defn close-conn
  "Closes a connection that cannot be reused. The connection is
   not returned to the pool."
  [[state] ^Channel conn]
  (decrement-count-for state (.getRemoteAddress conn))
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
        ;; The channel pool with a callback that tracks open
        ;; connections
        (ChannelPool.
         (options :expire-after)
         (reify ChannelPoolCallback
           (channelClosed [_ addr]
             (decrement-count-for state addr))))
        (netty/mk-client-factory
         create-pipeline options)
        options])))
