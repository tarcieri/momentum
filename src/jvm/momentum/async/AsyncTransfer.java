package momentum.async;

import clojure.lang.*;
import java.util.*;

/*
 * AsyncTransfer is a simple abstraction that is intended to assist with
 * transfering values from a producer to a consumer.
 *
 * TODO: I'm sure this isn't the most efficient implementation, so it might be
 * worth investigating other possible implementations.
 *
 * TODO: Unsuckify everything
 */
public class AsyncTransfer implements Counted {

  /*
   * Whether the transfer has a default value or not.
   */
  final boolean hasDefault;

  /*
   * The transfer's default value
   */
  final Object val;

  /*
   * List of values pending transfer. poll() pulls from this list first before
   * creating a new deferred value. Is volatile because setting his value to
   * nil represents the transfer is complete.
   */
  volatile LinkedList<Object> queuedValues = new LinkedList<Object>();

  /*
   * List of AsyncValues that represent polls that are pending realization.
   */
  final LinkedList<AsyncVal> queuedTransfers = new LinkedList<AsyncVal>();

  /*
   * The exception that the transfer has been aborted with.
   */
  Exception err;

  public AsyncTransfer() {
    hasDefault = false;
    val = null;
  }

  public AsyncTransfer(Object v) {
    hasDefault = true;
    val = v;
  }

  public int count() {
    synchronized (this) {
      if (isClosed()) {
        return 0;
      }

      return queuedValues.size() - queuedTransfers.size();
    }
  }

  public boolean isClosed() {
    return queuedValues == null;
  }

  public boolean put(Object o) {
    AsyncVal dst = null;

    synchronized (this) {
      if (isClosed()) {
        return false;
      }
      else if (queuedTransfers.size() > 0) {
        dst = queuedTransfers.poll();
      }
      else {
        queuedValues.offer(o);
      }
    }

    if (dst != null) {
      dst.put(o);
    }

    return true;
  }

  public Object poll() throws Exception {
    synchronized (this) {
      if (isClosed()) {
        if (err != null) {
          throw err;
        }
        else {
          return val;
        }
      }
      else if (queuedValues.size() > 0) {
        return queuedValues.poll();
      }
      else {
        AsyncVal placeholder = new AsyncVal();
        queuedTransfers.offer(placeholder);
        return placeholder;
      }
    }
  }

  public boolean abort(final Exception e) {
    synchronized (this) {
      if (!doClose()) {
        return false;
      }

      err = e;
    }

    AsyncVal v;
    while ((v = queuedTransfers.poll()) != null) {
      v.abort(e);
    }

    return true;
  }

  public boolean close() {
    if (hasDefault) {
      return abort(new RuntimeException("The async transfer has been closed"));
    }
    else if (doClose()) {

      AsyncVal v;
      while ((v = queuedTransfers.poll()) != null) {
        v.put(val);
      }

      return true;
    }

    return false;
  }

  boolean doClose() {
    synchronized (this) {
      if (isClosed()) {
        return false;
      }

      queuedValues = null;
    }

    return true;
  }
}
