package picard.core;

import clojure.lang.Obj;
import clojure.lang.ASeq;
import clojure.lang.ISeq;
import clojure.lang.IPending;
import clojure.lang.IPersistentMap;

public final class DeferredSeq extends ASeq implements IPending {

  /*
   * Channel that populates the seq
   */
  private final Channel chan;

  /*
   * Whether the seq is permitted to block
   */
  private final boolean canBlock;

  /*
   * Whether or not the seq's head is realized
   */
  volatile boolean isRealized;

  /*
   * Is there a thread blocked waiting for the deferred value to be realized?
   */
  boolean isBlocked;

  /*
   * The realized head of the seq
   */
  Object head;

  /*
   * The error that caused the abortion of the seq
   */
  Exception err;

  /*
   * The callback to invoke when the head of the seq is realized.
   */
  DeferredReceiver receiver;

  /*
   * Deferred value representing the moment that the head of this seq is read.
   */
  private final Deferred read;

  /*
   * The next link in the chain
   */
  private DeferredSeq next;


  DeferredSeq(Channel ch, boolean blk) {
    chan = ch;
    read = new Deferred();

    canBlock = blk;
  }

  public boolean isRealized() {
    return isRealized;
  }

  public boolean isSuccessful() {
    return isRealized && err == null;
  }

  public boolean isAborted() {
    return isRealized && err != null;
  }

  public Deferred put(Object v) {
    final boolean deliver;

    synchronized (this) {
      if (isRealized) {
        throw new IllegalStateException("Deferred seq head previously realized");
      }

      // Set the head to the value being put
      head = v;

      // Step the seq forward
      DeferredSeq seq = new DeferredSeq(chan, canBlock);

      if (chan.step(this, seq)) {
        next = seq;
      }

      // Mark the sequence as realized. Since isRealized is volatile, parallel
      // threads will be able to see head & next.
      isRealized = true;

      // If the receiver is set, then it should be called.
      deliver = receiver != null;

      if (isBlocked) {
        notifyAll();
      }
    }

    if (deliver) {
      deliver();
    }

    return read;
  }

  public synchronized Object first() {
    if (read.isRealized()) {
      return head;
    }

    if (isRealized) {
      read.put(this);
      return head;
    }

    throw new IllegalStateException("Deferred seq head not realized");
  }

  public ISeq next() {
    if (read.isRealized()) {
      return next;
    }

    if (isRealized) {
      read.put(this);
      return next;
    }

    throw new IllegalStateException("Deferred seq head not realized");
  }

  public Obj withMeta(IPersistentMap meta) {
    return this;
  }

  void abort(Exception err) {
    // Not implemented yet
  }

  private void deliver() {
    try {
      receiver.success(this);
    }
    catch (Exception e) {
      // Nothing for now
    }
  }

}
