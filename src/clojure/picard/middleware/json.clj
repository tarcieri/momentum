(ns picard.middleware.json
  (:use
   picard.helpers)
  (:require
   [clojure.contrib.json :as json]
   [picard.middleware.body-buffer :as bbuf])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer
    ChannelBufferInputStream]
   [java.io
    InputStreamReader
    StringReader]))

(defprotocol IFromJson (from-json [obj]))

(extend-type ChannelBuffer
  IFromJson
  (from-json [^ChannelBuffer buf]
    (-> buf
        ChannelBufferInputStream.
        InputStreamReader.
        (json/read-json-from false false nil))))

(extend-type String
  IFromJson
  (from-json [str]
    (-> str
        StringReader.
        (json/read-json-from false false nil))))

(extend-type nil
  IFromJson
  (from-json [_]))

;; TODO: FIgure out a better way to handle mime types in general
(def content-types  #{"application/json"})

(defn- json-msg?
  [hdrs]
  (contains? content-types (content-type hdrs)))

(defn- encoder
  [stream]
  (defstream
    (request [[hdrs body :as request]]
      (if (json-msg? hdrs)
        (stream :request [hdrs (json/json-str body)])
        (stream :request request)))

    (response [[status hdrs body :as response]]
      (if (json-msg? hdrs)
        (stream :response [status hdrs (json/json-str body)])
        (stream :response response)))))

(defn- decoder
  [stream]
  (defstream
    (request [[hdrs body :as request]]
      (if (json-msg? hdrs)
        (stream :request [hdrs (from-json body)])
        (stream :request request)))

    (response [[status hdrs body :as response]]
      (if (json-msg? hdrs)
        (stream :response [status hdrs (from-json body)])
        (stream :response response)))

    (else [evt val]
      (stream evt val))))

(defn- wrap-stream
  [stream wrapper]
  (cond
   (= :encode wrapper)
   (encoder stream)

   (= :decode wrapper)
   (decoder stream)

   :else
   stream))

(def default-options
  {:upstream :decode :downstream :encode})

(defn- merge-opts
  [opts]
  (let [opts (merge default-options opts)
        valid-opts #{:encode :decode nil false}]
    (when-not (and (contains? valid-opts (opts :upstream))
                   (contains? valid-opts (opts :downstream)))
      (throw (Exception. "Invalid options: " opts)))
    opts))

(defn- buffer-opts
  [opts]
  (assoc opts
    :upstream   (= :decode (opts :upstream))
    :downstream (= :decode (opts :downstream))))

(defn json
  ([app] (json app {}))
  ([app opts]
     (let [opts (merge default-options opts)
           app  (bbuf/body-buffer app (buffer-opts opts))]
       (fn [downstream]
         (let [upstream (app (wrap-stream downstream (opts :downstream)))]
           (wrap-stream upstream (opts :upstream)))))))
