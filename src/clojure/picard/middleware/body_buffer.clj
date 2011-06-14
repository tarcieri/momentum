(ns picard.middleware.body-buffer
  (:use
   [picard.helpers])
  (:import
   [org.jboss.netty.buffer
    ChannelBuffer
    CompositeChannelBuffer]
   [java.util
    LinkedList]))

(def default-options {:upstream true :downstream true})

(defn- buffer-stream
  [stream buffer?]
  (if-not buffer?
    stream
    (let [msg (atom nil)]
      (defstream
        ;; If the request is chunked, save the request
        ;; and start buffering up the chunks
        (request [[hdrs body :as req]]
          (if (not= :chunked body)
            (stream :request req)
            (reset! msg [#(stream :request [hdrs %]) nil (LinkedList.)])))

        ;; If the response is chunked, save the response
        ;; and start buffering up the chunks
        (response [[status hdrs body :as resp]]
          (if (not= :chunked body)
            (stream :response resp)
            (reset! msg [#(stream :response [status hdrs %]) nil (LinkedList.)])))

        ;; Buffer up the body chunks
        ;; TODO: Make this work when chunks might not be ChannelBuffers
        (body [^ChannelBuffer chunk]
          (let [[f order ll] @msg]
            (if chunk
              (do
                (when-not (instance? ChannelBuffer chunk)
                  (throw (Exception. (str "Non ChannelBuffer chunks is "
                                          "not implemented yet"))))

                (when-not order
                  (swap! msg (fn [[f _ ll]] [f (.order chunk) ll])))

                (.addLast ll chunk))

              ;; The body is done, compose all the chunks into one ChannelBuffer
              ;; and send it upstream.
              (f (CompositeChannelBuffer. order ll)))))

        ;; Stream all the other events through
        (else [evt val]
          (stream evt val))))))

(defn body-buffer
  ([app] (body-buffer app {}))
  ([app opts]
     (let [opts (merge default-options opts)]
       (fn [downstream]
         (buffer-stream
          (app (buffer-stream downstream (opts :downstream)))
          (opts :upstream))))))
