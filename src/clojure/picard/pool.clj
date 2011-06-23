(ns picard.pool
  (:use
   [picard.utils :rename {debug debug*}])
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

(defmacro debug
  [& msgs]
  `(debug* :pool ~@msgs))

(defn- create-pipeline
  []
  (netty/create-pipeline
   :decoder (HttpResponseDecoder.)
   :encoder (HttpRequestEncoder.)))

(defn- increment-count-for
  [[state _ _ options] addr]
  (dosync
   (let [[total by-addrs] @state]
     (when (and (< total (options :max-connections))
                (< (by-addrs addr 0) (options :max-connections-per-address)))
       (ref-set
        state
        [(inc total) (assoc by-addrs addr (inc (by-addrs addr 0)))])))))

(defn- decrement-count-for
  [state addr]
  (dosync
   (alter
    state
    (fn [[total by-addrs]]
      [(dec total)
       (if (> (by-addrs addr 0) 1)
         (assoc by-addrs addr (dec (by-addrs addr)))
         (dissoc by-addrs addr))]))))

(defn- return-conn
  [pool conn handler callback fresh?]
  (when (instance? Channel conn)
    (.. conn getPipeline (addLast "handler" handler)))
  (callback conn fresh?))

(defn- checkout-conn*
  [[_ pool] addr]
  (.checkout pool (netty/mk-socket-addr addr)))

(defn- connect-client
  [[_ _ factory opts] addr callback]
  (netty/connect-client factory addr (opts :local-addr) callback))

(defn checkout-conn
  "Calls success fn with the channel"
  [[state :as pool] addr handler callback]
  (if-let [conn (checkout-conn* pool addr)]
    ;; There is a fresh connection available
    (do
      (debug "Checking out connection from pool")
      (return-conn pool conn handler callback false))
    ;; Otherwise, let's try to create a connection
    (do
      (debug "Creating new connection")
      (if (increment-count-for pool addr)
        (connect-client
         pool addr
         (fn [conn-or-err]
           ;; Creating the connection failed, so decrement the count
           (when (instance? Exception conn-or-err)
             (debug "Failed attempt to create new connection")
             (decrement-count-for state addr))
           (return-conn pool conn-or-err handler callback true)))
        (callback (Exception. "Reached maximum connections") true)))))

(defn checkin-conn
  "Returns a connection to the pool"
  [[state ^ChannelPool pool] ^Channel conn]

  ;; is this right?
  (if (nil? conn)
    (throw (Exception.
            (str "Attempted to check in nil channel to connection pool: " pool))))

  (if (.isOpen conn)
    (do
      (debug "Checking connection back into pool")
      (.. conn getPipeline removeLast)

      ;; is this right?
      (if (nil? conn)
        (throw (Exception.
                (str "Attempted to check in nil channel to connection pool: " pool))))

      (.checkin pool conn))
    (do
      (debug "Discarding closed connection")
      (decrement-count-for state (.getRemoteAddress conn)))))

(defn close-conn
  "Closes a connection that cannot be reused. The connection is
   not returned to the pool."
  [[state] ^Channel conn]
  (debug "Connection closed while in client")
  (decrement-count-for state (.getRemoteAddress conn))
  (.close conn))

(def default-options
  {:keepalive                   60
   :max-connections             1000
   :max-connections-per-address 200    ;; Not implemented yet
   :max-queued-connections      5000}) ;; Not implemented yet

(defn mk-pool
  ([]
     (mk-pool {}))
  ([options]
     (let [options (merge default-options options)
           state   (ref [0 {}])]
       [state
        ;; The channel pool with a callback that tracks open
        ;; connections
        (ChannelPool.
         (options :keepalive)
         netty/global-timer
         (reify ChannelPoolCallback
           (channelClosed [_ addr]
             (debug "Connection closed while in pool")
             (decrement-count-for state addr))))
        (netty/mk-client-factory
         create-pipeline options)
        options])))

(defn shutdown
  [[state channel-pool opts]]
  (.shutdown channel-pool))
