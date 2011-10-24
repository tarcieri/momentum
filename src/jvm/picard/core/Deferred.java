package picard.core;

import clojure.lang.IDeref;
import clojure.lang.IBlockingDeref;
import clojure.lang.IPending;

public final class Deferred implements IDeref, IBlockingDeref, IPending {

  /*
   * Whether or not the current deferred value is realized.
   */
  volatile boolean isRealized;

  /*
   * Is a thread blocked waiting for the deferred value to be realized?
   */
  boolean isBlocked;

  /*
   * The realized value of the deferred value
   */
  Object value;

  /*
   * The error that caused the abortion of the deferred value
   */
  Exception err;

  /*
   * The callback to invoke when the deferred value is realized.
   */
  DeferredReceiver receiver;

  public boolean isRealized() {
    return isRealized;
  }

  public boolean isSuccessful() {
    return isRealized && err == null;
  }

  public boolean isAborted() {
    return isRealized && err != null;
  }

  public void put(Object v) {
    final boolean invoke;

    synchronized (this) {
      if (isRealized) {
        throw new RuntimeException("Not able ot accept value");
      }

      invoke = receiver != null;
      value  = v;
      // isRealized must be set last in order to preserve the happens-before semantics
      isRealized = true;

      if (isBlocked) {
        notifyAll();
      }
    }

    if (invoke) {
      invoke();
    }
  }

  public void abort(Exception e) {
    final boolean invoke;

    synchronized (this) {
      if (isRealized) {
        throw new RuntimeException("The deferred value has already been realized");
      }

      err = e;
      // isRealized must be set last in order to preserve the happens-before semantics
      isRealized = true;

      invoke = receiver != null;

      if (isBlocked) {
        notifyAll();
      }
    }

    if (invoke) {
      invoke();
    }
  }

  public void receive(DeferredReceiver r) {
    final boolean invoke;

    synchronized (this) {
      if (receiver != null) {
        throw new RuntimeException("A receiver has already been set");
      }

      receiver = r;
      invoke   = isRealized;
    }

    if (invoke) {
      invoke();
    }
  }

  public Object deref() {
    return deref(-1, null);
  }

  public Object deref(long ms, Object timeoutValue) {
    if (!isRealized) {
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

        if (!isRealized) {
          return timeoutValue;
        }
      }
    }

    if (err != null) {
      throw new RuntimeException(err);
    }
    else {
      return value;
    }
  }

  private void invoke() {
    try {
      if (err != null) {
        receiver.error(err);
      }
      else {
        receiver.success(value);
      }
    }
    catch (Exception e) {
      // Just discard this for now
    }
  }
}
