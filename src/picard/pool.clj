(ns picard.pool
  (:require
   [picard.netty :as netty])
  (:import
   [org.jboss.netty.channel
    Channel
    ChannelPipeline]
   [org.jboss.netty.handler.codec.http
    HttpRequestEncoder
    HttpResponseDecoder]))

(def QUEUE clojure.lang.PersistentQueue/EMPTY)

(defn- create-pipeline
  []
  (netty/create-pipeline
   :decoder (HttpResponseDecoder.)
   :encoder (HttpRequestEncoder.)))

(defn- mk-conn
  [host success error]
  (netty/connect-client (create-pipeline) host success))

(defn- return-conn
  [conn handler success]
  (.. conn getPipeline (addLast "handler" handler))
  (success conn))

(defn checkout-conn
  "Calls success fn with the channel"
  ([pool host handler success] (checkout-conn pool host handler success nil))
  ([pool host handler success error]
     (if-let [conn (dosync
                    (let [{[conn :as conns] host :as m} @pool]
                      (when conn
                        (ref-set
                         pool
                         (if (empty? conns)
                           (dissoc m host)
                           (assoc m host (pop conns))))
                        conn)))]
       (return-conn conn handler success)
       (mk-conn host #(return-conn % handler success) error))))

(defn checkin-conn
  "Returns a connection to the pool"
  [pool host ^Channel conn]
  (when (.isOpen conn)
    (.. conn getPipeline removeLast)
    (dosync
     (alter
      pool
      (fn [{conns host :as m}]
        (assoc m
          host (conj (or conns QUEUE) conn)))))))

(defn mk-pool
 []
 (ref {}))
