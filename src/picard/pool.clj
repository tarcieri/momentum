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
  [[host port] success error]
  (netty/connect-client (create-pipeline) host port success))

(defn- return-conn
  [conn handler success]
  (.. conn getPipeline (addLast "handler" handler))
  (success conn))

(defn checkout-conn
  "Calls success fn with the channel"
  ([pool addr handler success] (checkout-conn pool addr handler success nil))
  ([pool addr handler success error]
     (if-let [conn (dosync
                    (let [{[conn :as conns] addr :as m} @pool]
                      (when conn
                        (ref-set
                         pool
                         (if (empty? conns)
                           (dissoc m addr)
                           (assoc m addr (pop conns))))
                        conn)))]
       (return-conn conn handler success)
       (mk-conn addr #(return-conn % handler success) error))))

(defn checkin-conn
  "Returns a connection to the pool"
  [pool addr ^Channel conn]
  (when (.isOpen conn)
    (.. conn getPipeline removeLast)
    (dosync
     (alter
      pool
      (fn [{conns addr :as m}]
        (assoc m
          addr (conj (or conns QUEUE) conn)))))))

(defn mk-pool
 []
 (ref {}))
