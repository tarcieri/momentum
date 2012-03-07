(ns momentum.http.proto
  (:use
   momentum.core
   momentum.util.atomic)
  (:import
   [java.nio.channels
    ClosedChannelException]))

;; TODO:
;; - When pipelining, a no keepalive prevents further requests
;; - Add more checks for invalid header / body combinations
;;   - Transfer-Encoding w/o :chunked (unless HEAD request)
;;   - Content-Length w/o body or :chunked
;;   - :upgraded w/o upgrade handshake
;; - Handle CONNECT requests
;; - Better 100 continue semantics

(def http-1-0    [1 0])
(def http-1-1    [1 1])
(def empty-queue clojure.lang.PersistentQueue/EMPTY)

(defprotocol HttpHandler
  (handle-request-head       [_ msg final?])
  (handle-request-chunk      [_ chunk encoded? final?])
  (handle-request-message    [_ msg])
  (handle-response-head      [_ msg final?])
  (handle-response-chunk     [_ chunk encoded? final?])
  (handle-response-message   [_ msg])
  (handle-exchange-timeout   [_])
  (handle-keep-alive-timeout [_])
  (handle-abort              [_ err]))

(defrecord HttpConnection
    [handler
     keep-alive?
     upgraded?
     continue-acked?
     request-body
     request-bytes-remaining
     response-body
     response-bytes-remaining
     response-queue
     timeout
     options])

(declare mk-keep-alive-timer)

(defn init
  [options]
  (atom
   (HttpConnection.
    nil         ;; handler
    true        ;; keep-alive?
    false       ;; upgraded?
    false       ;; continue-acked?
    nil         ;; request-body
    0           ;; request-bytes-remaining
    nil         ;; response-body
    0           ;; response-bytes-remaining
    empty-queue ;; response-queue
    nil         ;; timeout
    options)))  ;; options

(defn- cleanup*
  [^HttpConnection conn]
  (cancel-timeout (.timeout conn)))

(defn cleanup
  [state]
  (get-swap-then!
   state
   (constantly nil)
   (fn [^HttpConnection conn _]
     (cleanup* conn))))

