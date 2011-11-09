(ns momentum.http.server
  (:use
   momentum.core.buffer
   momentum.http.core
   momentum.utils.core)
  (:require
   [momentum.core.timer :as timer]
   [momentum.net.server :as net]))

;; TODO:
;;   - Add more checks for invalid header / body combinations
;;     - Transfer-Encoding w/o :chunked (unless HEAD request)
;;     - Content-Length w/o body or :chunked
;;     - :upgraded w/o upgrade handshake
;;   - Handle CONNECT requests
;;   - Count the bytes in the request body

(declare
 handle-request
 stream-or-finalize-request
 handle-response
 stream-or-finalize-response
 awaiting-100-continue)

(defprotocol Timeout
  (track-timeout? [-])
  (get-timeout-ms [_])
  (get-timeout    [_])
  (abort-timeout  [_]))

(defrecord ConnectionState
    [upstream
     downstream
     address-info
     timeout
     opts]

  Timeout
  (track-timeout? [_] true)
  (get-timeout-ms [current-state]
    (* (-> current-state .opts :keepalive)) 1000)
  (get-timeout [current-state]
    (.timeout current-state))
  (abort-timeout [current-state]
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
     head?
     responded?
     upgrade
     bytes-expected
     bytes-to-send
     timeout
     opts]

  Timeout
  (track-timeout? [current-state]
    (not= :upgraded (.upgrade current-state)))
  (get-timeout-ms [current-state]
    (* (-> current-state .opts :timeout) 1000))
  (get-timeout [current-state]
    (.timeout current-state))
  (abort-timeout [current-state]
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
   conn             ;; connection
   address-info     ;; address-info
   nil              ;; upstream
   downstream       ;; downstream
   handle-request   ;; next-up-fn
   handle-response  ;; next-dn-fn
   true             ;; keepalive?
   false            ;; head?
   false            ;; responded?
   nil              ;; upgrade
   nil              ;; bytes-expected
   0                ;; bytes-to-send
   nil              ;; timeout
   opts))           ;; opts

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
  (when (track-timeout? current-state)
    (locking state
      (clear-timeout* current-state)
      (let [ms      (get-timeout-ms current-state)
            timeout (timer/register ms #(abort-timeout current-state))]
        (swap! state #(assoc % :timeout timeout))))))

(defn- waiting-for-response
  [_ evt val _]
  (throw
   (Exception.
    (str "Not expecting a message right now.n"
         "  EVT: " evt "\n"
         "  VAL: " val))))

(defn- upstream-pass-through
  [_ evt val _]
  (throw
   (Exception.
    (str "Should be receiving message events.\n"
         "  EVT: " evt "\n"
         "  VAL: " val))))

(defn- downstream-pass-through
  [_ evt val _]
  (throw
   (Exception.
    (str "Should be receiving message events.\n"
         "  EVT: " evt "\n"
         "  VAL: " val))))

(defn- awaiting-100-continue?
  [current-state]
  (= awaiting-100-continue (.next-up-fn current-state)))

(defn- awaiting-response?
  [current-state]
  (or (= waiting-for-response (.next-up-fn current-state))
      (= upstream-pass-through (.next-dn-fn current-state))))

(defn- exchange-finished?
  [current-state]
  (and (.responded? current-state)
       (or (awaiting-response? current-state)
           (awaiting-100-continue? current-state))))

(defmacro maybe-finalizing-exchange
  [state current-state & stmts]
  `(let [upstream#   (.upstream ~current-state)
         conn#       (.connection ~current-state)
         finished?#  (exchange-finished? ~current-state)]
     ;; The current exchange is complete, so remove it from
     ;; the connection state.
     (when finished?#
       (swap! conn# #(assoc % :upstream nil)))

     ;; Do what needs to happen
     ~@stmts

     (when finished?#
       (clear-timeout ~state ~current-state)
       (upstream# :done nil)
       (if (.keepalive? ~current-state)
         (bump-timeout conn# @conn#)
         ((.downstream ~current-state) :close nil)))))

(defn- handle-hard-close
  [state _]
  (swap-then!
   state
   #(assoc %
      :responded? true
      :keepalive? false
      :next-up-fn waiting-for-response
      :next-dn-fn nil)
   #(maybe-finalizing-exchange state %)))

(defn- stream-or-finalize-response
  [state evt chunk current-state]
  (when-not (= :body evt)
    (throw (Exception. "Expecting a :body event")))

  (swap-then!
   state
   (fn [current-state]
     (cond
      (buffer? chunk)
      (let [bytes-sent     (.bytes-to-send current-state)
            bytes-expected (.bytes-expected current-state)
            bytes-to-send  (+ bytes-sent (remaining chunk))
            responded?     (= bytes-expected bytes-to-send)]
        (assoc current-state
          :bytes-to-send bytes-to-send
          :responded?    responded?
          :next-dn-fn    (when-not responded? stream-or-finalize-response)))

      ;; This is the final chunk
      ;; TODO: Check that the content-length is correct
      (nil? chunk)
      (assoc current-state
        :responded? true
        :next-dn-fn nil)

      :else
      (throw (Exception. "Not a valid body chunk type"))))
   (fn [current-state]
     (let [downstream (.downstream current-state)]
       (maybe-finalizing-exchange
        state current-state
        (downstream :body chunk))))))

(defn- handle-response
  [state evt response current-state]
  (when-not (= :response evt)
    (throw (Exception. "Expecting a :response event")))

  (let [[status hdrs body] response
        hdrs           (or hdrs {})
        body           (when-not (.head? current-state) body)
        bytes-expected (content-length hdrs)
        bytes-to-send  (chunk-size body)]

    ;; 100 responses mean there will be other responses following. When
    ;; this is the final response header, track the number of bytes about
    ;; to be sent as well as the number of bytes to expect as specified
    ;; by the content-length header. The HTTP exchange is considered
    ;; complete unless the body is specifically chunked. If there is no
    ;; content-length or transfer-encoding: chunked header, then the HTTP
    ;; exchange will be finalized by closing the connection.
    ;;
    ;; TODO: This is actually a race condition since the client might
    ;; start sending the request body before the 100 continue is
    ;; sent. If that happens, the current state will NOT be
    ;; awaiting-100-continue?. However, sending the 100 continue
    ;; should not result in an exception.
    (when (and (= 100 status) (not (awaiting-100-continue? current-state)))
      (throw (Exception. "Not expecting a 100 Continue response.")))

    (when (and (= 101 status) (not= :upgrading (.upgrade current-state)))
      (throw (Exception. "Not expecting a 101 Switching Protocols response.")))

    (when (and (= :upgraded body) (not= 101 status))
      (throw (Exception. (str "Upgrading a connection without setting the "
                              "response status to 101 doesn't make much sense."))))

    ;; 100, 101, 204, and 304 responses MUST NOT have a response body,
    ;; so if we get one, throw an exception.
    (when (not (or (status-expects-body? status) (nil? body) (= :upgraded body)))
      (throw (Exception. (str status " responses must not include a body."))))

    (swap-then!
     state
     (fn [current-state]
       (cond
        (= 100 status)
        (assoc current-state :next-up-fn stream-or-finalize-request)

        (= 101 status)
        (assoc current-state
          :keepalive? false
          :upgrade    :upgraded
          :next-up-fn upstream-pass-through
          :next-dn-fn downstream-pass-through)

        :else
        (let [responded? (or (.head? current-state) (not= :chunked body))]
          (assoc current-state
            :bytes-to-send  bytes-to-send
            :bytes-expected bytes-expected
            :responded?     responded?
            ;; TODO: This isn't exactly correct since 304 responses
            ;; won't send the body and we also need to handle the
            ;; case of transfer-encoding: chunked w/ a single chunk
            ;; passed with the response.
            :next-dn-fn    (when (not responded?) stream-or-finalize-response)
            :keepalive?    (and (.keepalive? current-state)
                                (keepalive-response? response))))))
     (fn [current-state]
       ;; If the connection is upgraded, we don't track timeouts
       ;; anymore.
       ;; TODO: Have a separate option for this
       (when (= :upgraded (.upgrade current-state))
         (clear-timeout state current-state))

       (let [downstream (.downstream current-state)]
         (maybe-finalizing-exchange
          state current-state
          (downstream :response [status hdrs body])))))))

(defn- stream-or-finalize-request
  [state evt chunk current-state]
  (if chunk
    ((.upstream current-state) :body chunk)
    (swap-then!
     state
     #(assoc % :next-up-fn waiting-for-response)
     (fn [current-state]
       (let [upstream (.upstream current-state)]
         (maybe-finalizing-exchange
          state current-state
          (upstream :body nil)))))))

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
        upgrade      (when (= :upgraded body) :upgrading)
        next-up-fn   (cond
                      (not chunked?)  waiting-for-response
                      expects-100?    awaiting-100-continue
                      :else           stream-or-finalize-request)]

    (swap-then!
     state
     #(assoc %
        :keepalive? keepalive?
        :head?      head?
        :upgrade    upgrade
        :next-up-fn next-up-fn)
     (fn [current-state]
       (let [upstream (.upstream current-state)]
         (maybe-finalizing-exchange
          state current-state
          (upstream :request [hdrs body])))))))

(defn- mk-downstream-fn
  [state dn]
  (fn [evt val]
    (let [current-state @state]
      (cond
       (#{:response :body} evt)
       (if-let [next-dn-fn (.next-dn-fn current-state)]
         ;; If there is a next-dn-fn then the response is
         ;; still in progress, so bump the timeout and
         ;; process the event.
         (do
           (bump-timeout state current-state)
           (next-dn-fn state evt val current-state))

         ;; Otherwise, the response is completed. No further
         ;; events are expected, so throw an exception. However,
         ;; if this is a :body event and the request is a HEAD
         ;; request, just discard the body instead of throwing
         ;; an exception. As it turns out, not many people (myself
         ;; included) handle HEAD requests correctly, so let's just
         ;; do it for them.
         (when-not (and (= :body evt) (or (not val) (.head? current-state)))
           (throw (Exception. "Not currently expecting an event."))))

       (= :close evt)
       (handle-hard-close state current-state)

       (= :abort evt)
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
           (if (= :upgraded (.upgrade current-state))
             (next-up evt val)
             (throw-connection-reset-by-peer)))

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

(def default-opts
  {:keepalive 60
   :timeout   5})

(defn handler
  [app opts]
  (let [opts (merge default-opts opts)]
    (fn [dn]
      (let [state (atom (initial-connection-state dn opts))]
        (fn [evt val]
          (let [current-state @state
                next-up (.upstream current-state)]
            (cond
             ;; Check if an exchange is currently in progress. If
             ;; there is one, then throw an exception since
             ;; pipelining is not currently supported.
             (= :request evt)
             (if next-up
               (throw-http-pipelining-exception)
               (do
                 (clear-timeout state current-state)
                 (let [address-info
                       (.address-info current-state)
                       next-up
                       (exchange app dn state address-info opts)]
                   ;; Setup the new exchange
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

             (not (#{:body :pause :resume :abort} evt))
             (throw
              (Exception.
               (str "Not expecting a message right now: "
                    [evt val]))))))))))

(defn- encoder
  [dn]
  (let [chunked? (atom nil)]
    (defstream
      (response [[status hdrs body :as response]]
        (reset! chunked? (= (hdrs "transfer-encoding") "chunked"))
        (send-response dn status hdrs body))

      (body [chunk]
        (send-chunk dn @chunked? chunk))

      (else [evt val]
        (dn evt val)))))

(defn proto
  ([app] (proto app {}))
  ([app opts]
     (let [app (handler app opts)]
       (fn [dn]
         (request-parser
          (app (encoder dn)))))))

(defn start
  ([app] (start app {}))
  ([app opts]
     (net/start (proto app opts) opts)))

(defn stop
  [server]
  (net/stop server))
