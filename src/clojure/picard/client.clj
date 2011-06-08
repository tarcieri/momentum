(ns picard.client
  (:use [picard.utils])
  (:require
   [clojure.string :as str]
   [picard.netty   :as netty]
   [picard.pool    :as pool])
  (:import
   [org.jboss.netty.channel
    Channel
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
    HttpVersion]))

(defrecord State
    [pool
     ch
     keepalive?
     chunked?
     chunk-trailer?
     expects-100?
     next-up-fn
     next-dn-fn
     downstream
     upstream
     last-write
     aborted?])

(defn- keepalive?
  [[hdrs]]
  (not= "close" (hdrs "connection")))

(defn- is-100-continue?
  [^HttpResponse resp]
  (= HttpResponseStatus/CONTINUE
     (.getStatus resp)))

(defn- request-complete
  [_ evt _ _]
  (when-not (= :abort evt)
    (throw (Exception. "The request is complete"))))

(defn- finalize-request
  [current-state]
  (when (= request-complete (.next-dn-fn current-state))
    (netty/on-complete
     (.last-write current-state)
     (fn [_]
       (if (and (.keepalive? current-state)
                (not (.aborted? current-state)))
         (pool/checkin-conn (.pool current-state) (.ch current-state))
         (when-let [ch (.ch current-state)]
           (pool/close-conn (.pool current-state) ch)))))))

(defn- connection-pending
  [_ _ _ _]
  (throw (Exception. "The connection has not yet been established")))

(defn- write-pending
  [_ evt _ _]
  (if (or (= :done evt) (= :body evt))
    (throw (Exception. "The first write has not yet succeded"))))

(defn- response-pending
  [_ _ _ _])

(defn- stream-or-finalize-request
  [state evt val current-state]
  (let [ch (.ch current-state)]
    (if (= :done evt)
      (let [last-write
            (when (.chunk-trailer? current-state)
              (.write ch HttpChunk/LAST_CHUNK))]
        (swap-then!
         state
         (fn [current-state]
           (cond
            (nil? (.next-up-fn current-state))
            (assoc current-state
              :next-dn-fn request-complete
              :last-write (or last-write (.last-write current-state)))

            :else
            (assoc current-state
              :next-dn-fn response-pending
              :last-write (or last-write (.last-write current-state)))))
         finalize-request))
      (let [last-write (.write ch (mk-netty-chunk val))]
        (swap!
         state
         (fn [current-state]
           (assoc current-state :last-write last-write)))))))

(defn- stream-or-finalize-response
  [state ^HttpChunk msg args]
  (let [upstream-fn   (.upstream args)
        downstream-fn (.downstream args)]
    (if (.isLast msg)
      (do (swap-then!
           state
           (fn [current-state]
             (if (= response-pending (.next-dn-fn current-state))
               (assoc current-state
                 :next-dn-fn request-complete
                 :next-up-fn nil)
               (assoc current-state
                 :next-up-fn nil)))
           finalize-request)
          (upstream-fn downstream-fn :done nil))
      (upstream-fn downstream-fn :body (.getContent msg)))))

