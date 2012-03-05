(ns momentum.http.server
  (:use
   momentum.core
   momentum.core.atomic
   momentum.http.core)
  (:require
   [momentum.net.server  :as net]
   [momentum.http.parser :as parser]
   [momentum.http.proto  :as proto]
   [momentum.util.gate   :as gate]))

;; TODO:
;; - Pipelined requests receive pause / resume events.

(def default-opts
  {
   ;; Support up to 20 simultaneous pipelined exchanges. Once the
   ;; number is reached, the server will send a pause event downstream
   ;; which will tell the server to stop reading requests off of the
   ;; socket. Once the exchanges fall below the threshold, a
   ;; resume event will be fired.
   :pipeline  20
   ;; Keep the connection open for 60 seconds between exchanges.
   :keepalive 60
   ;; The max number of seconds between events of a given exchange. If
   ;; this timeout is reached, the connection will be forcibly closed.
   :timeout 5})

(def cas!        compare-and-set!)
(def empty-queue clojure.lang.PersistentQueue/EMPTY)

(defn- mk-handler
  [dn up opts]
  (reify proto/HttpHandler
    (handle-request-head [_ request _]
      (up :request request))

    (handle-request-chunk [_ chunk _ final?]
      (up :body chunk)
      (when final?
        (dn :close nil)))

    (handle-request-message [_ msg]
      (up :message msg))

    (handle-response-head  [_ [status hdrs body] final?]
      (dn :message (encode-response-head status hdrs))

      (when (and body (not (keyword? body)))
        (dn :message body))

      (when final?
        (dn :close nil)))

    (handle-response-chunk [_ chunk encoded? final?]
      (cond
       (and chunk encoded?)
       (dn :message (encode-chunk chunk))

       encoded?
       (dn :message (duplicate last-chunk))

       chunk
       (dn :message chunk))

      (when final?
        (dn :close nil)))

    (handle-response-message [_ msg]
      (dn :message msg))

    (handle-exchange-timeout [_]
      (dn :abort (Exception. "The HTTP exchange timed out")))

    (handle-keep-alive-timeout [_]
      (dn :close nil))

    (handle-abort [_ err]
      (up :abort err))))

(defn- mk-downstream
  [state dn]
  (fn [evt val]
    (cond
     (= :response evt)
     (proto/response state val)

     (= :body evt)
     (proto/response-chunk state val)

     (= :message evt)
     (proto/response-message state val)

     ;; If a :close event is sent downstream, then the connection is
     ;; being explicitly closed (sometimes mid exchange). We don't
     ;; want to send an exception upstream once the upstream :close
     ;; event is received.
     (= :close evt)
     (do (proto/force-connection-closed state)
         (dn evt val))

     :else
     (dn evt val))))

(defn request-parser
  "Wraps an upstream function with the basic HTTP parser."
  [f]
  (let [p (parser/request f)]
    (fn [evt val]
      (if (= :message evt)
        (p val)
        (f evt val)))))

;;
;; ==== HTTP Pipelining ====
;;

(defrecord Pipeliner
    [app
     dn
     env
     gate
     opts
     state])

(defrecord PipelinerState
    [addrs
     handling
     last-handler])

(defrecord PipelinedExchange
    [upstream gate])

(defn- reduce-pipeline-buffer
  [coll evt val]
  (cond
   (= :request evt)
   (let [[hdrs body] val]
     (conj coll [evt [hdrs (retain body)]]))

   (= :response evt)
   (let [[status hdrs body] val]
     (conj coll [evt [status hdrs (retain body)]]))

   (= :body evt)
   (conj coll [evt (retain val)])

   :else
   (conj coll [evt val])))

(defn- init-pipeliner
  [app dn env gate opts]
  (Pipeliner.
   app dn env gate opts
   (atom
    (PipelinerState. nil empty-queue nil))))

(defn- max-pipeline-depth
  [^Pipeliner pipeliner]
  (get (.opts pipeliner) :pipeline))

(defn- final-response-event?
  [evt val]
  (cond
   (= :response evt)
   (let [[_ _ body] val]
     (not (keyword? body)))

   (= :body evt)
   (nil? val)))

(defn- finalize-pipeliner
  [^Pipeliner pipeliner]
  (swap!
   (.state pipeliner)
   (fn [conn]
     (assoc conn :handling empty-queue :last-handler nil))))

(defn- throttle-pipelined-conn
  [^Pipeliner pipeliner ^PipelinerState conn]
  (if (= (count (.handling conn)) (max-pipeline-depth pipeliner))
    (gate/pause!  (.gate pipeliner))
    (gate/resume! (.gate pipeliner))))

(defn- mk-pipelined-dn
  [^Pipeliner pipeliner]
  (let [dn (.dn pipeliner)]
    (fn [evt val]
      ;; First send the event downstream
      (dn evt val)
      (when (final-response-event? evt val)
        (get-swap-then!
         (.state pipeliner)
         (fn [^PipelinerState conn]
           (assoc conn :handling (pop (.handling conn))))
         (fn [^PipelinerState old-conn ^PipelinerState new-conn]
           ;; If there is a pending response, release it
           (when-let [^PipelinedExchange exchange (first (.handling new-conn))]
             (gate/resume! (.gate exchange)))

           ;; If the exchange is done, release the requests
           (when (nil? (.last-handler old-conn))
             (throttle-pipelined-conn pipeliner new-conn))))))))

