(ns momentum.core.channel
  (:use
   momentum.core.deferred)
  (:import
   [momentum.core
    AsyncTransfer]))

(declare channel-seq)

(deftype Channel [transfer open? head ms]
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

  clojure.lang.IPending
  (isRealized [this]
    (not @(.open? this))))

(defn- channel-seq
  [ch]
  (async-seq (.ms ch)
    (fn []
      (doasync (.poll (.transfer ch))
        (fn [v]
          (if (= ::close-channel v)
            (reset! (.head ch) nil)
            (let [nxt (channel-seq ch)]
              (reset! (.head ch) nxt)
              (cons v nxt))))))))

(defn- mk-channel
  [ms]
  (let [ch (Channel. (AsyncTransfer. nil) (atom true) (atom nil) ms)]
    (reset! (.head ch) (channel-seq ch))
    ch))

(defn channel
  []
  (mk-channel 0))

(defn blocking-channel
  ([]   (mk-channel -1))
  ([ms] (mk-channel ms)))

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
