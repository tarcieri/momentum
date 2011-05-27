(ns picard.test
  (:use
   [lamina.core]))

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
  [(merge default-headers hdrs {:request-method method :path-info path}) body])

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
        ch       (channel)]

    (swap! *responses* #(conj % (lazy-channel-seq ch 1000)))
    ;; Track the upstream
    (reset! upstream
            (*app* (fn [evt val]
                     (enqueue ch [evt val])
                     (when callback (callback evt val @upstream)))))
    ;; Send the request
    (@upstream :request (mk-request method path hdrs body))
    @upstream))

(defn HEAD   [& args] (apply request "HEAD"   args))
(defn GET    [& args] (apply request "GET"    args))
(defn POST   [& args] (apply request "POST"   args))
(defn PUT    [& args] (apply request "PUT"    args))
(defn DELETE [& args] (apply request "DELETE" args))

(defn last-response
  []
  (when-not *responses*
    (throw (Exception. "Need to wrap these tests with `with-app`")))

  (->> (last @*responses*)
       (filter (fn [[evt]] (= :response evt)))
       (map (fn [[_ val]] val))
       first))

(defn last-response-status  [] (when-let [r (last-response)] (first r)))
(defn last-response-headers [] (when-let [r (last-response)] (second r)))

;; Helpers
(defn includes?
  [map1 map2]
  (every? (fn [[k v]] (= v (map2 k))) map1))