(defn- bind-pipeliner-upstream
  [^Pipeliner pipeliner]
  (let [app      (.app pipeliner)
        gate     (gate/init reduce-pipeline-buffer (mk-pipelined-dn pipeliner))
        upstream (app gate (.env pipeliner))]
    (PipelinedExchange. upstream gate)))

(defn- handle-pipelined-request
  [^Pipeliner pipeliner [hdrs body :as request]]
  (let [exchange ^PipelinedExchange (bind-pipeliner-upstream pipeliner)]

    ;; Track the upstream handler
    (get-swap-then!
     (.state pipeliner)
     (fn [^PipelinerState conn]
       (assoc conn
         :handling (conj (.handling conn) exchange)
         ;; Only set the last-handler when there will be further
         ;; events for the request.
         :last-handler (when (keyword? body) exchange)))

     (fn [^PipelinerState old-conn ^PipelinerState conn]
       (when (< 1 (count (.handling conn)))
         (gate/pause! (.gate exchange)))

       (when-not (keyword? body)
         (throttle-pipelined-conn pipeliner conn))

       (let [upstream (.upstream exchange)]
         (upstream :request [(merge (.addrs conn) hdrs) body]))))))

(defn- forward-pipelined-event
  [^PipelinedExchange exchange evt val]
  (if exchange
    (let [upstream (.upstream exchange)]
      (upstream evt val))
    (throw (Exception. (str "No upstream : " evt val)))))

(defn- handle-pipelined-request-body
  [^Pipeliner pipeliner chunk]
  (loop [^PipelinerState conn @(.state pipeliner)]
    (if (nil? chunk)
      (let [new-conn (assoc conn :last-handler nil)]
        (if (compare-and-set! (.state pipeliner) conn new-conn)
          (do (throttle-pipelined-conn pipeliner new-conn)
              (forward-pipelined-event (.last-handler conn) :body nil))
          (recur @(.state pipeliner))))
      (forward-pipelined-event (.last-handler conn) :body chunk))))

(defn- handle-pipelined-close
  [^Pipeliner pipeliner]
  ;; Proxy the close to all pending handlers
  (let [handling (.handling ^PipelinerState @(.state pipeliner))]
    (finalize-pipeliner pipeliner)
    (doseq [^PipelinedExchange exchange handling]
      (let [upstream (.upstream exchange)]
        (try (upstream :close nil)
             (catch Exception _))))))

(defn- handle-pipelined-abort
  [^Pipeliner pipeliner err]
  (let [handling (.handling ^PipelinerState @(.state pipeliner))]
    (finalize-pipeliner pipeliner)
    (doseq [^PipelinedExchange exchange handling]
      (let [upstream (.upstream exchange)]
        (try (upstream :abort err)
             (catch Exception _))))))

(defn- handle-pipelined-event
  [^Pipeliner pipeliner evt val]
  (let [^PipelinerState conn @(.state pipeliner)]
    (forward-pipelined-event (.last-handler conn) evt val)))

(defn- set-pipeliner-addrs!
  [^Pipeliner pipeliner addrs]
  (swap! (.state pipeliner) #(assoc % :addrs addrs)))

(defn pipeliner
  "Handles HTTP pipelining"
  [app opts]
  (fn [dn env]
    (let [gate (gate/init reduce-pipeline-buffer)
          pipeliner (init-pipeliner app dn env gate opts)]

      (gate/set-upstream!
       gate
       (fn [evt val]
         (cond
          (= :open evt)
          (set-pipeliner-addrs! pipeliner val)

          (= :request evt)
          (handle-pipelined-request pipeliner val)

          (= :body evt)
          (handle-pipelined-request-body pipeliner val)

          (= :close evt)
          (handle-pipelined-close pipeliner)

          (= :abort evt)
          (handle-pipelined-abort pipeliner val)

          :else
          (handle-pipelined-event pipeliner evt val)))))))

;; ==== Basic HTTP protocol

(defn proto
  "Middleware that implements the HTTP server protocol."
  ([app] (proto app {}))
  ([app opts]
     (let [opts (merge default-opts opts)
           app  (if (opts :pipeline) (pipeliner app opts) app)]
       (fn [dn env]
         (let [state (proto/init opts)
               up (app (mk-downstream state dn) env)]

           (proto/set-handler state (mk-handler dn up opts))

           (request-parser
            (fn [evt val]
              (cond
               (= :request evt)
               (proto/request state val)

               (= :body evt)
               (proto/request-chunk state val)

               (= :message evt)
               (proto/request-message state val)

               (= :close evt)
               (when (proto/connection-closed state)
                 (up evt val))

               (= :abort evt)
               (do (proto/cleanup state)
                   (up evt val))

               :else
               (up evt val)))))))))

(defn start
  ([app] (start app {}))
  ([app opts]
     (net/start (proto app opts) opts)))

(defn stop
  [server]
  (net/stop server))