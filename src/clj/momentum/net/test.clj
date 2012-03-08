(ns momentum.net.test
  (:use
   momentum.core
   momentum.util.atomic)
  (:require
   [momentum.net.core :as net]))

(def default-addrs
  {:local-addr  ["127.0.0.1" 12345]
   :remote-addr ["127.0.0.1" 12346]})

(def ms 1000)

(def ^:dynamic *endpoint*    nil)
(def ^:dynamic *client*      nil)
(def ^:dynamic *connections* nil)

(deftype Connection [received downstream]
  clojure.lang.Seqable
  (seq [this] (.received this))

  clojure.lang.IFn
  (invoke [this evt val]
    (put (.downstream this) [evt val])
    this))

(defn- close-connections
  [conns]
  (doseq [conn conns]
    (conn :close nil)))

(defn with-endpoint*
  [client endpoint f]
  (binding [*endpoint*    endpoint
            *client*      client
            *connections* (atom (list))]
    (try
      (f)
      (finally
       (close-connections @*connections*)))))

(defmacro with-endpoint
  [& args]
  (if (= :client (first args))
    (let [[_ client endpoint & stmts] args]
      `(with-endpoint* ~client ~endpoint (fn [] ~@stmts)))
    (let [[endpoint & stmts] args]
      `(with-endpoint* identity ~endpoint (fn [] ~@stmts)))))

(defn- mk-downstream
  [open? pause-resume read-ch write-ch]
  (fn [evt val]
    (when @open?
      (cond
       (#{:close :abort} evt)
       (do
         (put read-ch [evt val])
         (put write-ch [:close nil]))

       (= :message evt)
       (put write-ch [evt val])

       (#{:pause :resume} evt)
       (put pause-resume evt)

       :else
       (println "Unknown event: " evt)))))

(defn- open-socket
  [endpoint read-ch write-ch]
  (future
    (let [open?        (atom true)
          pause-resume (channel)
          downstream   (mk-downstream open? pause-resume read-ch write-ch)
          upstream     (endpoint downstream {:test true})
          messages     (splice [:pr (seq pause-resume)] [:read (seq read-ch)])]

      (try
        (loop [messages (blocking messages ms :timeout)]
          (let [[source msg] (first messages)]
            (if (= :pr source)
              (if (= :pause msg)
                (recur (next (dissoc messages :read)))
                (recur (next (assoc messages :read (seq read-ch)))))
              (let [[evt val] msg]
                (when @open?
                  (upstream evt val)
                  (when-not (#{:close :abort} evt)
                    (recur (next messages))))))))
        (catch Throwable _
          (put write-ch [:close nil])))

      ;; Clean up
      (reset! open? false)
      (close write-ch))))

(defn- build-client
  [client client-in client-out]
  (client
   (fn [dn _]
     (doseq* [[evt val] (seq client-in)]
       (dn evt val))
     (fn [evt val]
       (put client-out [evt val])))))

(defn- bind-client
  [client server-in server-out]
  (let [upstream
        (client
         (fn [evt val]
           (put server-in [evt val]))
         {:test true})]
    ;; Handle events received from the server
    (doseq* [[evt val] (seq server-out)]
      (upstream evt val))))

(defn- open-socket-pair
  [addrs server]
  (let [server-in  (channel)
        server-out (channel)
        client-in  (channel)
        client-out (channel)]

    ;; Start the server
    (open-socket server server-in server-out)

    ;; Bind client
    (bind-client
     (build-client *client* client-in client-out)
     server-in server-out)

    ;; Simulate opening connections
    (put server-in  [:open addrs])
    (put server-out [:open addrs])

    ;; Return connection instance
    (Connection. (blocking (seq client-out) ms :timeout) client-in)))

;; TODO: Allow a configurable timeout for the received channel
(defn open
  ([] (open {}))
  ([addrs]
     (when-not *endpoint*
       (throw (Exception. "No app setup, use (with-endpoint ...)")))

     (let [conn (open-socket-pair (merge default-addrs addrs) *endpoint*)]
       (swap! *connections* #(conj % conn))
       conn)))

(defn last-connection
  ([] (first @*connections*))
  ([evt val]
     (let [conn (last-connection)]
       (conn evt val))))

(defn received
  ([]     (seq (last-connection)))
  ([conn] (seq conn)))

(defn closed?
  ([]     (closed? (last-connection)))
  ([conn] (first (filter #(= % :close) (map first conn)))))