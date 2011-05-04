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
    HttpMethod
    HttpRequestEncoder
    HttpResponse
    HttpResponseDecoder
    HttpVersion]))

(defrecord State [addr ch next-up-fn next-dn-fn upstream downstream])

(defn- chunked?
  [[_ _ body]]
  (= body :chunked))

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

(defn- connection-pending
  [_ _ _ _]
  ;; Need to be able to handle abortiong
  (throw (Exception. "The connection has not yet been established")))

(defn- response-pending
  [_ _ _ _]
  (throw (Exception. "Not implemented yet")))

(defn- request-complete
  [_ _ _ _]
  ;; Just ignore abort events, throw if anything else
  (throw (Exception. "The request is complete")))

(defn- stream-or-finalize-request
  [_ _ _ _]
  (throw (Exception. "Not implemented yet")))

(defn- stream-or-finalize-response
  [state ^HttpChunk msg args]
  (let [downstream-fn (.downstream args)
        upstream-fn   (.upstream args)]
    (if (.isLast msg)
      (do (downstream-fn upstream-fn :done nil)
          (swap-then!
           state
           (fn [current-state]
             (if (= response-pending (.next-up-fn current-state))
               (assoc current-state
                 :next-up-fn request-complete
                 :next-dn-fn nil)
               (assoc current-state
                 :next-dn-fn nil)))
           (fn [current-state]
             )))
      (downstream-fn upstream-fn :body (.getContent msg)))))

(defn- finalize-request
  [current-state]
  (if (= request-complete (.next-up-fn current-state))
    (let [ch (.ch current-state)]
      (.close ch))))

(defn- initial-response
  [state ^HttpResponse msg args]
  (let [downstream-fn (.downstream args)
        upstream-fn   (.upstream args)]
    (downstream-fn upstream-fn :respond (netty-resp->resp msg))
    (swap-then!
     state
     (fn [current-state]
       (cond
        ;; If the response is chunked, then we need to
        ;; stream the body through
        (.isChunked msg)
        (assoc current-state :next-dn-fn stream-or-finalize-response)

        ;; If the exchange is waiting for the response to complete then
        ;; finish everything up
        (= response-pending (.next-up-fn current-state))
        (assoc current-state
          :next-up-fn request-complete
          :next-dn-fn nil)

        ;; Otherwise, just mark the request as done
        :else
        (assoc current-state :next-dn-fn nil)))
     finalize-request)))

(defn- netty-bridge
  [state]
  (netty/upstream-stage
   (fn [ch evt]
     (cond-let
      [msg (netty/message-event evt)]
      (let [current-state @state]
        ((.next-dn-fn current-state) state msg current-state))

      [err (netty/exception-event evt)]
      (do (.printStackTrace err))))))

(defn- mk-upstream-fn
  [state]
  (fn [evt val]
    (let [current-state @state]
      ((.next-up-fn current-state) evt val current-state))))

(defn- mk-initial-state
  [addr downstream-fn]
  (let [state       (atom nil)
        upstream-fn (mk-upstream-fn state)]
    (swap!
     state
     (fn [_] (State. addr
                    nil                ;; Netty channel
                    connection-pending ;; Next upstream event handler
                    initial-response   ;; Next downstream event handler
                    upstream-fn        ;; Upstream handler (external interface)
                    downstream-fn)))   ;; Downstream handler (passed in)
    [state upstream-fn]))

(defn request
  ([addr req downstream-fn]
     (request (pool/mk-pool) addr req downstream-fn))
  ([pool addr [_ body :as request] downstream-fn]
     ;; Create an atom that contains the state of the request
     (let [[state upstream-fn] (mk-initial-state addr downstream-fn)]
       (pool/checkout-conn
        pool addr (netty-bridge state)
        ;; When a connection to the remote host has been established.
        (fn [ch]
          ;; Stash the channel in the state structure
          (swap! state (fn [st] (assoc st :ch ch)))

          ;; Write the request to the connection
          (netty/on-complete
           (.write ch (req->netty-req request))
           ;; When the request has successfully been
           ;; written, move the state of the exchange forward
           (fn [_]
             (downstream-fn upstream-fn :connected nil)
             ;; TODO: handle returning the connection to the pool
             (swap-then!
              state
              (fn [current-state]
                (cond
                 ;; If the body is chunked, the next events will
                 ;; be the HTTP chunks
                 (= :chunked body)
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
              finalize-request))))))))

(defn mk-proxy
  ([] (mk-proxy (pool/mk-pool)))
  ([pool]
     ;; (fn [resp]
     ;;   (let [state (atom [incoming-request {:pool pool :resp resp}])]
     ;;     (fn [evt val]
     ;;       (let [[next-fn args] @state]
     ;;         (next-fn state evt val args)))))
     1))