(defn- initial-response
  [state ^HttpResponse msg current-state]
  ;; Check to see that the response isn't too crazy
  (when (and (is-100-continue? msg) (not (.expects-100? current-state)))
    (throw (Exception. "Not expecting a 100 Continue response.")))

  (let [upstream-fn   (.upstream current-state)
        downstream-fn (.downstream current-state)]
    (swap-then!
     state
     (fn [current-state]
       (let [keepalive? (and (.keepalive? current-state)
                             (HttpHeaders/isKeepAlive msg))]
         (cond
          (is-100-continue? msg)
          (assoc current-state :expects-100? false)

          ;; If the response is chunked, then we need to
          ;; stream the body through
          (.isChunked msg)
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
     finalize-request)
    (upstream-fn downstream-fn :response (netty-resp->resp msg))))

(defn- initial-write-succeeded
  [state current-state]
  (swap-then!
   state
   (fn [current-state]
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
   finalize-request)
  ;; The connected event is only sent downstream when the
  ;; request body is marked as chunked because that's the
  ;; only time that we really care. Also, there is somewhat
  ;; of a race condition where the response could be sent
  ;; upstream before this is called.
  ;; TODO: This should be moved into the netty bridge so that
  ;;       all downstream function calls happen on the same
  ;;       thread.
  (when (.chunked? current-state)
    ((.upstream current-state) (.downstream current-state) :connected nil)))

(defn- handle-ch-interest-change
  [state current-state writable?]
  (let [upstream-fn   (.upstream current-state)
        downstream-fn (.downstream current-state)
        ch            (.ch current-state)]
    (when (and upstream-fn (not= (.isWritable ch) @writable?))
      (swap-then!
       writable? not
       #(try (upstream-fn downstream-fn (if % :resume :pause) nil)
             (catch Exception err
               (throw (Exception. "Not implemented yet"))))))))

(defn- handle-err
  [state err current-state]
  (swap-then!
   state
   (fn [current-state]
     (assoc current-state
       :next-dn-fn request-complete
       :aborted?   true))
   (fn [current-state]
     (when (.upstream current-state)
       (try
         ((.upstream current-state) (.downstream current-state) :abort err)
         (catch Exception _))
       (finalize-request current-state)))))

(defn- netty-bridge
  [state]
  (let [writable? (atom true)]
    (netty/upstream-stage
     (fn [_ evt]
       (let [current-state @state]
         (when-not (.aborted? current-state)
           (cond-let
            [msg (netty/message-event evt)]
            ((.next-up-fn current-state) state msg current-state)

            ;; The channel interest has changed to writable
            ;; or not writable
            [[ch-state val] (netty/channel-state-event evt)]
            (cond
             (and (nil? val) (= ch-state ChannelState/CONNECTED)
                  (not= request-complete (.next-dn-fn current-state)))
             (handle-err state (Exception. "Connection reset by peer") current-state)

             (= ch-state ChannelState/INTEREST_OPS)
             (handle-ch-interest-change state current-state writable?))

            [_ (netty/write-completion-event evt)]
            (when (and (= write-pending (.next-dn-fn current-state))
                       (.. evt getFuture isSuccess))
              (initial-write-succeeded state current-state))

            [err (netty/exception-event evt)]
            (do (.printStackTrace err)))))))))

(defn- mk-downstream-fn
  [state]
  (fn [evt val]
    (let [current-state @state]
      (if (= evt :abort)
        (when-not (.aborted? current-state)
          (handle-err state val current-state))
        (do
          (when (.aborted? current-state)
            (throw (Exception. "The request has been aborted")))

          (cond
           (= evt :pause)
           (.setReadable (.ch current-state) false)

           (= evt :resume)
           (.setReadable (.ch current-state) true)

           :else
           ((.next-dn-fn current-state) state evt val current-state)))))))

(defn- mk-initial-state
  [pool [hdrs body :as  req] upstream-fn]
  (let [state       (atom nil)
        downstream-fn (mk-downstream-fn state)]
    (swap!
     state
     (fn [_] (State. pool               ;; The connection pool
                    nil                ;; Netty channel
                    (keepalive? req)   ;; Is the exchange keepalive?
                    (= :chunked body)  ;; Is the request chunked?
                    (= (hdrs "transfer-encoding")
                       "chunked")
                    (= (hdrs "expect") ;; Does the request expect 100 continue?
                       "100-continue")
                    initial-response   ;; Next downstream event handler
                    connection-pending ;; Next upstream event handler
                    downstream-fn      ;; Downstream handler (passed in)
                    upstream-fn        ;; Upstream handler (external interface)
                    nil                ;; Last write
                    nil)))             ;; Aborted?
    [state downstream-fn]))

(defn- initialize-request
  [state addr request]
  (let [current-state @state
        pool (.pool current-state)]
    (pool/checkout-conn
     pool addr (netty-bridge state)
     ;; When a connection to the remote host has been established.
     (fn [ch-or-err fresh?]
       (if (instance? Exception ch-or-err)
         ;; Handle exceptions
         (handle-err state ch-or-err current-state)

         ;; Start the request
         (swap-then!
          state
          (fn [current-state]
            (if (.aborted? current-state)
              current-state
              (assoc current-state
                :ch         ch-or-err
                :next-dn-fn write-pending)))
          (fn [current-state]
            (if (.aborted? current-state)
              ;; Return to the connection pool
              (pool/checkin-conn pool ch-or-err)

              ;; Otherwise, do stuff with it
              (let [write-future (.write ch-or-err (req->netty-req request))]
                ;; Save off the write future
                (swap! state (fn [current-state]
                               (assoc current-state :last-write write-future)))
                ;; Write the request to the connection
                (netty/on-complete
                 write-future
                 ;; TODO: Handle unsuccessfull connections here
                 (fn [future]
                   (when-not (.isSuccess future)
                     (if fresh?
                       (handle-err state (.getCause future) current-state)
                       (initialize-request state addr request))))))))))))))

;; A global pool
(def GLOBAL-POOL (pool/mk-pool))

;; Alias so that the pool namespace doesn't have to be required as
;; well as the client namespace
(def mk-pool pool/mk-pool)

(defn request
  ([addr req upstream-fn]
     (request GLOBAL-POOL addr req upstream-fn))
  ([pool addr request upstream-fn]
     ;; Create an atom that contains the state of the request
     (let [[state downstream-fn] (mk-initial-state pool request upstream-fn)]
       ;; TODO: Handle cases where the channel returned is open but
       ;;       writes to it will fail.
       (initialize-request state addr request)
       downstream-fn)))
