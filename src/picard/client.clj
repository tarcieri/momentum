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
    HttpMethod
    HttpRequestEncoder
    HttpResponse
    HttpResponseDecoder
    HttpVersion]))

(defn- netty-bridge
  [state resp]
  (netty/message-or-channel-state-event-stage
   (fn [_ msg ch-state]
     (when msg
       (if (instance? HttpResponse msg)
         (resp :respond (netty-resp->resp msg))
         (if (.isLast msg)
           (and (resp :done nil) (println "Finishing the response"))
           (resp :body (.getContent msg)))))
     nil)))

(defn- chunked?
  [[_ _ body]]
  (= body :chunked))

(defn- waiting-for-response
  [_ _]
  (throw (Exception. "I'm not in a good place right now.")))

(defn- handling-initial-request
  [_ _ _ _]
  (throw (Exception. "Bro, I thought I told you to chill out")))

(defn- incoming-request
  [state evt [hdrs body :as val] args]
  (when-not (= :request evt)
    (throw (Exception. "Picard is confused... requests start with the head.")))

  ;; First, raise an exception if any further events are received
  (swap! state (fn [[_ args]] [handling-initial-request args]))

  (let [resp (args :resp) pool (args :pool) host (hdrs "host")]
    ;; Acquire a channel from the pool
    (pool/checkout-conn
     pool host
     (netty-bridge state resp)
     (fn [ch]
       ;; TODO - handle the error case
       (netty/on-complete
        (.write ch (req->netty-req val))
        (fn [_]
          (let [[_ {placeholder :ch}] @state]
            (swap! state (fn [[f args]] [f (assoc args :ch ch)]))
            ;; When the request is chunked and we sent a pause
            ;; event downstream
            (when (and (= :pending placeholder) (chunked? val))
              (resp :resume nil)))))))

    ;; If the body is chunked, then mark the request as paused
    ;; until a channel has been acquired.
    (when (chunked? val)
      (locking val
        (let [[_ {ch :ch}] @state]
          (when-not ch
            (swap! state (fn [f args] [f (assoc args :ch :pending)]))
            (resp :pause nil)))))))

(defn mk-proxy
  ([] (mk-proxy (pool/mk-pool)))
  ([pool]
     (fn [resp]
       (let [state (atom [incoming-request {:pool pool :resp resp}])]
         (fn [evt val]
           (let [[next-fn args] @state]
             (next-fn state evt val args)))))))
