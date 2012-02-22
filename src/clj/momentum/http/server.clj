(ns momentum.http.server
  (:use
   momentum.core
   momentum.core.atomic)
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

(def cas! compare-and-set!)

(def http-version-bytes
  {[1 0] (buffer "HTTP/1.0")
   [1 1] (buffer "HTTP/1.1")})

(def SP          (buffer " "))
(def QM          (buffer "?"))
(def CRLF        (buffer "\r\n"))
(def http-1-0    [1 0])
(def http-1-1    [1 1])
(def last-chunk  (buffer "0\r\n\r\n"))
(def empty-queue clojure.lang.PersistentQueue/EMPTY)
(def response-status-reasons
  {100 "100 Continue"
   101 "101 Switching Protocols"
   102 "102 Processing"
   200 "200 OK"
   201 "201 Created"
   202 "202 Accepted"
   203 "203 Non-Authoritative Information"
   204 "204 No Content"
   205 "205 Reset Content"
   206 "206 Partial Content"
   207 "207 Multi-Status"
   226 "226 IM Used"
   300 "300 Multiple Choices"
   301 "301 Moved Permanently"
   302 "302 Found"
   303 "303 See Other"
   304 "304 Not Modified"
   305 "305 Use Proxy"
   306 "306 Reserved"
   307 "307 Temporary Redirect"
   400 "400 Bad Request"
   401 "401 Unauthorized"
   402 "402 Payment Required"
   403 "403 Forbidden"
   404 "404 Not Found"
   405 "405 Method Not Allowed"
   406 "406 Not Acceptable"
   407 "407 Proxy Authentication Required"
   408 "408 Request Timeout"
   409 "409 Conflict"
   410 "410 Gone"
   411 "411 Length Required"
   412 "412 Precondition Failed"
   413 "413 Request Entity Too Large"
   414 "414 Request-URI Too Long"
   415 "415 Unsupported Media Type"
   416 "416 Request Range Not Satisfiable"
   417 "417 Expectation Failed"
   422 "422 Unprocessable Entity"
   423 "423 Locked"
   424 "424 Failed Dependency"
   426 "426 Upgrade Required"
   500 "500 Internal Server Error"
   501 "501 Not Implemented"
   502 "502 Bad Gateway"
   503 "503 Service Unavailable"
   504 "504 Gateway Timeout"
   505 "505 HTTP Version Not Supported"
   506 "506 Variant Also Negotiates"
   507 "507 Insufficient Storage"
   510 "510 Not Extended"})

(defn- hex
  [i]
  (Integer/toHexString i))

(defn- status-to-reason
  [s]
  (or (response-status-reasons s)
      (throw (Exception. (str "Invalid HTTP status: " s)))))

(defn- http-version-to-bytes
  [v]
  (or (http-version-bytes (or v http-1-1))
      (throw (Exception. (str "Invalid HTTP version: " v)))))

(defn- write-message-header
  [buf name val]
  (when-not (or (nil? val) (= "" val))
    (write buf (str name) ": " (to-string val) CRLF)))

(defn- write-message-headers
  [buf hdrs]
  (doseq [[name v-or-vals] hdrs]
    (when (string? name)
      (if (sequential? v-or-vals)
        (doseq [val v-or-vals]
          (write-message-header buf name val))
        (write-message-header buf name v-or-vals))))

  ;; Send the final CRLF
  (write buf CRLF))

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

    (handle-response-head  [_ [status {version :http-version :as hdrs} body] final?]
      (let [buf (dynamic-buffer)
            ver (http-version-to-bytes version)
            rsn (status-to-reason status)]
        (write buf ver SP rsn CRLF)
        (write-message-headers buf hdrs)
        (dn :message (flip buf)))

      (when (and body (not (keyword? body)))
        (dn :message body))

      (when final?
        (dn :close nil)))

    (handle-response-chunk [_ chunk encoded? final?]
      (cond
       (and chunk encoded?)
       (let [size (hex (remaining chunk))]
         (dn :message (wrap (buffer size CRLF) chunk CRLF)))

       encoded?
       (dn :message last-chunk)

       chunk
       (dn :message chunk))

      (when final?
        (dn :close nil)))

    (handle-response-message [_ msg]
      (dn :message msg))

    (handle-exchange-timeout [_]
      (dn :abort (Exception. "The exchange took too long")))

    (handle-keep-alive-timeout [_]
      (dn :close nil))))

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
    [upstream
     gate])

