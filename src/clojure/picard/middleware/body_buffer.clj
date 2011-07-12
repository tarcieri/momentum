(ns picard.middleware.body-buffer
  (:use
   [picard.helpers])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer
    CompositeChannelBuffer]
   [java.util
    LinkedList]))

(def default-options
  {:upstream   true
   :downstream true
   :max-size   (* 100 1024 1024)})

(def request-entity-too-large
  [413 {"content-length"     "0"
        "x-picard-error-msg" "Request body too large."} nil])

(def server-error
  [500 {"content-length"     "0"
        "x-picard-error-msg" "Response body too large"} nil])

(def string-chunk-err-msg
  (str "Non ChannelBuffer chunks is "
       "not implemented yet"))

(defn- handle-chunk
  [state chunk max-size]
  (when-not (instance? ChannelBuffer chunk)
    (throw (Exception. string-chunk-err-msg)))

  (let [{error    :error-fn
         order    :order
         aborted? :aborted?
         size     :size
         ll       :ll}
        (swap!
         state
         (fn [{size :size order :order :as current-state}]
           (let [size (+ size (.readableBytes chunk))]
             (assoc  current-state
               :order    (or order (.order chunk))
               :size     (+ size (.readableBytes chunk))
               :aborted? (< max-size size)))))]

    (if aborted?
      (error)
      (.addLast ll chunk))))

(defn- buffer-stream
  [stream dn buffer? size]
  (if-not buffer?
    stream
    (let [msg (atom nil)]
      (defstream
        ;; If the request is chunked, save the request
        ;; and start buffering up the chunks
        (request [[hdrs body :as req]]
          (if (not= :chunked body)
            (stream :request req)
            (reset! msg {:success-fn #(stream :request [hdrs %])
                         :error-fn   #(dn :response request-entity-too-large)
                         :order      nil
                         :size       0
                         :ll         (LinkedList.)})))

        ;; If the response is chunked, save the response
        ;; and start buffering up the chunks
        (response [[status hdrs body :as resp]]
          (if (not= :chunked body)
            (stream :response resp)
            (reset! msg {:success-fn #(stream :response [status hdrs %])
                         :error-fn   #(dn :response server-error)
                         :order      nil
                         :size       0
                         :ll         (LinkedList.)})))

        ;; Buffer up the body chunks
        ;; TODO: Make this work when chunks might not be ChannelBuffers
        (body [^ChannelBuffer chunk]
          (let [{success  :success-fn
                 aborted? :aborted?
                 order    :order
                 ll       :ll
                 :as current-state} @msg]

            (when-not aborted?
              (if chunk
                (handle-chunk msg chunk size)
                (success (CompositeChannelBuffer. order ll))))))

        (else [evt val]
          (stream evt val))))))

(defn body-buffer
  ([app] (body-buffer app {}))
  ([app opts]
     (let [opts               (merge default-options opts)
           buffer-upstream?   (opts :upstream)
           buffer-downstream? (opts :downstream)
           max-size           (opts :max-size)]
       (fn [downstream]
         (buffer-stream
          (app (buffer-stream downstream downstream buffer-downstream? max-size))
          downstream
          buffer-upstream?
          max-size)))))
