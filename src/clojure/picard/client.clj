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
    HttpVersion]))

;; TODO: A big issue that still needs to be handed is that
;;       all events that happen after a write has occured
;;       must not happen immedietly, but after the write
;;       future has completed.

(defrecord State
    [pool ch keepalive? chunked? next-up-fn next-dn-fn upstream downstream])

(defn- chunked?
  [[_ _ body]]
  (= body :chunked))

(defn- keepalive?
  [[hdrs]]
  (not= "close" (hdrs "connection")))

;; (defn- handling-initial-request
;;   [_ _ _ _]
;;   (throw (Exception. "Bro, I thought I told you to chill out")))

;; (defn- incoming-request
;;   [state evt [hdrs body :as val] args]
;;   (when-not (= :request evt)
;;     (throw (Exception. "Picard is confused... requests start with the head.")))

;;   ;; First, raise an exception if any further events are received
;;   (swap! state (fn [[_ args]] [handling-initial-request args]))

;;   (let [resp (args :resp) pool (args :pool) host (hdrs "host")]
;;     ;; Acquire a channel from the pool
;;     (pool/checkout-conn
;;      pool host
;;      (netty-bridge state resp)
;;      (fn [ch]
;;        ;; TODO - handle the error case
;;        (netty/on-complete
;;         (.write ch (req->netty-req val))
;;         (fn [_]
;;           (let [[_ {placeholder :ch}] @state]
;;             (swap! state (fn [[f args]] [f (assoc args :ch ch)]))
;;             ;; When the request is chunked and we sent a pause
;;             ;; event downstream
;;             (when (and (= :pending placeholder) (chunked? val))
;;               (resp :resume nil)))))))

;;     ;; If the body is chunked, then mark the request as paused
;;     ;; until a channel has been acquired.
;;     (when (chunked? val)
;;       (locking val
;;         (let [[_ {ch :ch}] @state]
;;           (when-not ch
;;             (swap! state (fn [f args] [f (assoc args :ch :pending)]))
;;             (resp :pause nil)))))))

(defn- request-complete
  [_ evt _ _]
  (when-not (= :abort evt)
   (throw (Exception. "The request is complete"))))

(defn- finalize-request
  [current-state]
  (if (= request-complete (.next-up-fn current-state))
    (if (.keepalive? current-state)
      (pool/checkin-conn (.pool current-state) (.ch current-state))
      (.close (.ch current-state)))))

(defn- connection-pending
  [_ _ _]
  ;; Need to be able to handle abortiong
  (throw (Exception. "The connection has not yet been established")))

(defn- write-pending
  [_ _ _]
  ;; Need to be able to handle aborting
  (throw (Exception. "The first write has not yet succeded")))

(defn- response-pending
  [_ _ _ _]
  (throw (Exception. "Not implemented yet")))

(defn- stream-or-finalize-request
  [state evt val current-state]
  (let [ch (.ch current-state)]
    (if (= :done evt)
      (do (.write ch HttpChunk/LAST_CHUNK)
          (swap-then!
           state
           (fn [current-state]
             (cond
              (nil? (.next-dn-fn current-state))
              (assoc current-state :next-up-fn request-complete)

              :else
              (assoc current-state :next-up-fn response-pending)))
           finalize-request))
      (.write ch (mk-netty-chunk val)))))

(defn- stream-or-finalize-response
  [state ^HttpChunk msg args]
  (let [downstream-fn (.downstream args)
        upstream-fn   (.upstream args)]
    (if (.isLast msg)
      (do (swap-then!
           state
           (fn [current-state]
             (if (= response-pending (.next-up-fn current-state))
               (assoc current-state
                 :next-up-fn request-complete
                 :next-dn-fn nil)
               (assoc current-state
                 :next-dn-fn nil)))
           finalize-request)
          (downstream-fn upstream-fn :done nil))
      (downstream-fn upstream-fn :body (.getContent msg)))))