(defn- init-pipeliner
  [app dn env gate opts]
  (Pipeliner.
   app dn env gate opts
   (atom
    (PipelinerState. nil empty-queue nil))))

(defn- max-pipeline-depth
  [pipeliner]
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
  [pipeliner]
  (swap!
   (.state pipeliner)
   (fn [conn]
     (assoc conn :handling empty-queue :last-handler nil))))

(defn- throttle-pipelined-conn
  [pipeliner conn]
  (if (= (count (.handling conn)) (max-pipeline-depth pipeliner))
    (gate/pause!  (.gate pipeliner))
    (gate/resume! (.gate pipeliner))))

(defn- mk-pipelined-dn
  [pipeliner]
  (let [dn (.dn pipeliner)]
    (fn [evt val]
      ;; First send the event downstream
      (dn evt val)
      (when (final-response-event? evt val)
        (get-swap-then!
         (.state pipeliner)
         (fn [conn]
           (assoc conn :handling (pop (.handling conn))))
         (fn [old-conn new-conn]
           ;; If there is a pending response, release it
           (when-let [exchange (first (.handling new-conn))]
             (gate/resume! (.gate exchange)))

           ;; If the exchange is done, release the requests
           (when (nil? (.last-handler old-conn))
             (throttle-pipelined-conn pipeliner new-conn))))))))

(defn- bind-pipeliner-upstream
  [pipeliner]
  (let [app      (.app pipeliner)
        gate     (gate/init (mk-pipelined-dn pipeliner))
        upstream (app gate (.env pipeliner))]
    (PipelinedExchange. upstream gate)))

(defn- handle-pipelined-request
  [pipeliner [hdrs body :as request]]
  (let [exchange (bind-pipeliner-upstream pipeliner)]

    ;; Track the upstream handler
    (get-swap-then!
     (.state pipeliner)
     (fn [conn]
       (assoc conn
         :handling (conj (.handling conn) exchange)
         ;; Only set the last-handler when there will be further
         ;; events for the request.
         :last-handler (when (keyword? body) exchange)))

     (fn [old-conn conn]
       (when (< 1 (count (.handling conn)))
         (gate/pause! (.gate exchange)))

       (when-not (keyword? body)
         (throttle-pipelined-conn pipeliner conn))

       (let [upstream (.upstream exchange)]
         (upstream :request [(merge (.addrs conn) hdrs) body]))))))

(defn- forward-pipelined-event
  [exchange evt val]
  (if exchange
    (let [upstream (.upstream exchange)]
      (upstream evt val))
    (throw (Exception. (str "No upstream : " evt val)))))

(defn- handle-pipelined-request-body
  [pipeliner chunk]
  (loop [conn @(.state pipeliner)]
    (if (nil? chunk)
      (let [new-conn (assoc conn :last-handler nil)]
        (if (compare-and-set! (.state pipeliner) conn new-conn)
          (do (throttle-pipelined-conn pipeliner new-conn)
              (forward-pipelined-event (.last-handler conn) :body nil))
          (recur @(.state pipeliner))))
      (forward-pipelined-event (.last-handler conn) :body chunk))))

(defn- handle-pipelined-close
  [pipeliner]
  ;; Proxy the close to all pending handlers
  (let [handling (.handling @(.state pipeliner))]
    (finalize-pipeliner pipeliner)
    (doseq [exchange handling]
      (let [upstream (.upstream exchange)]
        (try (upstream :close nil)
             (catch Exception _))))))

(defn- handle-pipelined-abort
  [pipeliner err]
  (let [handling (.handling @(.state pipeliner))]
    (finalize-pipeliner pipeliner)
    (doseq [exchange handling]
      (let [upstream (.upstream exchange)]
        (try (upstream :abort err)
             (catch Exception _))))))

(defn- handle-pipelined-event
  [pipeliner evt val]
  (let [conn @(.state pipeliner)]
    (forward-pipelined-event (.last-handler conn) evt val)))

(defn- set-pipeliner-addrs!
  [pipeliner addrs]
  (swap! (.state pipeliner) #(assoc % :addrs addrs)))

(defn pipeliner
  "Handles HTTP pipelining"
  [app opts]
  (fn [dn env]
    (let [gate (gate/init)
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
  "Middleware that implements the HTTP protocol."
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

               :else
               (do
                 (when (#{:close :abort} evt)
                   (proto/cleanup state))
                 (up evt val))))))))))

(defn start
  ([app] (start app {}))
  ([app opts]
     (net/start (proto app opts) opts)))

(defn stop
  [server]
  (net/stop server))
