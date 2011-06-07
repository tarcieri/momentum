(ns picard.test
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer]
   [java.nio.charset
    Charset]
   [java.util.concurrent
    LinkedBlockingQueue
    TimeUnit]))

(declare *app* *responses*)

(def default-headers
  {:script-name "" :http-version [1 1] :server "picard.test"
   "host" "example.org"})

(defn- extract-verbatim
  [into [el & args]]
  [(conj into el) args])

(defn- extract-if
  [f default into [el & args]]
  (if (f el)
    [(conj into el) args]
    [(conj into default) (cons el args)]))

(defn- process-request-args
  [args]
  (->> [[] args]
       (apply extract-verbatim)                  ;; method
       (apply extract-verbatim)                  ;; path
       (apply extract-if map? {})                ;; headers
       (apply extract-if #(or (string? %)        ;; body
                              (keyword? %)) nil) ;; body
       (apply extract-verbatim)                  ;; callback
       first))

(defn- mk-request
  [method path hdrs body]
  [(merge default-headers
          hdrs
          {:request-method method
           :path-info      path
           :remote-addr    ["localhost" 12345]})
   body])

(defmacro with-app
  [app & stmts]
  `(binding [*app* ~app *responses* (atom [])]
     ~@stmts))

(defn request
  [& args]
  (when-not *app*
    (throw (Exception. "Need to wrap these tests with `with-app`")))

  (let [[method path hdrs body callback] (process-request-args args)
        upstream (atom nil)
        queue    (LinkedBlockingQueue.)]

    (swap! *responses* #(conj % (atom [[] queue])))
    ;; Track the upstream
    (reset! upstream
            (*app* (fn [evt val]
                     (.put queue [evt val])
                     (when callback (callback evt val @upstream)))))
    ;; Send the request
    (@upstream :request (mk-request method path hdrs body))
    @upstream))

(defn HEAD   [& args] (apply request "HEAD"   args))
(defn GET    [& args] (apply request "GET"    args))
(defn POST   [& args] (apply request "POST"   args))
(defn PUT    [& args] (apply request "PUT"    args))
(defn DELETE [& args] (apply request "DELETE" args))

(defn- normalize-body
  [[_ body]]
  (if (instance? ChannelBuffer body)
    [:body (.toString body (Charset/defaultCharset))]
    [:body  body]))

(defn- normalize-response
  [[_ [status hdrs body] :as response]]
  (if (instance? ChannelBuffer body)
    [:response [status hdrs (.toString body (Charset/defaultCharset))]]
    response))

(defn- ensure-scoped []
  (when-not *responses*
    (throw (Exception. "Need to wrap these tests with `with-app`"))))

(defn last-exchange
  []
  (ensure-scoped)
  (last @*responses*))

(defn exchange-events
  ([ex] (exchange-events ex 1000))
  ([ex timeout]
     (let [[cached queue] @ex
           f (fn seq []
               (lazy-seq
                (when-let [el (.poll queue timeout TimeUnit/MILLISECONDS)]
                  (swap! ex (fn [[cached queue]]
                              [(conj cached el) queue]))
                  (cons el (seq)))))]
       (lazy-cat cached (f)))))

(defn received-exchange-events [ex] (exchange-events ex 0))

(defn last-response
  []
  (->> (exchange-events (last-exchange))
       (filter (fn [[evt]] (= :response evt)))
       (map (comp second normalize-response))
       first))

(defn last-response-status  [] (when-let [r (last-response)] (first r)))
(defn last-response-headers [] (when-let [r (last-response)] (second r)))
(defn last-response-body    [] (when-let [r (last-response)] (nth r 2)))

(defn last-body-chunks
  []
  (->> (exchange-events (last-exchange))
       (filter (fn [[evt]] (= :body evt)))
       (map (comp second normalize-body))))

;; Helpers
(defn includes?
  [map1 map2]
  (every? (fn [[k v]] (= v (map2 k))) map1))
