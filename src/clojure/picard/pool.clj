(ns picard.pool
  (:use
   [picard.utils :rename {debug debug*}])
  (:require
   [clojure.contrib.string :as str]
   [picard.netty :as netty])
  (:import
   picard.ChannelPool
   [org.jboss.netty.channel
    Channel
    ChannelPipeline]
   [org.jboss.netty.handler.codec.http
    HttpRequestEncoder
    HttpResponseDecoder]
   java.net.InetSocketAddress))

;; TODO:
;; * Connected / disconnected event doesn't seem to fire
;; * Invalid hosts still seems to generate closed +
;;   java.net.ConnectException

(defmacro debug
  [& msgs]
  `(debug* :pool ~@msgs))

(defn- normalize-addr
  [[host port]]
  [host (or port 80)])

(defn- to-addr
  [^InetSocketAddress addr]
  [(.getHostName addr) (.getPort addr)])

(defn- increment-count-for
  [[state _ _ options] addr]
  (swap!
   state
   (fn [[total by-addrs]]
     (when (>= total (options :max-connections))
       (debug {:msg   "Maximum global connections reached."
               :state {"total-connections" total
                       "hosts" (str/join ", " (take 100 (keys by-addrs)))
                       (str "connections for " addr) (by-addrs addr)}})
       (throw (Exception. "Reached maximum global connections for pool")))

     (when (>= (by-addrs addr 0) (options :max-connections-per-address))
       (debug {:msg   "Maximum connections for " addr " reached."
               :state {"total-connections" total
                       "hosts" (str/join ", " (take 100 (keys by-addrs)))
                       (str "connections for " addr) (by-addrs addr)}})
       (throw (Exception. "Reached maximum connections for " addr)))

     [(inc total) (assoc by-addrs addr (inc (by-addrs addr 0)))])))

(defn- decrement-count-for
  [state addr]
  (swap!
   state
   (fn [[total by-addrs]]
     [(dec total)
      (if (> (by-addrs addr 0) 1)
        (assoc by-addrs addr (dec (by-addrs addr)))
        (dissoc by-addrs addr))])))

(defn- connection-count-handler
  [state]
  (netty/upstream-stage
   (fn [^Channel ch evt]
     (when (netty/channel-close-event? evt)
       (let [addr (to-addr (.getRemoteAddress ch))]
         (decrement-count-for state addr)
         (debug
          (let [[total by-addrs] @state]
            {:msg   "Closing connection"
             :event [ch addr]
             :state {"total-connections" total
                     "hosts" (str/join ", " (take 100 (keys by-addrs)))
                     (str "connections for " addr) (by-addrs addr)}})))))))

(defn- create-pipeline
  [pool]
  (netty/create-pipeline
   :track-closes (connection-count-handler pool)
   :decoder      (HttpResponseDecoder.)
   :encoder      (HttpRequestEncoder.)))

(defn- add-handler
  [conn handler]
  (.. conn getPipeline (addLast "handler" handler)))

(defn checkout-conn
  "Calls success fn with the channel"
  [[state conn-pool factory opts :as pool] addr handler callback]
  (let [addr (normalize-addr addr)]
    ;; First, attempt to grab a hot connection out of the connection
    ;; pool for the requested socket address.
    (if-let [conn (.checkout conn-pool (netty/mk-socket-addr addr))]
      ;; There is a fresh connection available, add the netty handler to
      ;; the end of the channel pipeline and invoke the callback with
      ;; the connection and `false` in order to indicate that the
      ;; connection is not fresh (came from a connection pool). That
      ;; way, if the connection is bogus somehow, the client knows that
      ;; it is able to attempt to get a different connection
      (do
        (debug {:msg "Checking out connection from pool"
                :event conn})
        (add-handler conn handler)
        (callback conn false))

      ;; Otherwise, we'll need to establish a new connection (assuming
      ;; that we haven't reached the maximum allotted connections
      ;; already). The connection counters are incremented before the
      ;; connection is established. This prevent any race conditions
      ;; where a bazillion connections are established at the same
      ;; time. If establishing the connection fails, the counters are
      ;; decremented at that point.
      (try
        (let [[total by-addrs] (increment-count-for pool addr)]
          (debug {:msg   "Establishing new connection"
                  :state {"total-connections" total
                          "hosts" (str/join ", " (take 100 (keys by-addrs)))
                          (str "connections for " addr) (by-addrs addr)}}))
        (netty/connect-client
         ;; Alright, this is totally not valid since this can only be
         ;; called once per pool. Somehow, we need to bind the channel
         ;; pipeline to the state & addr
         factory addr (opts :local-addr)
         (fn [conn-or-err]
           (if (instance? Exception conn-or-err)
             (do (debug {:msg "Failed to establish connection"
                         :event conn-or-err})
                 (decrement-count-for state addr))
             (do (debug {:msg "Successfully established connection" :event conn-or-err})
                 (add-handler conn-or-err handler)))
           (callback conn-or-err true)))
        ;; Incrementing the count might raise an exception, if it does,
        ;; return it to the client by passing it through the callback.
        (catch Exception err
          (callback err true))))))

(defn checkin-conn
  "Returns a connection to the pool"
  [[state ^ChannelPool pool] ^Channel conn]
  ;; Hope that this never happens
  (when (nil? conn)
    (throw (Exception. (str "Attempted to check in nil channel "
                            "to connection pool: " pool))))

  (when (.isOpen conn)
    (debug
     (let [[total by-addrs] @state
           addr (to-addr (.getRemoteAddress conn))]
       {:msg   "Returning connection to pool"
        :event conn
        :state {"total-connections" total
                "hosts" (str/join ", " (take 100 (keys by-addrs)))
                (str "connections for " addr) (by-addrs addr)}}))
    (.. conn getPipeline removeLast)
    (.checkin pool conn)))

(defn close-conn
  "Closes a connection that cannot be reused. The connection is
   not returned to the pool."
  [[state] ^Channel conn]
  (debug {:msg "Closing connection" :event conn})
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
           state   (atom [0 {}])]
       [state
        (ChannelPool. (options :keepalive) netty/global-timer)
        (netty/mk-client-factory #(create-pipeline state) options)
        options])))

(defn shutdown
  [[state channel-pool opts]]
  (debug "Shutting down channel pool")
  (.shutdown channel-pool))
