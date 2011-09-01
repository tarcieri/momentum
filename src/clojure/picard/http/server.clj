(ns picard.http.server
  (:use
   picard.utils
   picard.http.core)
  (:require
   [picard.core.timer :as timer]
   [picard.net.server :as net])
  (:import
   [org.jboss.netty.handler.codec.http
    HttpRequestDecoder
    HttpResponseEncoder]))

;; TODO:
;; * Count the bytes in the request body

(declare
 handle-request
 stream-or-finalize-request
 handle-response
 stream-or-finalize-response
 awaiting-100-continue
 waiting-for-response)

(defprotocol Timeout
  (get-timeout-ms [_])
  (get-timeout [_])
  (abort [_]))

(defrecord ConnectionState
    [upstream
     downstream
     address-info
     timeout
     opts]
  Timeout
  (get-timeout-ms [current-state]
    (* (-> current-state .opts :keepalive)) 1000)
  (get-timeout [current-state]
    (.timeout current-state))
  (abort [current-state]
    (let [downstream (.downstream current-state)]
      (downstream :abort (Exception. "HTTP exchange taking too long.")))))

(defrecord ExchangeState
    [connection
     address-info
     upstream
     downstream
     next-up-fn
     next-dn-fn
     keepalive?
     chunked?
     head?
     responded?
     timeout
     opts]
  Timeout
  (get-timeout-ms [current-state]
    (* (-> current-state .opts :timeout) 1000))
  (get-timeout [current-state]
    (.timeout current-state))
  (abort [current-state]
    (let [downstream (.downstream current-state)]
      (downstream :abort (Exception. "Connection reached max keep alive time.")))))

(defn- initial-connection-state
  [dn opts]
  (ConnectionState.
   nil
   dn
   nil
   nil
   opts))

(defn- initial-exchange-state
  [conn downstream address-info opts]
  (ExchangeState.
   conn
   address-info
   nil
   downstream
   handle-request
   handle-response
   true
   false
   false
   false
   nil
   opts))

;; Timeouts

(defn- clear-timeout*
  [current-state]
  (when-let [timeout (get-timeout current-state)]
    (timer/cancel timeout)))

