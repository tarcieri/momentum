package picard.core;

import clojure.lang.Cons;
import clojure.lang.ISeq;
import clojure.lang.Seqable;
import java.util.concurrent.atomic.AtomicReference;

/*
 * seq() returns a sequable object 
 */
public final class Channel implements Seqable {

  final AtomicReference<DeferredSeq> head;
  final AtomicReference<DeferredSeq> tail;

  public Channel(boolean canBlock) {
    seq  = new DeferredSeq(this, canBlock);
    head = new AtomicReference<DeferredSeq>(seq);
    tail = new AtomicReference<DeferredSeq>(seq);
  }

  public Deferred put(Object v) {
    DeferredSeq seq = tail.get();

    if (seq == null) {
      throw new IllegalStateException("Channel closed");
    }

    return seq.put(v);
  }

  public Deferred putLast(Object v) {
    DeferredSeq seq = tail.getAndSet(null);

    if (seq == null) {
      throw new IllegalStateException("Channel closed");
    }

    return seq.put(v);
  }

  public void abort(Exception err) {
    DeferredSeq seq = tail.getAndSet(null);

    if (seq == null) {
      throw new IllegalStateException("Channel closed");
    }

    seq.abort(err);
  }

  public void close() {
    DeferredSeq seq = tail.getAndSet(null);

    if (seq != null && !seq.isRealized()) {
      seq.put(null);
    }
  }

  public ISeq seq() {
    return active.get();
  }

  boolean step(DeferredSeq seq, DeferredSeq next) {
    return tail.compareAndSet(seq, next);
  }

}
