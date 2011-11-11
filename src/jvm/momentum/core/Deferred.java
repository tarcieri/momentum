package momentum.core;

import clojure.lang.AFn;
import clojure.lang.IDeref;
import clojure.lang.IBlockingDeref;
import java.util.LinkedList;

public final class Deferred extends AFn implements Receivable, IDeref, IBlockingDeref {

  public static Deferred aborted(Exception e) {
    Deferred ret = new Deferred();
    ret.abort(e);
    return ret;
  }

  public static Deferred realized(Object v) {
    Deferred ret = new Deferred();
    ret.put(v);
    return ret;
  }

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
   * The callbacks to invoke when the deferred value is realized.
   */
  final LinkedList<Receiver> receivers;

  public Deferred() {
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

  public boolean put(Object v) {
    synchronized (this) {
      if (isRealized) {
        return false;
      }

      value = v;
      // isRealized must be set last in order to preserve the happens-before semantics
      isRealized = true;

      if (isBlocked) {
        notifyAll();
      }
    }

    // Invoke any pending receivers

    Receiver r;
    while ((r = receivers.poll()) != null) {
      deliver(r);
    }

    return true;
  }

  public Object invoke(Object v) {
    return put(v);
  }

  public boolean abort(Exception e) {
    synchronized (this) {
      if (isRealized) {
        return false;
      }

      err = e;
      // isRealized must be set last in order to preserve the happens-before semantics
      isRealized = true;

      if (isBlocked) {
        notifyAll();
      }
    }

    Receiver r;
    while ((r = receivers.poll()) != null) {
      deliver(r);
    }

    return true;
  }

  public void receive(Receiver r) {
    if (r == null) {
      throw new NullPointerException("Receiver is null");
    }

    // If the deferred value is already realized, just invoke the receiver
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

    // If we get here, then the deferred value was realized between the first
    // check and the second, so just invoke the callback now.
    deliver(r);
  }

  public Object deref() {
    return deref(-1, null);
  }

  public Object deref(long ms, Object timeoutValue) {
    if (!isRealized) {

      if (ms == 0) {
        return timeoutValue;
      }

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

  private void deliver(Receiver receiver) {
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
