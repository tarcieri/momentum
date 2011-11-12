package momentum.core;

import clojure.lang.AFn;
import java.util.LinkedList;

public class Async<T> extends AFn implements Receivable {

  /*
   * Whether or not the async object is realized
   */
  volatile boolean isRealized;

  /*
   * Is there a thread waiting for the async object to become realized
   */
  boolean isBlocked;

  /*
   * The realized value of the async object
   */
  T val;

  /*
   * The error that caused the async object to become aborted
   */
  Exception err;

  /*
   * The callbacks to invoke when the deferred value becomes realized
   */
  final LinkedList<Receiver> receivers = new LinkedList<Receiver>();

  protected boolean realizeSuccess(T v) {
    synchronized (this) {
      if (isRealized) {
        return false;
      }

      // Handle the actual value
      val = v;

      // Mark the async object as realized
      isRealized = true;

      // Notify any pending threads
      if (isBlocked) {
        notifyAll();
      }
    }

    deliverAll(true);

    return true;
  }

  protected boolean realizeError(Exception e) {
    // The synchronization block is required to ensure that there is no race
    // condition between invoking pending callbacks and registering new ones as
    // pending.
    synchronized (this) {
      if (isRealized) {
        return false;
      }

      // First track the error
      err = e;

      // Mark the async object as realized
      isRealized = true;

      // Notify any pending threads
      if (isBlocked) {
        notifyAll();
      }
    }

    deliverAll(false);

    return true;
  }

  /*
   * Receivalbe API
   */
  final public void receive(Receiver r) {
    if (r == null) {
      throw new NullPointerException("Receiver is null");
    }

    // Check to see if the async object is realized yet. The check is actually
    // done twice if the first one fails, however, the second time around only
    // the volatile boolean field is checked. The reason why the method is
    // called the first time around is because isRealized() is a hook point
    // that is overridden in AsyncSeq (and possibly other subclasses).
    if (!isRealized()) {
      synchronized (this) {
        // Check to make sure that the object still is not realized now that a
        // lock has been established, also a lighter check is done than the
        // first time around.
        if (!isRealized) {
          receivers.add(r);
          return;
        }
      }
    }

    deliver(r, err == null);
  }

  public boolean isRealized() {
    return isRealized;
  }

  final public boolean isSuccessful() {
    return isRealized() && err == null;
  }

  final public boolean isAborted() {
    return isRealized() && err != null;
  }

  protected void block(long ms) {
    synchronized (this) {
      isBlocked = true;

      try {
        if (ms < 0) {
          wait();
        }
        else {
          wait(ms);
        }
      }
      catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  void deliverAll(boolean success) {
    Receiver r;
    while ((r = receivers.poll()) != null) {
      deliver(r, success);
    }
  }

  void deliver(Receiver r, boolean success) {
    try {
      if (success) {
        r.success(val);
      }
      else {
        r.error(err);
      }
    }
    catch (Exception e) {
      // Just ignore this for now
    }
  }
}
