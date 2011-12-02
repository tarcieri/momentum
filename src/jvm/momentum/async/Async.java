package momentum.async;

import clojure.lang.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

public abstract class Async<T> extends AFn implements IPending, IDeref, IBlockingDeref {

  static class Node {
    /*
     * Next node
     */
    final Node next;

    /*
     * Callback to invoke
     */
    final Receiver cb;

    /*
     * Thread waiting on async object
     */
    final Thread th;

    Node(Node next, Receiver cb, Thread t) {
      this.next = next;
      this.cb   = cb;
      this.th   = t;
    }
  }

  static final Node REALIZED = new Node(null, null, null);

  /*
   * Whether or not the async object is realized
   */
  private final AtomicBoolean isRealized = new AtomicBoolean();

  /*
   * Is there a thread waiting for the async object to become realized
   */
  private boolean isBlocked;

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
  final AtomicReference<Node> head = new AtomicReference<Node>();

  boolean observe() {
    return isRealized();
  }

  final public boolean isRealized() {
    return head.get() == REALIZED;
  }

  final public boolean isSuccessful() {
    return isRealized() && err == null;
  }

  final public boolean isAborted() {
    return isRealized() && err != null;
  }

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
    if (!isRealized.compareAndSet(false, true)) {
      return false;
    }

    // Handle the actual value
    val = v;
    deliverAll(true);

    return true;
  }

  final protected boolean realizeError(Exception e) {
    if (!isRealized.compareAndSet(false, true)) {
      return false;
    }

    err = e;
    deliverAll(false);

    return true;
  }

  final public void receive(Receiver r) {
    if (r == null) {
      throw new NullPointerException("Receiver is null");
    }

    if (listen(r, null)) {
      deliver(r, err == null);
    }
  }

  final private boolean listen(Receiver r, Thread t) {
    Node curr, node;

    // Clean up the observe concept
    observe();

    do {
      curr = head.get();

      // If the async object is already realized, then just deliver it now
      if (curr == REALIZED) {
        return true;
      }

      node = new Node(curr, r, t);

    } while (!head.compareAndSet(curr, node));

    return false;
  }

  /*
   * I(Blocking)Deref API
   */
  final public Object deref() {
    return deref(false, 0, null);
  }

  final public Object deref(long ms, Object timeoutValue) {
    return deref(true, ms * 1000000, timeoutValue);
  }

  final private Object deref(boolean timed, long nanos, Object timeoutValue) {
    if (!listen(null, Thread.currentThread())) {
      long lastTime = timed ? System.nanoTime() : 0L;

      // Loop & park current thread until the async object is realized
      do {
        if (timed) {
          if (nanos <= 0) {
            return timeoutValue;
          }

          LockSupport.park(nanos);

          long now = System.nanoTime();

          nanos -= (now - lastTime);
          lastTime = now;
        }
        else {
          LockSupport.park();
        }

      } while (head.get() != REALIZED);
    }

    // Successfully waited for the async object to realize
    if (err != null) {
      throw Util.runtimeException(err);
    }
    else {
      return val;
    }
  }

  final void deliverAll(boolean success) {
    Node curr = head.getAndSet(REALIZED);

    while (curr != null) {
      if (curr.cb != null) {
        deliver(curr.cb, success);
      }
      else {
        LockSupport.unpark(curr.th);
      }

      curr = curr.next;
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
