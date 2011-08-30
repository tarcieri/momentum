(ns picard.http.server
  (:use picard.http.core))

(declare
 handle-request
 handle-response
 awaiting-100-continue
 waiting-for-response)

(defrecord ExchangeState
    [connection
     next-up-fn
     next-dn-fn
     keepalive?
     chunked?
     head?
     responded?])

(defn- initial-exchange-state
  [conn]
  (ExchangeState.
   conn
   handle-request
   handle-response
   true
   false
   false
   false))

(defn- awaiting-100-continue?
  [current-state]
  `(= awaiting-100-continue (.next-up-fn ~current-state)))

(defn- awaiting-response?
  [current-state]
  `(= waiting-for-response (.next-up-fn ~current-state)))

(defn- finalize-exchange
  [state current-state])

(defn- stream-or-finalize-response
  [])

(defn- handle-response
  [state next-up evt val current-state]
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
         (assoc current-state :next-up-fn stream-request-body)
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
       (throw (Exception. "Not implemented"))))))

(defn- waiting-for-response
  [_ _ _ _ _]
  (throw (Exception. "Not expecting a message right now.")))

(defn- stream-or-finalize-request
  [state next-up evt val current-state]
  ;; TODO: Handle the case when (not= :body evt)
  (let [chunk (normalize-chunk val)]
    (if chunk
      (next-up :body chunk)
      (swap-then!
       state
       #(assoc % :next-up-fn waiting-for-response)
       (fn [current-state]
         (next-up :body nil)
         (finalize-exchange state current-state))))))

(defn- awaiting-100-continue
  [state next-up evt val current-state]
  (swap-then!
   state
   #(assoc % :next-up-fn stream-or-finalize-request)
   #(stream-or-finalize-request state next-up evt val %)))

(defn- handle-request
  [state next-up _ [hdrs body :as request] _]
  (let [keepalive?   (keepalive-request? request)
        expects-100? (expecting-100? request)
        head?        (= "HEAD" (request :request-method))
        chunked?     (= :chunked body)
        next-up-fn
        (cond
         (not chunked?)  waiting-for-response
         expects-100?    awaiting-100-continue
         :else           stream-request-body)]

    (swap-then!
     state
     #(assoc %
        :keepalive? keepalive?
        :head?      head?
        :next-up-fn next-up-fn)
     (fn [current-state]
       (next-up :request request)
       (finalize-exchange state current-state)))))

(defn- mk-downstream-fn
  [state next-dn]
  (fn [evt val]
    (if (#{:response :body} evt)
      (let [current-state @state]
        ((.next-dn-fn current-state) state next-dn evt val current-state))
      (next-dn evt val))))

(defn- exchange
  [app dn conn]
  (let [state   (atom (initial-exchange-state conn))
        next-up (app (mk-downstream-fn state dn))]
    ;; The exchange event handler only processes events
    ;; related to the current HTTP exchange and will pass
    ;; on all other events.
    (fn [evt val]
      (if (#{:request :body} evt)
        (let [current-state @state]
          ((.next-up-fn current-state) state next-up evt val current-state))
        (next-up evt val)))))

(defrecord ConnectionState
    [upstream])

(defn- throw-http-pipelining-exception
  []
  (throw
   (Exception.
    (str "Not expecting an HTTP request right now, "
         "pipelining is not yet supported."))))

(defn proto
  [app]
  (fn [dn]
    (let [state (atom (ConnectionState. nil))]
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
             (let [next-up (exchange app dn state)]
               (swap! state #(assoc % :upstream next-up))
               (next-up evt val)))

           next-up
           (next-up evt val)

           :else
           (throw (Exception. "Not expecting a message right now."))))))))
