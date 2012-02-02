(ns momentum.http.client
  (:use
   momentum.core
   momentum.core.atomic
   momentum.http.core)
  (:require
   [momentum.net.client :as net])
  (:import
   [java.net
    URI]
   [java.util.concurrent
    LinkedBlockingQueue]))

(declare
 handle-request
 handle-response)

(def HEAD (.intern "HEAD"))

(defrecord ExchangeState
    [upstream
     downstream
     next-up-fn
     next-dn-fn
     keepalive?
     chunked?
     head?
     expecting-100?
     body-until-close?
     queue
     opts])

(defn- mk-initial-state
  [downstream queue opts]
  (ExchangeState.
   nil              ;; upstream
   downstream       ;; downstream
   handle-response  ;; next-up-fn
   handle-request   ;; next-dn-fn
   true             ;; keepalive?
   false            ;; chunked?
   false            ;; head?
   false            ;; expecting-100?
   false            ;; body-until-close?
   queue            ;; queue
   opts))           ;; opts

(defn- not-expecting-message
  [evt val]
  (str "Not expecting a message right now: " [evt val]))

(defn- exchange-complete
  [_ evt val _]
  (throw (Exception. (not-expecting-message evt val))))

(defn- awaiting-response
  [_ evt val _]
  (throw (Exception. (not-expecting-message evt val))))

(defn- awaiting-request
  [_ evt val _]
  (throw (Exception. (not-expecting-message evt val))))

(defn- exchange-complete?
  [current-state]
  (= exchange-complete (.next-up-fn current-state)))

(defn- maybe-finalize-exchange
  [current-state]
  (let [upstream   (.upstream current-state)
        downstream (.downstream current-state)]
    ;; Maybe the upstream should be reset to nil
    (when (exchange-complete? current-state)
      (upstream :done nil)
      (downstream :close (not (.keepalive? current-state)))
      true)))

(defn- stream-or-finalize-response
  [state evt chunk current-state]
  (if chunk
    ((.upstream current-state) :body chunk)
    (swap-then!
     state
     (fn [current-state]
       (if (= awaiting-response (.next-dn-fn current-state))
         (assoc current-state
           :next-up-fn exchange-complete
           :next-dn-fn exchange-complete)
         (assoc current-state
           :next-up-fn awaiting-request)))
     (fn [current-state]
       ((.upstream current-state) :body nil)
       (maybe-finalize-exchange current-state)))))

(defn- handle-response
  [state evt response current-state]
  ;; Ensure that the response isn't too crazy
  (when-not (= :response evt)
    (throw (Exception. (str "Expecting :response but got: " [evt val]))))

  (when (and (is-100? response) (not (.expecting-100? current-state)))
    (throw (Exception. "Not expecting a 100 Continue response.")))

  (let [[status hdrs body] response
        body     (when-not (.head? current-state) body)
        response [status hdrs body]
        upstream (.upstream current-state)]
    (swap-then!
     state
     (fn [current-state]
       (let [until-close?
             (and (= :chunked body)
                  (not (.head? current-state))
                  (body-until-close? hdrs))
             keepalive?
             (and (.keepalive? current-state)
                  (keepalive-response? response (.head? current-state)))]
         (cond
          (is-100? response)
          (assoc current-state :expects-100? false)

          ;; If the response is chunked, then we need to stream the
          ;; body through
          (and (not (.head? current-state)) (= :chunked body))
          (assoc current-state
            :keepalive?        keepalive?
            :body-until-close? until-close?
            :next-up-fn        stream-or-finalize-response)

          ;; If the exchange is waiting for the response to complete
          ;; then just finish everything up
          (= awaiting-response (.next-dn-fn current-state))
          (do
            (assoc current-state
              :keepalive?        keepalive?
              :body-until-close? until-close?
              :next-dn-fn        exchange-complete
              :next-up-fn        exchange-complete))

          ;; Otherwise, just mark the request as alone
          :else
          (do
            (assoc current-state
              :keepalive?        keepalive?
              :body-until-close? until-close?
              :next-up-fn         awaiting-request)))))
     (fn [current-state]
       (upstream :response response)
       (maybe-finalize-exchange current-state)))))

(defn- stream-or-finalize-request
  [state evt chunk current-state]
  (when-not (= :body evt)
    (throw (Exception. "Expecting a :body event")))

  (if chunk
    (send-chunk (.downstream current-state) (.chunked? current-state) chunk)
    (swap-then!
     state
     (fn [current-state]
       (if (= awaiting-request (.next-up-fn current-state))
         (assoc current-state
           :next-up-fn exchange-complete
           :next-dn-fn exchange-complete)
         (assoc current-state
           :next-dn-fn awaiting-response)))
     (fn [current-state]
       (send-chunk (.downstream current-state) (.chunked? current-state) chunk)
       (maybe-finalize-exchange current-state)))))

