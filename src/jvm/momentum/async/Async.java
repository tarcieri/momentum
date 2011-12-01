package momentum.async;

import clojure.lang.*;
import java.util.LinkedList;

public abstract class Async<T> extends AFn implements IPending, IDeref, IBlockingDeref {

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

  /*
   * Puts a value into the async object
   */
  public boolean put(Object val) {
    throw new UnsupportedOperationException();
  }

  /*
   * Aborts an async object
   */
  public boolean abort(Exception e) {
    throw new UnsupportedOperationException();
  }

  final protected boolean realizeSuccess(T v) {
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

  final protected boolean realizeError(Exception e) {
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
    if (!observe()) {
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

  boolean observe() {
    return isRealized;
  }

  final public boolean isRealized() {
    return isRealized;
  }

  final public boolean isSuccessful() {
    return isRealized && err == null;
  }

  final public boolean isAborted() {
    return isRealized && err != null;
  }

  /*
   * I(Blocking)Deref API
   */
  final public Object deref() {
    return deref(-1, null);
  }

  final public Object deref(long ms, Object timeoutValue) {
    // Wait for the value to become realized
    if (block(ms)) {
      if (err != null) {
        throw Util.runtimeException(err);
      }
      else {
        return val;
      }
    }

    return timeoutValue;
  }

  /*
   * Block for the Async object to become realized for a maximum given time in
   * ms;
   */
  final protected boolean block(long ms) {
    if (observe()) {
      return true;
    }

    if (ms == 0) {
      return false;
    }

    synchronized (this) {
      if (isRealized) {
        return true;
      }

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

      return isRealized;
    }
  }

  final void deliverAll(boolean success) {
    Receiver r;
    while ((r = receivers.poll()) != null) {
      deliver(r, success);
    }
  }

  final void deliver(Receiver r, boolean success) {
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
