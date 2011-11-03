package picard.core;

import clojure.lang.Obj;
import clojure.lang.ASeq;
import clojure.lang.ISeq;
import clojure.lang.IPending;
import clojure.lang.IPersistentMap;
import java.util.LinkedList;

public final class DeferredSeq extends ASeq implements Receivable, IPending {

  /*
   * Channel that populates the seq
   */
  final Channel chan;

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
   * The callbacks to invoke when the head of the seq is realized.
   */
  final LinkedList<Receiver> receivers;

  /*
   * Deferred value representing the moment that the head of this seq is read.
   */
  final Deferred read;

  /*
   * The next link in the chain
   */
  DeferredSeq next;


  DeferredSeq(Channel ch) {
    chan = ch;
    read = new Deferred();

    receivers = new LinkedList<Receiver>();
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

  Receivable put(Object v) {
    synchronized (this) {
      if (isRealized) {
        throw new IllegalStateException("Deferred seq head previously realized");
      }

      // Set the head to the value being put
      head = v;

      // Mark the sequence as realized. Since isRealized is volatile, parallel
      // threads will be able to see head & next.
      isRealized = true;

      if (isBlocked) {
        notifyAll();
      }
    }

    Receiver r;
    while ((r = receivers.poll()) != null) {
      deliver(r);
    }

    return read;
  }

  public void abort(Exception e) {
    synchronized (this) {
      if (isRealized) {
        throw new IllegalStateException("Deferred seq head previously realized");
      }

      err = e;

      isRealized = true;

      if (isBlocked) {
        notifyAll();
      }
    }

    Receiver r;
    while ((r = receivers.poll()) != null) {
      deliver(r);
    }
  }

  public void receive(Receiver r) {
    if (r == null) {
      throw new NullPointerException("Receiver is null");
    }

    if (isRealized) {
      deliver(r);
      return;
    }

    synchronized (this) {
      if (!isRealized) {
        receivers.add(r);
        return;
      }
    }

    deliver(r);
  }

  public Object first() {
    // First, a hot path check
    if (read.isRealized()) {
      return head;
    }

    if (isRealized) {
      if (err != null) {
        throw new RuntimeException(err);
      }

      observe();
      return head;
    }

    if (chan.canBlock) {
      block();
      return first();
    }
    else {
      throw new IllegalStateException("Deferred seq head not realized");
    }
  }

  public ISeq next() {
    if (read.isRealized()) {
      return next;
    }

    if (isRealized) {
      if (err != null) {
        throw new RuntimeException(err);
      }

      observe();
      return next;
    }

    if (chan.canBlock) {
      block();
      return next();
    }
    else {
      throw new IllegalStateException("Deferred seq head not realized");
    }
  }

  public Obj withMeta(IPersistentMap meta) {
    throw new RuntimeException("Not supported yet");
  }

  private synchronized void observe() {
    if (!read.isRealized()) {
      read.put(this);
      chan.head = next;
    }
  }

  private void deliver(Receiver r) {
    try {
      if (err != null) {
        r.error(err);
      }
      else {
        r.success(this);
      }
    }
    catch (Exception e) {
      // Nothing for now
    }
  }

  private void block() {
    synchronized (this) {
      if (!isRealized) {
        if (chan.timeout == 0) {
          throw new TimeoutException();
        }

        isBlocked = true;

        try {
          if (chan.timeout < 0) {
            wait();
          }
          else {
            wait(chan.timeout);
          }
        }
        catch (InterruptedException e) {
          throw new RuntimeException(e);
        }

        if (!isRealized) {
          throw new TimeoutException();
        }
      }
    }
  }

}
