(ns picard.test
  (:use
   [picard.utils])
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
  {:script-name  ""
   :query-string ""
   :http-version [1 1]
   :server-name  "picard.test"
   :remote-addr  ["127.0.0.1" 12345]
   :local-addr   ["127.0.0.1" 80]
   "host"        "example.org"})

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
          {:request-id (gen-uuid)}
          hdrs
          {:request-method method
           :path-info      path})
   body])

(defmacro with-app
  [app & stmts]
  `(binding [*app* ~app *responses* (atom [])]
     ~@stmts))

(defn- mk-downstream
  [state]
  (fn [evt val]
    (let [current-state @state
          upstream (current-state ::upstream)]

      ;; Handle an exception event
      (when (= :abort evt)
        (when (not= :finalized (current-state ::state))
          (swap! state #(assoc % ::state :finalized))
          (upstream evt val)))

      ;; Handle the response is finished
      (when (or (and (= :response evt) (not (= :chunked (val 2))))
                (and (= :body evt) (nil? val)))
        (cond
         (= :pending-response (current-state ::state))
         (do (swap! state #(assoc % ::state :finalized))
             (upstream :done nil))

         (= :initializing (current-state ::state))
         (swap! state #(assoc % ::state :pending-request)))))))

(defn- mk-upstream
  [state upstream]
  (fn [evt val]
    (let [current-state @state]
      (cond
       (= :abort evt)
       (do (swap! state #(assoc % ::state :finalized))
           (upstream evt val))

       (or (and (= :request evt) (not (= :chunked (val 1))))
           (and (= :body evt) (nil? val)))
       (do
         (upstream evt val)
         ;; The state might have changed
         (let [current-state @state]
           (cond
            (= :pending-request (current-state ::state))
            (do (swap! state #(assoc % ::state :finalized))
                (upstream :done nil))

            (= :initializing (current-state ::state))
            (swap! state #(assoc % ::state :pending-response)))))

       :else
       (upstream evt val)))))

(defn request
  [& args]
  (when-not *app*
    (throw (Exception. "Need to wrap these tests with `with-app`")))

  (let [[method path hdrs body callback] (process-request-args args)
        state (atom {::state :initializing})
        queue (LinkedBlockingQueue.)]

    (swap! *responses* #(conj % (atom [[] queue])))
    ;; Track the upstream
    (let [downstream (mk-downstream state)
          upstream* (*app* (fn [evt val]
                             (.put queue [evt val])
                             (let [upstream (@state ::upstream)]
                               (when callback (callback evt val upstream))
                               (downstream evt val))))
          upstream (mk-upstream state upstream*)]
      (swap! state #(assoc % ::upstream upstream))

      ;; Send the request
      (upstream :request (mk-request method path hdrs body))
      upstream)))

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
       (filter (fn [[evt val]] (and (= :response evt) (not= 100 (first val)))))
       (map (comp second normalize-response))
       first))

(defn last-response-status  [] (when-let [r (last-response)] (first r)))
(defn last-response-body    [] (when-let [r (last-response)] (nth r 2)))

(defn last-response-headers
  ([] (when-let [r (last-response)] (second r)))
  ([key]
     (when-let [hdrs (last-response-headers)]
       (hdrs key))))

(defn last-body-chunks
  []
  (->> (exchange-events (last-exchange))
       (filter (fn [[evt val]] (and (= :body evt) val)))
       (map (comp second normalize-body))))

(defn received-event?
  [f]
  (some #(apply f %) (exchange-events (last-exchange))))

;; Helpers
(defn continue?
  [ex]
  (let [evts (exchange-events ex)
        real-evts (filter (fn [[evt]] (and (not= :pause evt) (not= :resume evt))) evts)
        [evt [status]] (first real-evts)]
    (and (= :response evt) (= 100 status))))

(defn includes?
  [map1 map2]
  (every? (fn [[k v]] (= v (map2 k))) map1))
