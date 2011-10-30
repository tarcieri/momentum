(ns picard.http.router
  (:use
   picard.core))

(def SEGMENT     #"[^/]+")
(def CAPTURE     #"([^/]+)")
(def GLOB        #"(.*?)")
(def PLACEHOLDER #"^(:|\*)([a-z0-9*+!-+?]+)$")

(defprotocol Route
  (^{:private true} route [this state dn method path hdrs body]))

(extend-type Object
  Route
  (route [this state dn method path hdrs body]
    (let [upstream (this dn)]
      (reset! state upstream)
      (upstream :request [hdrs body])
      upstream)))

(defn- re-match
  [re s]
  (when-let [match (re-find re s)]
    (if (string? match)
      (list match)
      match)))

(defn- placeholder
  [s]
  (when-let [p (second (re-find PLACEHOLDER s))]
    (keyword p)))

(defn- parse
  [path]
  (map
   (fn [segment]
     (if-let [[_ type name :as match] (re-find PLACEHOLDER segment)]
       (if (= ":" type)
         {:pattern CAPTURE :name (keyword name)}
         {:pattern GLOB    :name (keyword name)})
       {:pattern segment}))
   (re-seq SEGMENT path)))

(defn- finalize-path
  [{pattern :pattern :as path}]
  (assoc path :pattern (re-pattern (str "^" pattern "/*$"))))

(defn- compile-path
  [segments]
  (finalize-path
   (reduce
    (fn [{:keys [pattern names]} {p :pattern name :name}]
      {:pattern (str pattern "/+" p)
       :names   (if name (conj names name) names)})
    {:pattern "" :names []} segments)))

(defn- compile-route
  [expected-method {:keys [pattern names]} target]
  (reify Route
    (route [_ state dn method path hdrs body]
      ;; Only match the method if there is a method requirement
      (when (or (nil? expected-method) (= expected-method method))
        ;; Match the path
        (when-let [[matched & vals] (re-match pattern path)]
          ;; There was a match, deconstruct the values
          (let [params (zipmap names vals)
                path   (subs path (count matched))
                hdrs   (assoc hdrs
                         :params      params
                         :path-info   path
                         :script-name matched)]
            (route target state dn method path hdrs body)))))))

(defn match
  ([path target] (match nil path target))
  ([method path target]
     (compile-route method (compile-path (parse path)) target)))

(defn- not-found
  [dn]
  (fn [evt val]
    (when (= :request evt)
      (dn :response [404 {"content-length" "9"} (buffer "Not found")]))))

(defn- handle-request
  [state routes dn [{path :path-info method :request-method :as hdrs} body]]
  (loop [[r & more] routes]
    (when-not (route r state dn method path hdrs body)
      (if more
        (recur more)
        (let [upstream (not-found dn)]
          (reset! state upstream)
          (upstream :request [hdrs body]))))))

(defn routing
  [& routes]
  (let [routes (flatten routes)]
    (fn [dn]
      (let [state (atom nil)]
        (fn [evt val]
          (when (= :abort evt)
            (.printStackTrace val))
          (if (= :request evt)
            (handle-request state routes dn val)
            (if-let [upstream @state]
              (upstream evt val)
              (throw (Exception. "Invalid state")))))))))

;; (defn zomg
;;   []
;;   (fn [dn]
;;     (routing
;;      (with-scope "/namespace"
;;        (GET "/zomg" some-app)
;;        (GET "/"     other-app)))))

;; (defroutes zomg)

;; (map "/socket.io" my-socketio-app)

;; (defroutes my-app
;;   (GET "/socket.io" zomg))

;; (defroute :GET "/"
;;   [hdrs body]
;;   )

;; (def routes
;;   (map-routes
;;    (fn [map]
;;      (map :GET  "/" some-app)
;;      (map :POST "/" some-other))))

;; (defn socketio
;;   [opts]
;;   (routing
;;    (GET  "/:version/:transport/:sid" (connection opts))
;;    (POST "/:version" (handshake opts))))
