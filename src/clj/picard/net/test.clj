(ns picard.net.test
  (:require
   [picard.net.core :as net])
  (:import
   [java.util.concurrent
    LinkedBlockingQueue
    TimeUnit]))

(declare
 *app*
 *connections*
 received)

(def default-addrs
  {:remote-addr ["127.0.0.1" 1234]
   :local-addr  ["127.0.0.1" 4321]})

(deftype Connection
    [queue
     upstream
     cached]

  clojure.lang.Seqable
  (seq [this]
    (concat
     @(.cached this)
     (received this)))

  clojure.lang.IFn
  (invoke [this evt val]
    ((.upstream this) evt val)))

(defmacro with-app
  [app & stmts]
  `(binding [*app* (net/handler ~app)
             *connections* (atom (sequence []))]
     ~@stmts))

(defn- mk-downstream
  [queue]
  (fn [evt val]
    (.put queue [evt val])))

(defn open
  ([] (open {}))
  ([addrs]
     (when-not *app*
       (throw (Exception. "No app set, use (with-app ...)")))

     (let [queue    (LinkedBlockingQueue.)
           upstream (*app* (mk-downstream queue))
           cache    (atom clojure.lang.PersistentQueue/EMPTY)
           conn     (Connection. queue upstream cache)]
       (upstream :open (merge default-addrs addrs))
       (swap! *connections* #(conj % conn))
       conn)))

(defn last-connection
  []
  (first @*connections*))

(defn received
  ([] (received (last-connection)))
  ([conn]
     (lazy-seq
      (when-let [e (.poll (.queue conn) 1000 TimeUnit/MILLISECONDS)]
        (swap! (.cached conn) #(conj % e))
        (cons e (received conn))))))
