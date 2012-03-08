(ns momentum.http.client
  (:use
   momentum.core
   momentum.http.core
   momentum.util.atomic)
  (:require
   [momentum.net.client  :as net]
   [momentum.http.parser :as parser]
   [momentum.http.proto  :as proto])
  (:import
   [java.util.concurrent
    LinkedBlockingQueue]))

(def default-options
  {:keepalive 60
   :timeout   5})

(defn- mk-handler
  [dn up ^LinkedBlockingQueue queue opts]
  (reify proto/HttpHandler
    (handle-request-head [_ [hdrs body] _]
      ;; Put the request method onto the queue
      (.put queue (hdrs :request-method))

      (dn :message (encode-request-head hdrs))

      (when (buffer? body)
        (dn :message body)))

    (handle-request-chunk [_ chunk encoded? final?]
      (cond
       (and chunk encoded?)
       (dn :message (encode-chunk chunk))

       encoded?
       (dn :message (duplicate last-chunk))

       chunk
       (dn :message chunk))

      (when final?
        (dn :close nil)))

    (handle-request-message [_ msg]
      (dn :message msg))

    (handle-response-head [_ response final?]
      (up :response response)

      (when final?
        (dn :close nil)))

    (handle-response-chunk [_ chunk _ final?]
      (up :body chunk)

      (when final?
        (dn :close nil)))

    (handle-response-message [_ msg]
      (up :message msg))

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
     (= :request evt)
     (proto/request state val)

     (= :body evt)
     (proto/request-chunk state val)

     (= :message evt)
     (proto/request-message state val)

     ;; If a :close event is sent downstream, then the connection is
     ;; being explicitly closed (sometimes mid exchange). We don't
     ;; want to send an exception upstream once the upstream :close
     ;; event is received.
     (= :close evt)
     (do (proto/force-connection-closed state)
         (dn evt val))

     :else
     (dn evt val))))

(defn- response-parser
  "Wraps an upstream function with the basic HTTP parser."
  [queue f]
  (let [p (parser/response queue f)]
    (fn [evt val]
      (if (= :message evt)
        (p val)
        (f evt val)))))

(defn proto
  "Middleware that implements the HTTP client protocol."
  ([app] (proto app {}))
  ([app opts]
     (let [opts (merge default-options opts)]
       (fn [dn env]
         (let [queue (LinkedBlockingQueue.)
               state (proto/init opts)
               up    (app (mk-downstream state dn) env)]

           (proto/set-handler state (mk-handler dn up queue opts))

           (response-parser
            queue
            (fn [evt val]
              (cond
               (= :response evt)
               (proto/response state val)

               (= :body evt)
               (proto/response-chunk state val)

               (= :message evt)
               (proto/response-message state val)

               (= :close evt)
               (when (proto/connection-closed state)
                 (up evt val))

               (= :abort evt)
               (do (proto/cleanup state)
                   (up evt val))

               :else
               (up evt val)))))))))

(defn connect
  [app opts]
  (net/connect (proto app opts) opts))