(ns momentum.core.channel
  (:use
   momentum.core.buffer
   momentum.core.deferred)
  (:import
   [momentum.async
    AsyncTransfer]))

(declare channel-seq)

(deftype Channel [transfer open? head]
  clojure.lang.Seqable
  (seq [_]
    @head)

  DeferredRealizer
  (put [this val]
    (when @(.open? this)
      (.put (.transfer this) val)))
  (abort [this err]
    (when @(.open? this)
      (.abort (.transfer this) err)))

  clojure.lang.IFn
  (invoke [this v]
    (put this v))

  clojure.lang.Counted
  (count [this]
    (.count (.transfer this))))

(defn- channel-seq
  [ch]
  (async-seq
    (fn [_]
      (doasync (.poll (.transfer ch))
        (fn [v]
          (when-not (= ::close-channel v)
            (let [nxt (channel-seq ch)]
              (reset! (.head ch) nxt)
              (cons v nxt))))))))

(defn channel
  []
  (let [ch (Channel. (AsyncTransfer. nil) (atom true) (atom nil))]
    (reset! (.head ch) (channel-seq ch))
    ch))

(defn enqueue
  ([_])
  ([ch & vs]
     (when @(.open? ch)
       (loop [[v & vs] vs]
         (put ch v)
         (when vs
           (recur vs))))))

(defn close
  [ch]
  (reset! (.open? ch) false)
  (.put (.transfer ch) ::close-channel))