(defn- handle-request
  [state evt request current-state]
  (when-not (= :request evt)
    (throw (Exception. "Expecting a :request event")))

  (let [[{method :request-method :as hdrs} body]  request
        method       (.intern method)
        hdrs         (or hdrs {})
        keepalive?   (keepalive-request? request)
        head?        (identical? HEAD method)
        chunked?     (and (= (hdrs "transfer-encoding") "chunked") (not head?))
        expects-100? (expecting-100? request)
        queue        (.queue current-state)]
    (.put queue method)
    (swap-then!
     state
     (fn [current-state]
       (assoc current-state
         :keepalive?     (and keepalive? (.keepalive? current-state))
         :chunked?       chunked?
         :head?          head?
         :expecting-100? expects-100?
         :next-dn-fn     (if (= :chunked body)
                           stream-or-finalize-request
                           awaiting-response)))
     (fn [current-state]
       (send-request (.downstream current-state) hdrs body)))))

(defn- mk-downstream-fn
  [state dn]
  (fn [evt val]
    (let [current-state @state]
      (cond
       (#{:request :body} evt)
       (if-let [next-dn-fn (.next-dn-fn current-state)]
         (next-dn-fn state evt val current-state)
         (throw (Exception. "Not currently expecting an event.")))

       (= :done evt)
       nil

       :else
       (dn evt val)))))

(defn proto
  [app opts]
  (fn [dn env]
    (let [queue   (LinkedBlockingQueue.)
          state   (atom (mk-initial-state dn queue opts))
          next-up (app (mk-downstream-fn state dn) env)]
      ;; Save off the upstream function
      (swap! state #(assoc % :upstream next-up))
      ;; Return the protocol upstream function
      (response-parser
       queue
       (fn [evt val]
         (let [current-state @state]
           (cond
            (#{:response :body} evt)
            (let [next-up-fn (.next-up-fn current-state)]
              (next-up-fn state evt val current-state))

            (= :open evt)
            (next-up evt (dissoc val :exchange-count))

            (= :close evt)
            (do
              ;; If we're streaming the body until close, simulate a
              ;; final chunk event
              (if (.body-until-close? current-state)
                (when-not (stream-or-finalize-response state :body nil current-state)
                  (throw (Exception. "Connection reset by peer")))
                (when (not= exchange-complete (.next-up-fn current-state))
                  (throw (Exception. "Connection reset by peer")))))

            (= :abort evt)
            (next-up evt val)

            :else
            (next-up evt val))))))))

(def client net/client)

(def default-options
  {:keepalive 60
   :timeout   5})

(def default-client (net/client {:pool {:keepalive 60}}))

(def release net/release)

(defn connect
  ([app opts]
     (connect default-client app opts))
  ([client app opts]
     (let [opts (merge default-options opts)]
       (net/connect client (proto app opts) opts))))

;; ==== Some higher level of abstraction APIs

(defn- uri-map
  [uri]
  (when uri
    (let [uri  (URI. uri)
          host (.getHost uri)
          port (.getPort uri)]
      {:host         host
       "host"        host
       :port         (if (> port 0) port)
       :path-info    (.getPath uri)
       :script-name  ""
       :query-string (or (.getQuery uri) "")})))

(defn- request*
  [method uri hdrs request-body]
  (let [hdrs (merge {:http-version [1 1] :request-method method} (uri-map uri) hdrs)
        resp (async-val)
        up   (atom nil)]
    (connect
     (fn [dn _]
       ;; A channel that is able to buffer 5 events before it invokes
       ;; the downstream fn with a pause.
       (let [ch (channel dn 5)]
         (fn [evt val]
           (cond
            (= :open evt)
            (cond
             (keyword? request-body)
             (throw (IllegalArgumentException. (format "Invalid body: %s" request-body)))

             (coll? request-body)
             (do
               (dn :request [hdrs :chunked])
               (reset! up (sink dn request-body)))

             :else
             (dn :request [hdrs (buffer request-body)]))

            (= :response evt)
            (let [[status hdrs body] val]
              ;; TODO: Handle upgrades
              (if (= :chunked body)
                (resp [status hdrs (seq ch)])
                (resp val)))

            (= :body evt)
            (if val
              (put ch val)
              (close ch))

            (#{:pause :resume} evt)
            (when-let [upstream @up]
              (upstream evt val))

            (= :abort evt)
            (abort resp val)

            :else
            (when-not (#{:done} evt)
              (println "Unhandled event " [evt val]))))))

     ;; Extract the connection options
     (select-keys hdrs [:host :port]))
    resp))

(defn request
  ([hdrs]
     (request* (hdrs :request-method) nil hdrs nil))

  ([method arg]
     (cond
      (string? arg)
      (request* method arg {} nil)

      (map? arg)
      (request* method nil arg nil)

      :else
      (throw (IllegalArgumentException.))))

  ([method arg1 arg2]
     (if (string? arg1)
       (if (map? arg2)
         (request* method arg1 arg2 nil)
         (request* method arg1 {} arg2))
       (request* method nil arg1 arg2)))

  ([method uri hdrs body]
     (request* method uri hdrs body)))

(defn HEAD   [& args] (apply request "HEAD"   args))
(defn GET    [& args] (apply request "GET"    args))
(defn POST   [& args] (apply request "POST"   args))
(defn PUT    [& args] (apply request "PUT"    args))
(defn DELETE [& args] (apply request "DELETE" args))
