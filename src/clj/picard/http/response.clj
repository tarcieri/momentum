(ns picard.http.response
  (:use
   picard.core))

(defmulti responder (fn [type & args] type))

(defmethod responder :default
  [type _ _ _]
  (throw (Exception. (str "Unsupported format: " type))))

(defmethod responder :status
  [_ status hdrs body]
  [body hdrs nil])

(defn- normalize-response
  [status hdrs body]
  (cond
   (or (hdrs "content-length") (hdrs "transfer-encoding"))
   [status hdrs body]

   (coll? body)
   [status (assoc hdrs "transfer-encoding" "chunked") body]

   :else
   [status (assoc hdrs "content-length" (str (remaining body))) body]))

(defn- normalize-body
  [body]
  (if (coll? body)
    body (buffer body)))

(defmethod responder :text
  [_ status hdrs body]
  (let [hdrs (merge {"content-type" "text/plain"} hdrs)
        body (if (coll? body) body (buffer body))]
   (normalize-response status hdrs body)))

(defn respond
  [type body & opts]
  (let [hdrs   (apply hash-map opts)
        status (hdrs :status 200)
        hdrs   (merge (dissoc hdrs :headers :status) (hdrs :headers))]
    (responder type (hdrs :status 200) hdrs body)))
