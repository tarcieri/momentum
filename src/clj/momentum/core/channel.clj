(ns momentum.core.channel
  (:use
   momentum.core.buffer
   momentum.core.deferred)
  (:import
   [momentum.async
    AsyncTransferQueue]))

(declare channel-seq)

(deftype Channel [transfer head]
  clojure.lang.Seqable
  (seq [_]
    @head)

  DeferredRealizer
  (put [this val]
    (.put (.transfer this) val))
  (abort [this err]
    (.abort (.transfer this) err))

  clojure.lang.IFn
  (invoke [this v]
    (put this v))

  clojure.lang.Counted
  (count [this]
    (.count (.transfer this))))

(defn- channel-seq
  [ch]
  (async-seq
    (fn []
      (doasync (.take (.transfer ch))
        (fn [v]
          (when-not (= ::close-channel v)
            (let [nxt (channel-seq ch)]
              (reset! (.head ch) nxt)
              (cons v nxt))))))))

(defn channel
  []
  (let [ch (Channel. (AsyncTransferQueue. ::close-channel) (atom nil))]
    (reset! (.head ch) (channel-seq ch))
    ch))

(defn enqueue
  ([_])
  ([ch & vs]
     (loop [vs vs cont? true]
       (when-let [[v & more] vs]
         (when (and vs cont?)
           (recur more (put ch v)))))))

(defn close
  [ch]
  (.close (.transfer ch)))