(defn- initial-response
  [state ^HttpResponse msg args]
  (let [downstream-fn (.downstream args)
        upstream-fn   (.upstream args)]
    (swap-then!
     state
     (fn [current-state]
       (let [keepalive? (and (.keepalive? current-state)
                             (HttpHeaders/isKeepAlive msg))]
         (cond
          ;; If the response is chunked, then we need to
          ;; stream the body through
          (.isChunked msg)
          (assoc current-state
            :keepalive? keepalive?
            :next-dn-fn stream-or-finalize-response)

          ;; If the exchange is waiting for the response to complete then
          ;; finish everything up
          (= response-pending (.next-up-fn current-state))
          (assoc current-state
            :keepalive? keepalive?
            :next-up-fn request-complete
            :next-dn-fn nil)

          ;; Otherwise, just mark the request as done
          :else
          (assoc current-state
            :keepalive? keepalive?
            :next-dn-fn nil))))
     finalize-request)
    (downstream-fn upstream-fn :respond (netty-resp->resp msg))))

(defn- initial-write-succeeded
  [state current-state]
  (swap-then!
   state
   (fn [current-state]
     (cond
      ;; If the body is chunked, the next events will
      ;; be the HTTP chunks
      (.chunked? current-state)
      (assoc current-state :next-up-fn stream-or-finalize-request)

      ;; If the body is not chunked, then the request is
      ;; finished. If the response has already been completed
      ;; then we must finish up the request
      (nil? (.next-dn-fn current-state))
      (assoc current-state :next-up-fn request-complete)

      ;; Otherwise, the exchange state is to be awaiting
      ;; the response to complete.
      :else
      (assoc current-state :next-up-fn response-pending)))
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
    ((.downstream current-state) (.upstream current-state) :connected nil)))

(defn- netty-bridge
  [state]
  (netty/upstream-stage
   (fn [ch evt]
     (let [current-state @state]
       (cond-let
        [msg (netty/message-event evt)]
        ((.next-dn-fn current-state) state msg current-state)

        [_ (netty/write-completion-event evt)]
        (when (and (= write-pending (.next-up-fn current-state))
                   (.. evt getFuture isSuccess))
          (initial-write-succeeded state current-state))

        [err (netty/exception-event evt)]
        (do (.printStackTrace err)))))))

(defn- mk-upstream-fn
  [state]
  (fn [evt val]
    (let [current-state @state]
      ((.next-up-fn current-state) state evt val current-state))))

(defn- mk-initial-state
  [pool [_ body :as  req] downstream-fn]
  (let [state       (atom nil)
        upstream-fn (mk-upstream-fn state)]
    (swap!
     state
     (fn [_] (State. pool               ;; The connection pool
                    nil                ;; Netty channel
                    (keepalive? req)   ;; Is the exchange keepalive?
                    (= :chunked body)  ;; Is the request chunked?
                    connection-pending ;; Next upstream event handler
                    initial-response   ;; Next downstream event handler
                    upstream-fn        ;; Upstream handler (external interface)
                    downstream-fn)))   ;; Downstream handler (passed in)
    [state upstream-fn]))

;; Alias so that the pool namespace doesn't have to be required as
;; well as the client namespace
(def mk-pool pool/mk-pool)

(defn request
  ([addr req downstream-fn]
     (request (pool/mk-pool) addr req downstream-fn))
  ([pool addr [_ body :as request] downstream-fn]
     ;; Create an atom that contains the state of the request
     (let [[state upstream-fn] (mk-initial-state pool request downstream-fn)]
       ;; TODO: Handle cases where the channel returned is open but
       ;;       writes to it will fail.
       (pool/checkout-conn
        pool addr (netty-bridge state)
        ;; When a connection to the remote host has been established.
        (fn [ch]
          ;; Stash the channel in the state structure
          (swap!
           state
           (fn [current-state]
             (assoc current-state :ch ch :next-up-fn write-pending)))

          ;; Write the request to the connection
          (netty/on-complete
           (.write ch (req->netty-req request))
           ;; When the request has successfully been
           ;; written, move the state of the exchange forward
           (fn [future] 1)))))))

(defn mk-proxy
  ([] (mk-proxy (pool/mk-pool)))
  ([pool]
     ;; (fn [resp]
     ;;   (let [state (atom [incoming-request {:pool pool :resp resp}])]
     ;;     (fn [evt val]
     ;;       (let [[next-fn args] @state]
     ;;         (next-fn state evt val args)))))
     1))