(defn set-handler
  "Sets the protocol handler as well as an initial keep alive timer"
  [state handler]
  (let [conn    ^HttpConnection @state
        timeout (mk-keep-alive-timer state (.options conn))]
    (swap! state #(assoc % :handler handler :timeout timeout))))

(defn- lower-case
  [^String s]
  (and s (.toLowerCase s)))

(defn- status-expects-body?
  [status]
  (and (<= 200 status)
       (not= 204 status)
       (not= 304 status)))

(defn- keepalive-request?
  [[{version :http-version connection "connection"} body]]
  (let [connection (lower-case connection)]
    (if (or (nil? version) (= http-1-1 version))
      (and (not (#{"close" "upgrade"} connection))
           (not (= :upgraded body)))
      (= "keep-alive" connection))))

(defn- keepalive-response?
  [[status {version           :http-version
            connection        "connection"
            content-length    "content-length"
            transfer-encoding "transfer-encoding"}] head?]
  (and (if (= http-1-0 version)
         (= "keep-alive" (lower-case connection))
         (not= "close" (lower-case connection)))
       (or head? content-length
           (= "chunked" (lower-case transfer-encoding))
           (not (status-expects-body? status)))))

(defn- expects-100?
  [[{version :http-version expect "expect"}]]
  (cond
   (and version (not= http-1-1 version))
   false

   (not expect)
   false

   (string? expect)
   (= "100-continue" (lower-case expect))

   (vector? expect)
   (some #(= "100-continue" (lower-case %)) expect)

   :else
   false))

(defn- content-length
  [hdrs]
  (if-let [content-length (hdrs "content-length")]
    (if (number? content-length)
      (long content-length)
      (Long. (str content-length)))
    0))

(defn- depth
  [^HttpConnection conn]
  (count (.response-queue conn)))

;; TODO: Figure out how to unify all of these

(defn- processing-exchange?
  [^HttpConnection conn]
  (or (.request-body conn)
      (.response-body conn)
      (> (depth conn) 0)))

(defn- connection-closable?
  [^HttpConnection conn]
  (or (.upgraded? conn)
      (and (not (.request-body conn))
           (or (not (.response-body conn))
               (= :until-close (.response-body conn)))
           (or (not (.response-queue conn))
               (= (depth conn) 0)))))

(defn- connection-waiting?
  [^HttpConnection conn]
  (and (not (.request-body conn))
       (not (.response-body conn))
       (.response-queue conn)
       (= (depth conn) 0)))

(defn- connection-final?
  "Returns true if the connection should be closed."
  [^HttpConnection conn]
  (and (not (.keep-alive? conn))
       (not (.request-body conn))
       (not (.response-body conn))
       (not (.response-queue conn))))

(defn- mk-exchange-timer
  [state opts]
  (schedule-timeout
   (* (opts :timeout 5) 1000)
   #(get-swap-then!
     state
     (fn [conn]
       (assoc conn
         :keep-alive?    false
         :request-body   nil
         :response-body  nil
         :response-queue nil))
     (fn [^HttpConnection old-conn ^HttpConnection new-conn]
       (when-not (connection-final? old-conn)
         (handle-exchange-timeout (.handler old-conn)))))))

(defn- mk-keep-alive-timer
  [state opts]
  (schedule-timeout
   (* (opts :keepalive 60) 1000)
   #(get-swap-then!
     state
     (fn [^HttpConnection conn]
       (cond
        ;; The connection was already closed somehow. In this case,
        ;; don't do anything.
        (not (.keep-alive? conn))
        conn

        ;; A request is in progress
        (or (.request-body conn)
            (.response-body conn)
            (< 0 (count (.response-queue conn))))
        conn

        :else
        (assoc conn :keep-alive? false :response-queue nil)))
     (fn [^HttpConnection old-conn ^HttpConnection new-conn]
       ;; If the timeout caused the connection to transition into a
       ;; final state, notify the handler; otherwise, just discard the
       ;; timer.
       (when (and (not (connection-final? old-conn)) (connection-final? new-conn))
         (handle-keep-alive-timeout (.handler old-conn)))))))

(defn- bump-exchange-timer
  [state ^HttpConnection conn]
  (let [timeout (mk-exchange-timer state (.options conn))]
    (get-swap-then!
     state
     (fn [conn]
       (if (processing-exchange? conn)
         (assoc conn :timeout timeout)
         conn))
     (fn [^HttpConnection old-conn ^HttpConnection new-conn]
       (cancel-timeout
        (if (= (.timeout new-conn) timeout)
          (.timeout old-conn) timeout))))))

(defn- bump-keep-alive-timer
  [state ^HttpConnection  conn]
  (let [timeout (mk-keep-alive-timer state (.options conn))]
    (get-swap-then!
     state
     (fn [conn]
       (if (connection-waiting? conn)
         (assoc conn :timeout timeout)
         conn))
     (fn [^HttpConnection old-conn ^HttpConnection new-conn]
       (cancel-timeout
        (if (= (.timeout new-conn) timeout)
          (.timeout old-conn) timeout))))))

(defn- bump-timer
  [state ^HttpConnection conn]
  (cond
   (.upgraded? conn)
   (cancel-timeout (.timeout conn))

   (processing-exchange? conn)
   (bump-exchange-timer state conn)

   (connection-waiting? conn)
   (bump-keep-alive-timer state conn)


   :else
   (cancel-timeout (.timeout conn))))

(defn request
  [state [hdrs body :as request]]
  (let [keep-alive?     (keepalive-request? request)
        bytes-remaining (content-length hdrs)

        request-body
        (when (= :chunked body)
          (if (= "chunked" (lower-case (hdrs "transfer-encoding")))
            :chunked :streaming))

        requirement
        (cond
         (= "HEAD" (hdrs :request-method))
         :head

         (expects-100? request)
         :continue

         (= :upgraded body)
         :upgrade)]

    (get-swap-then!
     state
     (fn [^HttpConnection conn]
       (when conn
         (when (.request-body conn)
           (throw (Exception. "Still handling previous request body")))

         (if (.keep-alive? conn)
           (assoc conn
             :keep-alive?    (and (.keep-alive? conn) keep-alive?)
             :request-body   request-body
             :response-queue (conj (.response-queue conn) requirement)
             :request-bytes-remaining bytes-remaining)
           ;; Otherwise, the connection will be closed after all
           ;; accepted requests have been handled, so just discard the
           ;; request.
           conn)))
     (fn [^HttpConnection old-conn ^HttpConnection new-conn]
       (when old-conn
         (bump-timer state new-conn)
         (when (.keep-alive? old-conn)
           (let [handler (.handler new-conn)]
             (handle-request-head handler request false))))))))

(defn response
  [state [status hdrs body :as response]]
  (let [hdrs            (or hdrs {})
        chunked?        (= "chunked" (lower-case (hdrs "transfer-encoding")))
        bytes-remaining (content-length hdrs)
        response-body   (when (= :chunked body)
                          (cond
                           chunked?
                           :chunked

                           (= :chunked body)
                           (if (hdrs "content-length")
                             :streaming
                             :until-close)))]

    (when (and (= :upgraded body) (not= 101 status))
      (throw (Exception. "A body of :upgraded requires a status of 101.")))

    (when (not (or (status-expects-body? status) (nil? body) (= :upgraded body)))
      (throw (Exception. (str status " responses must not include a body."))))

    (get-swap-then!
     state
     (fn [^HttpConnection conn]
       (when conn
         (when (and (.response-body conn) (not= :pending (.response-body conn)))
           (throw (Exception. "Still handling previous response body")))

         (let [requirements (peek (.response-queue conn))]
           (cond
            (= 100 status)
            (if (or (.continue-acked? conn) (not= :continue requirements))
              (throw (Exception. "Not expecitng a 100 Continue response."))
              (assoc conn
                :continue-acked? true
                :response-body   :pending))

            (= 101 status)
            (if (= :upgrade requirements)
              (assoc conn
                :keep-alive?   false
                :request-body  :upgraded
                :response-body :upgraded
                :upgraded?     true)
              (throw (Exception. "Not upgrading a connection")))

            :else
            (let [keep-alive?
                  (and (.keep-alive? conn)
                       (not= :upgrade requirements)
                       (keepalive-response? response (= :head requirements)))]
              (assoc conn
                :response-bytes-remaining bytes-remaining
                :response-queue (when keep-alive? (pop (.response-queue conn)))
                :response-body  (and (not= :head requirements) response-body)
                :keep-alive?    keep-alive?))))))
     (fn [^HttpConnection old-conn ^HttpConnection new-conn]
       (when old-conn
         (bump-timer state new-conn)
         (let [handler (.handler new-conn)
               head?   (= :head (peek (.response-queue old-conn)))]

           (handle-response-head
            handler [status hdrs (when-not head? body)]
            (connection-final? new-conn))))))))

(defn request-chunk
  [state chunk]
  (get-swap-then!
   state
   (fn [^HttpConnection conn]
     (when conn
       (cond
        (buffer? chunk)
        (cond
         (= :chunked (.request-body conn))
         conn

         (= :streaming (.request-body conn))
         (let [bytes-remaining (- (.request-bytes-remaining conn) (remaining chunk))]
           (when (< bytes-remaining 0)
             (throw (Exception. "Sending more data than expected")))

           (assoc conn
             :request-bytes-remaining bytes-remaining
             :request-body (when (< 0 bytes-remaining) (.response-body conn))))

         :else
         (throw (Exception. "Not expecting a chunk at this time")))

        (nil? chunk)
        (assoc conn :request-body nil)

        :else
        (throw (Exception. "Not a valid body chunk type")))))
   (fn [^HttpConnection old-conn ^HttpConnection new-conn]
     (when old-conn
       (bump-timer state new-conn)

       (let [handler (.handler new-conn)]
         (handle-request-chunk
          handler
          chunk (= :chunked (.request-body old-conn))
          (connection-final? new-conn)))))))

(defn response-chunk
  [state chunk]

  (get-swap-then!
   state
   (fn [^HttpConnection conn]
     (when conn
       (cond
        (buffer? chunk)
        (cond
         (#{:chunked :until-close} (.response-body conn))
         conn

         (= :streaming (.response-body conn))
         (let [bytes-remaining (- (.response-bytes-remaining conn) (remaining chunk))]
           (when (< bytes-remaining 0)
             (throw (Exception. "Sending more data than specified")))

           (assoc conn
             :response-bytes-remaining bytes-remaining
             :response-body (when (< 0 bytes-remaining) (.response-body conn))))

         :else
         (throw (Exception. "Not expecting a chunk at this time")))

        (nil? chunk)
        (assoc conn :response-body nil)

        :else
        (throw (Exception. "Not a valid body chunk type")))))

   (fn [^HttpConnection old-conn ^HttpConnection new-conn]
     (when old-conn
       (bump-timer state new-conn)

       (let [handler (.handler new-conn)]

         ;; Then invoke the specific handler
         (handle-response-chunk
          (.handler new-conn)
          chunk (= :chunked (.response-body old-conn))
          (connection-final? new-conn)))))))

(defn request-message
  [state msg]
  (when-let [conn ^HttpConnection @state]
    (if (.upgraded? conn)
      (handle-request-message (.handler conn) msg)
      (throw (Exception. "Connection was not upgraded")))))

(defn response-message
  [state msg]
  (when-let [conn ^HttpConnection @state]
    (if (.upgraded? conn)
      (handle-response-message (.handler conn) msg)
      (throw (Exception. "Connection was no upgraded")))))

(defn force-connection-closed
  [state]
  (get-swap-then!
   state
   (constantly nil)
   (fn [^HttpConnection conn _]
     (when conn
       (cleanup* conn)))))

(defn connection-closed
  [state]
  (get-swap-then!
   state
   (constantly nil)
   (fn [^HttpConnection conn _]
     (if conn
       (do
         (cleanup* conn)
         (if (connection-closable? conn)
           (do
             (when (= :until-close (.response-body conn))
               (handle-response-chunk
                (.handler  conn)
                nil false true))
             true)
           (let [handler (.handler conn)]
             (handle-abort handler (ClosedChannelException.))
             false)))
       true))))
