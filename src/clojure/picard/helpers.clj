(ns picard.helpers
  (:require [clojure.contrib.string :as string])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBuffers]
   [org.jboss.netty.util
    HashedWheelTimer
    TimerTask]
   [java.nio.charset
    Charset]
   [java.util.concurrent
    TimeUnit]
   [java.net
    URL]))

;; Conversions
(defn to-channel-buffer
  [str]
  (if (instance? ChannelBuffer str)
    str
    (ChannelBuffers/wrappedBuffer (.getBytes str))))

(defn response-status  [[status]]    status)
(defn response-headers [[_ headers]] headers)
(defn response-body    [[_ _ body]]  body)

(defn request-scheme
  [hdrs]
  (cond
   (= (hdrs :https) "on")
   "https"

   (= (hdrs "x-forwarded-ssl") "on")
   "https"

   (hdrs "x-forwarded-proto")
   (-> (string/split #"\s*,\s*" 2 (hdrs "x-forwarded-proto"))
       first
       string/lower-case)

   :else
   "http"))

(defn request-ssl?
  [hdrs]
  (= "https" (request-scheme hdrs)))

(defn request-url
  [[hdrs _]]
  (let [[host port] (if-let [host-hdr (hdrs "host")]
                      (string/split #":" 2 host-hdr)
                      (:local-addr hdrs))
        port (cond
              (nil? port) 80
              (number? port) port
              (string? port) (Integer/parseInt port))
        script-name (:script-name hdrs)
        path-info (:path-info hdrs)
        query-string-hdr (:query-string hdrs)
        query-string (if-not (empty? query-string-hdr) (str "?" query-string-hdr) "")
        file (str script-name path-info query-string)]
    ;; TODO: work out http/https
    (URL. "http" host port file)))

(defn body-size
  ([body] (body-size :body body))
  ([type body]
     (cond
      (= :response type)
      (body-size :body (nth body 2))

      (= :request type)
      (body-size :body (nth body 1))

      (not= :body type)
      (throw (Exception. "Not a valid body"))

      (instance? String body)
      (.length ^String body)

      (instance? ChannelBuffer body)
      (.readableBytes ^ChannelBuffer body)

      (or (nil? body) (= :chunked body))
      0

      :else
      (throw (Exception. "Not a valid body")))))

(defn request-done?
  [evt val]
  (or (and (= :request evt) (not= :chunked (val 1)))
      (and (= :body evt) (nil? val))
      (= :abort evt)))

(defn response-done?
  [evt val]
  (or (and (= :response evt) (not= :chunked (val 2)))
      (and (= :body evt) (nil? val))
      (= :abort evt)))

(defmacro build-stack
  "Builds an application stack from downstream to upstream. The last
  argument should be the end application and everything before that
  is middleware."
  [& items] `(-> ~@(reverse items)))

(defmacro defstream
  [& handlers]
  (let [evt (gensym) val (gensym)]
    `(fn [~evt ~val]
       ~(reduce
         (fn [else [evt* bindings & stmts]]
           (if (= 'else evt*)
             `(let [~bindings [~evt ~val]] ~@stmts)
             `(if (= ~(keyword evt*) ~evt)
                (let [~bindings [~val]] ~@stmts)
                ~else)))
         nil (reverse handlers))
       true)))

(defmacro defmiddleware
  "Define a simple middleware."
  [[state downstream upstream] app & handlers]
  (let [handlers    (apply hash-map handlers)
        state*      (gensym "state")
        upstream*   (gensym "upstream")
        downstream* (gensym "downstream")]

    `(fn [~downstream*]
       (let [~state* (atom {})
             ~upstream*
             (~app ~(if (handlers :downstream)
                      ;; If we're injecting a downstream handler,
                      ;; we need to do a bit of heavy lifting here
                      ;; in order to correctly get bindings.
                      `(fn [evt# val#]
                         ;; Call the downstream handler
                         ((or (@~state* ::dn)
                              (throw (Exception. "Can't call this yet")))
                          evt# val#))
                      ;; If there is no downstream handler, just
                      ;; pass the one we have in.
                      downstream*))

             ;; Setup the bindings that were passed in
             ~state      ~state*
             ~upstream   ~upstream*
             ~downstream ~downstream*]
         ;; Set the state to hold the downstream fn
         (reset! ~state* {::dn ~(handlers :downstream)})
         ;; Run any initialization code now that the bindings
         ;; have been setup.
         ~(handlers :initialize)
         ;; If there is a handler for finalize, then wrap the
         ;; upstream fn to catch the :done event.
         ~(if-let [finalize (handlers :finalize)]
            `(let [upstream# ~(handlers :upstream upstream*)
                   finalize# ~finalize]
               (fn [evt# val#]
                 (upstream# evt# val#)
                 (cond
                  (= :done evt#)
                  (finalize# true)

                  (= :abort evt#)
                  (finalize# false))))

            ;; There is no finalize handler, so ju
            (handlers :upstream upstream*))))))

(defn timer [] (HashedWheelTimer.))

(def global-timer (timer))

(defn timeout
  ([ms f] (timeout global-timer ms f))
  ([^HashedWheelTimer timer ms f]
     (.newTimeout timer
                  (reify TimerTask (run [_ _] (f)))
                  ms TimeUnit/MILLISECONDS)))
