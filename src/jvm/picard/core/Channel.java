package picard.core;

import clojure.lang.Cons;
import clojure.lang.ISeq;
import clojure.lang.Seqable;
import java.util.concurrent.atomic.AtomicReference;

/*
 * seq() returns a sequable object 
 */
public final class Channel implements Seqable {

  /*
   * Whether or not the sequences are aloud to block waiting be realized
   */
  final boolean canBlock;

  /*
   * How long in Milliseconds the channel should block waiting for values
   */
  final long timeout;

  /*
   * The first element waiting to be observed
   */
  volatile DeferredSeq head;

  /*
   * The tail of the queue
   */
  DeferredSeq tail;

  public Channel() {
    this(false, 0);
  }

  public Channel(long ms) {
    this(true, ms);
  }

  Channel(boolean blk, long ms) {
    canBlock = blk;
    timeout  = ms;
    tail     = new DeferredSeq(this);
    head     = tail;
  }

  public Deferred put(Object v) {
    DeferredSeq curr;

    synchronized (this) {
      curr = tail;

      if (curr == null) {
        throw new IllegalStateException("Channel closed");
      }

      tail = curr.grow();
    }

    return curr.put(v);
  }

  public Deferred putLast(Object v) {
    DeferredSeq curr;

    synchronized (this) {
      curr = tail;

      if (curr == null) {
        throw new IllegalStateException("Channel closed");
      }

      tail = null;
    }

    return curr.put(v);
  }

  public void abort(Exception err) {
    DeferredSeq curr;

    synchronized (this) {
      curr = tail;

      if (curr == null) {
        throw new IllegalStateException("Channel closed");
      }

      tail = null;
    }

    curr.abort(err);
  }

  public void close() {
    DeferredSeq curr;

    synchronized (this) {
      curr = tail;
      tail = null;
    }

    if (curr != null && !curr.isRealized()) {
      curr.put(null);
    }
  }

  public ISeq seq() {
    return head;
  }

}
