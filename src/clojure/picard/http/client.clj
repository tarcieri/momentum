(ns picard.http.client
  (:use
   picard.utils
   picard.http.core)
  (:require
   [picard.net.client :as net])
  (:import
   [picard.http
    HttpClientCodec]))

(declare
 handle-request
 handle-response)

(defrecord ExchangeState
    [upstream
     downstream
     next-up-fn
     next-dn-fn
     keepalive?
     chunked?
     head?
     expecting-100?
     opts])

(defn- mk-initial-state
  [downstream opts]
  (ExchangeState.
   nil             ;; upstream
   downstream      ;; downstream
   handle-response ;; next-up-fn
   handle-request  ;; next-dn-fn
   true            ;; keepalive?
   false           ;; chunked?
   false           ;; head?
   false           ;; expecting-100?
   opts))          ;; opts

(defn- exchange-complete
  [_ _ _ _]
  (throw (Exception. "The HTTP exchange is complete")))

(defn- awaiting-response
  [_ _ _ _]
  (throw (Exception. "Not expecting a message right now.")))

(defn- awaiting-request
  [_ _ _ _]
  (throw (Exception. "Not expecting a message right now.")))

(defn- exchange-complete?
  [current-state]
  (= exchange-complete (.next-up-fn current-state)))

(defn- maybe-finalize-exchange
  [state current-state]
  (let [upstream   (.upstream current-state)
        downstream (.downstream current-state)]
    ;; Maybe the upstream should be reset to nil
    (when (exchange-complete? current-state)
      (upstream :done nil)
      (downstream :close (not (.keepalive? current-state))))))

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
       (maybe-finalize-exchange state current-state)))))

(defn- handle-response
  [state evt response current-state]
  ;; Ensure that the response isn't too crazy
  (when-not (= :response evt)
    (throw (Exception. (str "Expecting :response but got: " [evt val]))))

  (when (and (is-100? response) (not (.expecting-100? current-state)))
    (throw (Exception. "Not expecting a 100 Continue response.")))

  (let [[status hdrs body] response
        upstream (.upstream current-state)]
    (swap-then!
     state
     (fn [current-state]
       (let [keepalive?
             (and (.keepalive? current-state)
                  (keepalive-response? response))]
         (cond
          (is-100? response)
          (assoc current-state :expects-100? false)

          ;; If the response is chunked, then we need to stream the
          ;; body through
          (and (not (.head? current-state)) (= :chunked body))
          (assoc current-state
            :keepalive? keepalive?
            :next-up-fn stream-or-finalize-response)

          ;; If the exchange is waiting for the response to complete
          ;; then just finish everything up
          (= awaiting-response (.next-dn-fn current-state))
          (do
            (assoc current-state
              :keepalive? keepalive?
              :next-dn-fn exchange-complete
              :next-up-fn exchange-complete))

          ;; Otherwise, just mark the request as alone
          :else
          (do
            (assoc current-state
             :keepalive? keepalive?
             :next-up-fn awaiting-request)))))
     (fn [current-state]
       (upstream :response response)
       (maybe-finalize-exchange state current-state)))))

(defn- stream-or-finalize-request
  [state evt chunk current-state]
  (throw (Exception. "Not implemented")))

(defn- handle-request
  [state evt request current-state]
  (when-not (= :request evt)
    (throw (Exception. "Expecting a :request event")))

  (let [[hdrs body]  request
        hdrs         (or hdrs {})
        keepalive?   (keepalive-request? request)
        head?        (= "HEAD" (hdrs :request-method))
        chunked?     (= :chunked body)
        expects-100? (expecting-100? request)]
    (swap-then!
     state
     (fn [current-state]
       (assoc current-state
         :keepalive?     (and keepalive? (.keepalive? current-state))
         :chunked?       chunked?
         :head?          head?
         :expecting-100? expects-100?
         :next-dn-fn     (if-not chunked?
                           awaiting-response
                           stream-or-finalize-request)))
     (fn [current-state]
       (let [dn (.downstream current-state)]
         (dn :message (request->netty-request hdrs body)))))))

(defn- mk-downstream-fn
  [state dn]
  (fn [evt val]
    (let [current-state @state]
      (cond
       (#{:request :body} evt)
       (if-let [next-dn-fn (.next-dn-fn current-state)]
         (next-dn-fn state evt val current-state)
         (throw (Exception. "Not currently expecting an event.")))

       :else
       (dn evt val)))))

(defn proto
  [app opts]
  (fn [dn]
    (let [state (atom (mk-initial-state dn opts))
          next-up (app (mk-downstream-fn state dn))]
      ;; Save off the upstream function
      (swap! state #(assoc % :upstream next-up))
      ;; Return the protocol upstream function
      (fn [evt val]
        (let [current-state @state]
          (cond
           (#{:response :body} evt)
           (let [next-up-fn (.next-up-fn current-state)]
             (next-up-fn state evt val current-state))

           (= :close evt)
           (when (not= exchange-complete (.next-up-fn current-state))
             (throw (Exception. "Connection reset by peer")))

           (= :abort evt)
           (next-up evt val)

           :else
           (next-up evt val)))))))

(defn- http-pipeline
  [p _]
  (doto p
    (.addLast "codec" (HttpClientCodec.))))

(def client net/client)

(def default-options
  {:keepalive 60
   :timeout   5})

(defn- merge-opts
  [opts]
  (merge default-options opts {:pipeline-fn http-pipeline}))

(def release net/release)

(defn connect
  ([app opts]
     (let [opts (merge-opts opts)]
       (net/connect (proto app opts) opts)))
  ([client app opts]
     (let [opts (merge-opts opts)]
       (net/connect client (proto app opts) opts))))
