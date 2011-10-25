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


  DeferredSeq(Channel ch) {
    chan = ch;
    read = new Deferred();
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

  DeferredSeq grow() {
    // Does not need to be synchronized since put() will be immediately called
    // on the same thread;
    next = new DeferredSeq(chan);
    return next;
  }

  public Deferred put(Object v) {
    final boolean deliver;

    synchronized (this) {
      if (isRealized) {
        throw new IllegalStateException("Deferred seq head previously realized");
      }

      // Set the head to the value being put
      head = v;

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

  public Object first() {
    // First, a hot path check
    if (read.isRealized()) {
      return head;
    }

    synchronized (this) {
      if (isRealized) {
        if (!read.isRealized()) {
          read.put(this);
        }

        return head;
      }
    }

    throw new IllegalStateException("Deferred seq head not realized");
  }

  public ISeq next() {
    if (read.isRealized()) {
      return next;
    }

    if (isRealized) {
      observe();
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

  private synchronized void observe() {
    if (!read.isRealized()) {
      read.put(this);
      chan.head = next;
    }
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
