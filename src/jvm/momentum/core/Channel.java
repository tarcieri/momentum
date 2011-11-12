package momentum.core;

import clojure.lang.AFn;
import clojure.lang.Cons;
import clojure.lang.IPending;
import clojure.lang.ISeq;
import clojure.lang.Seqable;
import java.util.concurrent.atomic.AtomicReference;

public final class Channel extends AFn implements Seqable, IPending {

  static final AsyncVal channelClosed = AsyncVal.aborted(new RuntimeException("Channel closed"));

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

  public Receivable put(Object v) {
    DeferredSeq curr;

    synchronized (this) {
      curr = tail;

      if (curr == null) {
        return channelClosed;
      }

      tail = curr.grow();
    }

    return curr.put(v);
  }

  public Object invoke(Object v) {
    return put(v);
  }

  public Receivable putLast(Object v) {
    DeferredSeq curr;

    synchronized (this) {
      curr = tail;

      if (curr == null) {
        return channelClosed;
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
        return;
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

  public boolean isRealized() {
    DeferredSeq curr = head;
    return curr == null || curr.isRealized();
  }

}
