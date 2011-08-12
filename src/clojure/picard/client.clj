(ns picard.client
  (:use
   [picard.utils :rename {debug debug*}]
   [picard.helpers])
  (:require
   [clojure.string :as str]
   [picard.netty   :as netty]
   [picard.pool    :as pool])
  (:import
   [org.jboss.netty.channel
    Channel
    ChannelEvent
    ChannelFuture
    ChannelState]
   [org.jboss.netty.handler.codec.http
    DefaultHttpRequest
    HttpChunk
    HttpHeaders
    HttpMethod
    HttpRequestEncoder
    HttpResponse
    HttpResponseDecoder
    HttpResponseStatus
    HttpVersion]
   [org.jboss.netty.handler.timeout
    TimeoutException]
   [java.io
    IOException]
   [java.net
    URI]))

(defrecord State
    [pool
     ch
     keepalive?
     chunked?
     head?
     chunk-trailer?
     expects-100?
     next-up-fn
     next-dn-fn
     downstream
     upstream
     last-write
     aborted?
     timeout
     options])

(declare handle-err)

(defmacro debug
  [& msgs]
  `(debug* :client ~@msgs))

(defn- keepalive?
  [[hdrs]]
  (not= "close" (hdrs "connection")))

(defn- is-100-continue?
  [^HttpResponse resp]
  (= HttpResponseStatus/CONTINUE
     (.getStatus resp)))

(defn- clear-timeout*
  [^State current-state]
  (when-let [old-timeout (.timeout current-state)]
    (netty/cancel-timeout old-timeout)))

(defn- clear-timeout
  [state]
  (locking state
    (clear-timeout* @state)))

(defn- bump-timeout
  [state]
  (locking state
    (let [current-state ^State @state]
      (clear-timeout* current-state)
      (let [new-timeout
            (netty/on-timeout
             netty/global-timer
             (* ((.options current-state) :timeout) 1000)
             #(handle-err
               state
               (TimeoutException.
                (str "Client timed out: " (System/identityHashCode (.timeout @state))))
               @state))]
        (debug {:msg   (str "Setting timer: " (System/identityHashCode new-timeout))
                :state current-state})
        (swap! state #(assoc % :timeout new-timeout))))))

(defn- request-complete
  [_ evt val _]
  (when-not (= :abort evt)
    (throw (Exception. (str "The request is complete."
                            "  Event: " evt "\n"
                            "  Value: " val)))))

(defn- finalize-request
  [state ^State current-state]
  (when (= request-complete (.next-dn-fn current-state))
    ;; Send an upstream message indicating that the exchange is complete
    (when-not (.aborted? current-state)
      (let [upstream (.upstream current-state)]
        (debug {:msg "Sending upstream" :event [:done nil] :state current-state})
        (upstream :done nil)))

    ;; Don't close the connection or reuse it until all pending writes
    ;; have been fully flushed to the socket.
    (netty/on-complete
     (.last-write current-state)
     (fn [future]
       (let [current-state ^State @state]
         (debug {:msg   "Last write finished, cleaning up exchange"
                 :event future
                 :state current-state})

         ;; Clear the timeout since there will be no other user code called
         (clear-timeout state)

         ;; The connection will either be closed or it will be moved
         ;; into the connection pool which has it's own keepalive
         ;; timeout, so clear any existing timeout for this connection.
         (if (and (.keepalive? current-state)
                  (not (.aborted? current-state)))
           (pool/checkin-conn (.pool current-state) (.ch current-state))
           (when-let [ch (.ch current-state)]
             (pool/close-conn (.pool current-state) ch))))))))

(defn- connection-pending
  [_ evt val _]
  (throw (Exception. (str "The connection has not yet been established.\n"
                          "  Event: " evt "\n"
                          "  Value: " val))))

(defn- write-pending
  [_ evt val _]
  (if (= :body evt)
    (throw (Exception. (str "The first write has not yet succeded.\n"
                            "  Event: " evt "\n"
                            "  Value: " val)))))

(defn- response-pending
  [_ _ _ _])

(defn- stream-or-finalize-request
  [state evt val ^State current-state]
  (when-not (= :body evt)
    (throw (Exception. (str "Not expecting event.\n"
                            "  Event: " evt "\n"
                            "  Value: " val))))

  (let [ch ^Channel (.ch current-state)]
    (if (nil? val)
      (let [last-write
            (when (.chunk-trailer? current-state)
              (.write ch HttpChunk/LAST_CHUNK))]
        (swap-then!
         state
         (fn [^State current-state]
           (cond
            (nil? (.next-up-fn current-state))
            (assoc current-state
              :next-dn-fn request-complete
              :last-write (or last-write (.last-write current-state)))

            :else
            (assoc current-state
              :next-dn-fn response-pending
              :last-write (or last-write (.last-write current-state)))))
         #(finalize-request state %)))
      (let [last-write (.write ch (mk-netty-chunk val))]
        (swap!
         state
         (fn [current-state]
           (assoc current-state :last-write last-write)))))))

(defn- stream-or-finalize-response
  [state ^HttpChunk msg ^State current-state]
  (let [upstream-fn   (.upstream current-state)
        downstream-fn (.downstream current-state)]
    (if (.isLast msg)
      (do (swap-then!
           state
           (fn [^State current-state]
             (if (= response-pending (.next-dn-fn current-state))
               (assoc current-state
                 :next-dn-fn request-complete
                 :next-up-fn nil)
               (assoc current-state
                 :next-up-fn nil)))
           (fn [current-state]
             (debug {:msg "Sending upstream" :event [:body nil]
                     :state current-state})
             (upstream-fn :body nil)
             (finalize-request state current-state))))
      (do
        (debug {:msg "Sending upstream" :event [:body (.getContent msg)]
                :state @state})
        (upstream-fn :body (.getContent msg))))))

(defn- initial-response
  [state ^HttpResponse msg ^State current-state]
  ;; Check to see that the response isn't too crazy
  (when (and (is-100-continue? msg) (not (.expects-100? current-state)))
    (throw (Exception. "Not expecting a 100 Continue response.")))

  (let [upstream-fn   (.upstream current-state)
        downstream-fn (.downstream current-state)]
    (swap-then!
     state
     (fn [^State current-state]
       (let [keepalive? (and (.keepalive? current-state)
                             (HttpHeaders/isKeepAlive msg))]
         (cond
          (is-100-continue? msg)
          (assoc current-state :expects-100? false)

          ;; If the response is chunked, then we need to
          ;; stream the body through
          (and (not (.head? current-state)) (.isChunked msg))
          (assoc current-state
            :keepalive? keepalive?
            :next-up-fn stream-or-finalize-response)

          ;; If the exchange is waiting for the response to complete then
          ;; finish everything up
          (= response-pending (.next-dn-fn current-state))
          (assoc current-state
            :keepalive? keepalive?
            :next-dn-fn request-complete
            :next-up-fn nil)

          ;; Otherwise, just mark the request as done
          :else
          (assoc current-state
            :keepalive? keepalive?
            :next-up-fn nil))))
     (fn [^State current-state]
       (let [response (netty-resp->resp msg (.head? current-state))]
         (debug {:msg "Sending upstream" :event [:response response]
                 :state current-state})
         (upstream-fn :response response)
         (finalize-request state current-state))))))

(defn- initial-write-succeeded
  [state ^State current-state]
  (swap-then!
   state
   (fn [^State current-state]
     (cond
      ;; If the body is chunked, the next events will
      ;; be the HTTP chunks
      (.chunked? current-state)
      (assoc current-state :next-dn-fn stream-or-finalize-request)

      ;; If the body is not chunked, then the request is
      ;; finished. If the response has already been completed
      ;; then we must finish up the request
      (nil? (.next-up-fn current-state))
      (assoc current-state :next-dn-fn request-complete)

      ;; Otherwise, the exchange state is to be awaiting
      ;; the response to complete.
      :else
      (assoc current-state :next-dn-fn response-pending)))
   #(finalize-request state %))
  ;; Signal upstream that we are connected and ready to start handling
  ;; events.
  ((.upstream current-state) :connected nil))

(def interest-ops
  {Channel/OP_NONE       :op-none
   Channel/OP_READ       :op-read
   Channel/OP_WRITE      :op-write
   Channel/OP_READ_WRITE :op-read-write})

(defn- handle-ch-interest-change
  [state ^State current-state interest-ops-lock]
  (let [upstream-fn   (.upstream current-state)
        ch            ^Channel (.ch current-state)]

    (debug {:msg "Interest Ops Changed"
            :event [:interest-ops (interest-ops (.getInterestOps ch))]
            :state current-state})

    ;; Gotta synchronize this shit
    (swap-then!
     interest-ops-lock
     (fn [[writable? count]] [writable? (inc count)])
     (fn [[writable? count]]
       (when (= count 1)
         (try
           (loop [writable? writable?]
             (when (and upstream-fn (not= (.isWritable ch) writable?))
               (swap-then!
                interest-ops-lock
                (fn [[writable? count]] [(not writable?) count])
                (fn [[writable? _]]
                  (debug {:msg   "Sending upstream"
                          :event [(if writable? :resume :pause)]
                          :state current-state})
                  (upstream-fn (if writable? :resume :pause) nil))))
             (let [[writable? count]
                   (swap!
                    interest-ops-lock
                    (fn [[writable? count]] [writable? (dec count)]))]
               (when (< 0 count)
                 (recur writable?))))
           (catch Exception err
             (handle-err state err @state))))))))

(defn- handle-err
  [state err current-state]
  (swap-then!
   state
   #(assoc %
      :next-dn-fn request-complete
      :aborted?   true)
   (fn [^State current-state]
     (debug {:msg "Handling error" :event err :state current-state})
     (when (.upstream current-state)
       (try
         ((.upstream current-state) :abort err)
         (catch Exception _))
       (finalize-request state current-state)))))

(defn- netty-bridge
  [state]
  ;; [ writable? count  ]
  (let [interest-ops-lock (atom [true 0])]
    (netty/upstream-stage
     (fn [_ ^ChannelEvent evt]
       (let [current-state ^State @state]
         (debug {:msg   "Netty event"
                 :event evt
                 :state current-state})
         (when-not (.aborted? current-state)
           (cond-let
            [msg (netty/message-event evt)]
            (try
              (bump-timeout state)
              ((.next-up-fn current-state) state msg current-state)
              (catch Exception err
                (handle-err state err current-state)))

            ;; The channel interest has changed to writable
            ;; or not writable
            [[ch-state val] (netty/channel-state-event evt)]
            (cond
             (and (nil? val) (= ch-state ChannelState/CONNECTED)
                  (not= request-complete (.next-dn-fn current-state)))
             (handle-err state (IOException. "Connection reset by peer")
                         current-state)

             (= ch-state ChannelState/INTEREST_OPS)
             (do
               (handle-ch-interest-change state current-state interest-ops-lock)))

            ;; TODO: Bug - The write completion event might be for a
            ;; write from the previous exchange.
            [_ (netty/write-completion-event evt)]
            (when (and (= write-pending (.next-dn-fn current-state))
                       (.. evt getFuture isSuccess))
              (initial-write-succeeded state current-state))

            [err (netty/exception-event evt)]
            (handle-err state err current-state))))))))

(defn- mk-downstream-fn
  [state]
  (fn [evt val]
    (let [current-state ^State @state]
      (debug {:msg   "Downstream event"
              :event [evt val]
              :state current-state})
      (if (= evt :abort)
        (when-not (.aborted? current-state)
          (handle-err state val current-state))

        (do
          (when (.aborted? current-state)
            (throw (Exception. (str "The request has been aborted.\n"
                                    "  Event: " evt "\n"
                                    "  Value: " val))))

          (cond
           (= evt :pause)
           (.setReadable ^Channel (.ch current-state) false)

           (= evt :resume)
           (.setReadable ^Channel (.ch current-state) true)

           (= evt :body)
           (when-let [next-dn-fn (.next-dn-fn current-state)]
             (bump-timeout state)
             (next-dn-fn state evt val current-state))))))))

(defn- mk-initial-state
  [[hdrs body :as  req] accept-fn {pool :pool :as opts}]
  (let [state         (atom nil)
        downstream-fn (mk-downstream-fn state)
        upstream-fn   (accept-fn downstream-fn)]
    (swap!
     state
     (fn [_] (State. pool               ;; The connection pool
                    nil                ;; Netty channel
                    (keepalive? req)   ;; Is the exchange keepalive?
                    (= :chunked body)  ;; Is the request chunked?
                    (= "HEAD" (hdrs :request-method))
                    (= (hdrs "transfer-encoding")
                       "chunked")
                    (= (hdrs "expect") ;; Does the request expect 100 continue?
                       "100-continue")
                    initial-response   ;; Next downstream event handler
                    connection-pending ;; Next upstream event handler
                    downstream-fn      ;; Downstream handler (passed in)
                    upstream-fn        ;; Upstream handler (external interface)
                    nil                ;; Last write
                    nil                ;; Aborted?
                    nil                ;; Timeout
                    opts)))            ;; Request options
    [state downstream-fn]))

(defn- initialize-request
  [state addr request]
  (let [current-state ^State @state
        pool (.pool current-state)]
    (pool/checkout-conn
     pool addr (netty-bridge state)
     ;; When a connection to the remote host has been established.
     (fn [^Channel ch-or-err fresh?]
       (debug {:msg   (str "Checked out channel: " ch-or-err " " fresh?)
               :event [:request request]})
       (if (instance? Exception ch-or-err)
         ;; Handle exceptions
         (handle-err state ch-or-err current-state)

         ;; Start the request
         (swap-then!
          state
          #(assoc %
             :ch         ch-or-err
             :next-dn-fn write-pending)
          (fn [^State current-state]
            (if (.aborted? current-state)
              ;; Something happened (an error?) and this HTTP exchange
              ;; is done. However, nothing was written to the socket
              ;; yet so we can return the connection to pool for
              ;; future use.
              (do
                (debug {:msg   "Exchange canceled before request written."
                        :event ch-or-err
                        :state current-state})
                (pool/checkin-conn pool ch-or-err))

              ;; Otherwise, do stuff with it
              (let [write-future (.write ch-or-err (req->netty-req request))]
                (debug {:msg   "Writing request to socket"
                        :event [:request request]
                        :state current-state})
                ;; Start tracking timeouts for this exchange.
                (bump-timeout state)

                ;; Save off the write future
                (swap! state #(assoc % :last-write write-future))

                ;; Write the request to the connection
                (netty/on-complete
                 write-future
                 (fn [^ChannelFuture future]
                   (debug
                    (let [current-state @state]
                      (debug {:msg   "Initial write finished"
                              :event future
                              :state current-state})))
                   ;; When the write is not successfull, then there is something
                   ;; wrong with the connection. If the connection is
                   ;; "fresh" then it was established for this request
                   ;; and the write failure indicates something wrong
                   ;; with the end server. If the connection is not
                   ;; "fresh" then a previous request succeeded on the
                   ;; connection, so something else went wrong
                   ;; (perhaps the server terminated the connection
                   ;; before it received the write). In this case, we
                   ;; want to retry the request since there is a good
                   ;; chance that the request will eventually get through.
                   ;; PS: If anybody knows how to test this code path, please
                   ;; let me know.
                   (when-not (.isSuccess future)
                     (debug {:msg   "Request write failed"
                             :event [:request request]
                             :state @state})
                     (if fresh?
                       (handle-err state (.getCause future) current-state)
                       (do
                         ;; Clear the timeout first since we're going
                         ;; to discard the connection in a minute.
                         (clear-timeout state)
                         ;; Cleanup the bogus connection to make sure
                         ;; we don't hit any max connections next time
                         ;; around.
                         (pool/close-conn pool ch-or-err)
                         ;; Restart the exchange.
                         (initialize-request state addr request)))))))))))))))

;; A global pool
(def GLOBAL-POOL (pool/mk-pool))

;; Alias so that the pool namespace doesn't have to be required as
;; well as the client namespace
(def mk-pool       pool/mk-pool)
(def shutdown-pool pool/shutdown)

(def default-options
  {:pool      (pool/mk-pool)
   :timeout   60})

(defn request
  ([addr req accept-fn]
     (request addr req {} accept-fn))
  ([addr request opts accept-fn]
     (debug {:msg   "Handling request"
             :event [:request request]})
     ;; Create an atom that contains the state of the request
     (let [opts (merge default-options opts)]
      (let [[state downstream-fn] (mk-initial-state request accept-fn opts)]
        (initialize-request state addr request)
        downstream-fn))))

(defn simple-request
  [^String uri hdrs body accept-fn]
  (let [uri          (URI. uri)
        request-hdrs (merge (uri->request-headers uri) hdrs)
        addr         [(or (.getHost uri) (hdrs "host"))
                      (if (> (.getPort uri) 0) (.getPort uri))]]
    (request addr [request-hdrs body] hdrs accept-fn)))

(defmacro def-simple-request-for
  [method]
  `(defn ~method
     ([uri# accept-fn#]
        (~method uri# {} nil accept-fn#))
     ([uri# hdrs-or-body# accept-fn#]
        (if (map? hdrs-or-body#)
          (~method uri# hdrs-or-body# "" accept-fn#)
          (~method uri# {} hdrs-or-body# accept-fn#)))
     ([uri# hdrs# body# accept-fn#]
        (simple-request
         uri# (assoc hdrs# :request-method ~(name method))
         body# accept-fn#))))

(def-simple-request-for HEAD)
(def-simple-request-for GET)
(def-simple-request-for POST)
(def-simple-request-for PUT)
(def-simple-request-for DELETE)