(defn- clear-timeout
  [state current-state]
  (locking state
    (clear-timeout* current-state)
    (swap! state #(assoc % :timeout nil))))

(defn- bump-timeout
  [state current-state]
  (locking state
    (clear-timeout* current-state)
    (let [ms      (get-timeout-ms current-state)
          timeout (timer/register ms #(abort current-state))]
      (swap! state #(assoc % :timeout timeout)))))

(defn- awaiting-100-continue?
  [current-state]
  (= awaiting-100-continue (.next-up-fn current-state)))

(defn- awaiting-response?
  [current-state]
  (= waiting-for-response (.next-up-fn current-state)))

(defn- exchange-finished?
  [current-state]
  (and (.responded? current-state)
       (or (awaiting-response? current-state)
           (awaiting-100-continue? current-state))))

(defn- maybe-finalize-exchange
  [current-state]
  (when (exchange-finished? current-state)
    (let [conn     (.connection current-state)
          upstream (.upstream current-state)]

      ;; The current exchange is complete, so remove it from
      ;; the connection state.
      (swap! conn #(assoc % :upstream nil))

      ;; Send an upstream event that indicates the HTTP exchange
      ;; is complete.
      (upstream :done nil))))

(defn- maybe-close-connection
  [state current-state]
  (when (exchange-finished? current-state)
    (clear-timeout state current-state)
    (if (.keepalive? current-state)
      (let [conn (.connection current-state)]
        (bump-timeout conn @conn))
      ((.downstream current-state) :close nil))))

(defn- stream-or-finalize-response
  [state evt chunk current-state]
  (when-not (= :body evt)
    (throw (Exception. "Expecting a :body event")))

  (swap-then!
   state
   (fn [current-state]
     (if chunk
       (let [bytes-sent     (.bytes-to-send current-state)
             bytes-expected (.bytes-expected current-state)
             bytes-to-send  (+ bytes-sent (chunk-size chunk))
             responded?     (= bytes-expected bytes-to-send)]
         (assoc current-state
           :bytes-to-send bytes-to-send
           :responded?    responded?
           :next-dn-fn    (when-not responded? stream-or-finalize-request)))
       ;; This is the final chunk
       ;; TODO: Check that the content-length is correct
       (assoc current-state
         :responded? true
         :next-dn-fn nil)))
   (fn [current-state]
     (let [responded? (.responded? current-state)
           chunked?   (.chunked? current-state)
           downstream (.downstream current-state)]
       ;; It only makes sense to check if the exchange needs to be finalized
       ;; when the response is complete.
       (when responded?
         (maybe-finalize-exchange current-state))

       (cond
        chunk    (downstream :message (chunk->netty-chunk chunk))
        chunked? (downstream :message last-chunk))

       (when responded?
         (maybe-close-connection state current-state))))))

(defn- handle-response
  [state evt val current-state]
  (when-not (= :response evt)
    (throw (Exception. "Expecting a :response event")))

  (let [[status hdrs body] val
        body           (and (not (.head? current-state)) body)
        bytes-expected (content-length hdrs)
        bytes-to-send  (chunk-size body)
        message        (response->netty-response status hdrs body)]

    ;; 100 responses mean there will be other responses following. When
    ;; this is the final response header, track the number of bytes about
    ;; to be sent as well as the number of bytes to expect as specified
    ;; by the content-length header. The HTTP exchange is considered
    ;; complete unless the body is specifically chunked. If there is no
    ;; content-length or transfer-encoding: chunked header, then the HTTP
    ;; exchange will be finalized by closing the connection.
    (when (and (= 100 status) (not (awaiting-100-continue? current-state)))
      (throw (Exception. "Not expecting a 100 Continue response.")))

    ;; 204 and 304 responses MUST NOT have a response body, so if we
    ;; get one, throw an exception.
    (when (and (#{204 304} status) (not (empty? body)))
      (throw (Exception. (str status " responses must not include a body."))))

    (swap-then!
     state
     (fn [current-state]
       (if (= 100 status)
         (assoc current-state :next-up-fn stream-or-finalize-request)
         (let [responded? (or (.head? current-state) (not= :chunked body))]
           (assoc current-state
             :bytes-to-send  bytes-to-send
             :bytes-expected bytes-expected
             :responded?     responded?
             ;; TODO: This isn't exactly correct since 304 responses
             ;; won't send the body and we also need to handle the
             ;; case of transfer-encoding: chunked w/ a single chunk
             ;; passed with the response.
             :chunked?      (= (hdrs "transfer-encoding") "chunked")
             :next-dn-fn    (when (not responded?) stream-or-finalize-response)
             :keepalive?    (and (.keepalive? current-state)
                                 (not= "close" (hdrs "connection"))
                                 (or (hdrs "content-length")
                                     (= (hdrs "transfer-encoding") "chunked")
                                     (not (status-expects-body? status))))))))
     (fn [current-state]
       (let [downstream (.downstream current-state)
             responded? (.responded? current-state)
             message    (response->netty-response status hdrs body)]
         (when responded?
           (maybe-finalize-exchange current-state))
         (downstream :message message)
         (when responded?
           (maybe-close-connection state current-state)))))))

(defn- waiting-for-response
  [_ _ _ _ _]
  (throw (Exception. "Not expecting a message right now.")))

(defn- stream-or-finalize-request
  [state evt chunk current-state]
  (if chunk
    ((.upstream current-state) :body chunk)
    (swap-then!
     state
     #(assoc % :next-up-fn waiting-for-response)
     (fn [current-state]
       (let [responded? (.responded? current-state)
             upstream   (.upstream current-state)]
         (when responded?
           (maybe-finalize-exchange current-state))
         (upstream :body nil)
         (when responded?
           (maybe-close-connection state current-state)))))))

(defn- awaiting-100-continue
  [state evt val current-state]
  (swap-then!
   state
   #(assoc % :next-up-fn stream-or-finalize-request)
   #(stream-or-finalize-request state evt val %)))

(defn- handle-request
  [state _ [hdrs body :as request] current-state]
  (let [hdrs         (merge (.address-info current-state) hdrs)
        keepalive?   (keepalive-request? request)
        expects-100? (expecting-100? request)
        head?        (= "HEAD" (hdrs :request-method))
        chunked?     (= :chunked body)
        next-up-fn   (cond
                      (not chunked?)  waiting-for-response
                      expects-100?    awaiting-100-continue
                      :else           stream-or-finalize-request)]

    (swap-then!
     state
     #(assoc %
        :keepalive? keepalive?
        :head?      head?
        :next-up-fn next-up-fn)
     (fn [current-state]
       (let [upstream   (.upstream current-state)
             responded? (.responded? current-state)]
         (when responded?
           (maybe-finalize-exchange current-state))
         (upstream :request [hdrs body])
         (when responded?
           (maybe-close-connection state current-state)))))))

(defn- mk-downstream-fn
  [state dn]
  (fn [evt val]
    (let [current-state @state]
      (cond
       (#{:response :body} evt)
       (let [next-dn-fn (.next-dn-fn current-state)]
         (bump-timeout state current-state)
         (next-dn-fn state evt val current-state))

       (#{:abort :close} evt)
       (do
         (clear-timeout state current-state)
         (dn evt val))

       :else
       (dn evt val)))))

(defn- exchange
  [app dn conn address-info opts]
  (let [state   (atom (initial-exchange-state conn dn address-info opts))
        next-up (app (mk-downstream-fn state dn))]
    ;; Track the upstream
    (swap! state #(assoc % :upstream next-up))

    ;; The exchange event handler only processes events
    ;; related to the current HTTP exchange and will pass
    ;; on all other events.
    (fn [evt val]
      (let [current-state @state]
       (cond
        (#{:request :body} evt)
        (let [next-up-fn (.next-up-fn current-state)]
          (bump-timeout state current-state)
          (next-up-fn state evt val current-state))

        (= :close evt)
        (do
          (clear-timeout state current-state)
          (throw-connection-reset-by-peer))

        (= :abort evt)
        (do
          (clear-timeout state current-state)
          (next-up evt val))

        :else
        (next-up evt val))))))

(defn- throw-http-pipelining-exception
  []
  (throw
   (Exception.
    (str "Not expecting an HTTP request right now, "
         "pipelining is not yet supported."))))

(defn proto
  [app opts]
  (fn [dn]
    (let [state (atom (initial-connection-state dn opts))]
      (fn [evt val]
        (let [current-state @state
              next-up (.upstream current-state)]
          (cond
           ;; Check if an exchange is currently in progress. If there
           ;; is one, then throw an exception since pipelining is not
           ;; currently supported.
           (= :request evt)
           (if next-up
             (throw-http-pipelining-exception)
             (do
               (clear-timeout state current-state)
               (let [address-info (.address-info current-state)
                     next-up      (exchange app dn state address-info opts)]
                 (swap! state #(assoc % :upstream next-up))
                 (next-up evt val))))

           next-up
           (next-up evt val)

           (= :close evt)
           (clear-timeout state current-state)

           (= :open evt)
           (do
             (bump-timeout state current-state)
             (swap! state #(assoc % :address-info val)))

           (not (#{:pause :resume :abort} evt))
           (throw
            (Exception.
             (str "Not expecting a message right now: " [evt val])))))))))

(defn- http-pipeline
  [p _]
  (doto p
    (.addLast "encoder" (HttpResponseEncoder.))
    (.addLast "decoder" (HttpRequestDecoder.))))

(def default-options
  {:keepalive 60
   :timeout   5})

(defn start
  ([app] (start app {}))
  ([app opts]
     (let [opts (merge default-options opts {:pipeline-fn http-pipeline})]
       (net/start (proto app opts) opts))))

(defn stop
  [server]
  (net/stop server))
