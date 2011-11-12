package momentum.core;

import clojure.lang.Cons;
import clojure.lang.IFn;
import clojure.lang.IPersistentCollection;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Obj;
import clojure.lang.PersistentList;
import clojure.lang.RT;
import clojure.lang.Sequential;
import clojure.lang.Util;
import java.util.List;
import java.util.LinkedList;

public final class AsyncSeq extends Obj implements ISeq, Sequential, Receivable, Receiver {

  /*
   * Function that will realize the sequence
   */
  IFn fn;

  /*
   * The realized sequence
   */
  ISeq s;

  /*
   * The error that caused the seq to be aborted
   */
  Exception err;

  /*
   * The callbacks to invoke when the async seq is realized
   */
  final LinkedList<Receiver> receivers = new LinkedList<Receiver>();

  /*
   * Tracks the state of the async seq
   */
  volatile boolean isRealized;

  public AsyncSeq(IFn fn) {
    this.fn = fn;
  }

  /*
   * Obj API
   */
  public Obj withMeta(IPersistentMap meta) {
    return this;
  }

  /*
   * Evaluate the body of the async seq only once. If the return value is an
   * async value of some kind, then register a callback on it.
   */
  boolean observe() {
    if (isRealized) {
      return true;
    }

    final IFn fn;
    final Object ret;

    synchronized (this) {
      // If the fn is null, then we're already in the process of realizing the
      // sequence
      if (this.fn == null) {
        return isRealized;
      }

      fn = this.fn;
      this.fn = null;
    }

    try {
      ret = fn.invoke();
    }
    catch (Exception e) {
      // Abort the seq
      error(e);

      // Return true since the seq is realized
      return true;
    }

    if (ret instanceof Receivable) {
      // Register the current async sequence as the receiver for the returned
      // async value
      ((Receivable) ret).receive(this);

      // The volatile isRealized variable must be read in order to determine if
      // the sequence was realized since registering the reciever
      return isRealized;
    }
    else {
      // The returned value is a normal object, so the sequence has been
      // realized.
      success(ret);

      return true;
    }
  }

  /*
   * Receivable API
   */
  public void receive(Receiver r) {
    if (r == null) {
      throw new NullPointerException("Receiver is null");
    }

    if (!observe()) {
      synchronized (this) {
        // Check to make sure that the seq is still not realized now that a
        // lock has been established.
        if (!isRealized) {
          receivers.add(r);
          return;
        }
      }
    }

    deliver(r);
  }

  public boolean isRealized() {
    return isRealized;
  }

  /*
   * Receiver API
   */
  public void success(Object val) {
    try {
      // Save off the realized sequence
      s = RT.seq(val);

      // Mark the async seq as complete
      isRealized = true;

      deliverAll();
    }
    catch (Exception e) {
      error(e);
    }
  }

  public void error(Exception e) {
    // Save off the error
    err = e;

    // Mark the async sequence as complete
    isRealized = true;

    deliverAll();
  }

  void deliverAll() {
    Receiver r;
    synchronized (this) {
      while ((r = receivers.poll()) != null) {
        deliver(r);
      }
    }
  }

  void deliver(Receiver r) {
    try {
      if (err != null) {
        r.error(err);
      }
      else {
        r.success(s);
      }
    }
    catch (Exception e) {
      // Nothing right now
    }
  }

  void ensureSuccess() {
    if (observe()) {
      if (err != null) {
        throw Util.runtimeException(err);
      }

      return;
    }

    throw new RuntimeException("Async seq has not been realized yet");
  }

  /*
   * ISeq API
   */
  public ISeq seq() {
    return this;
  }

  public Object first() {
    ensureSuccess();

    if (s == null) {
      return null;
    }

    return s.first();
  }

  public ISeq next() {
    ensureSuccess();

    if (s == null) {
      return null;
    }

    return s.next();
  }

  public ISeq more() {
    ensureSuccess();

    if (s == null) {
      return PersistentList.EMPTY;
    }

    return s.more();
  }

  public ISeq cons(Object o) {
    return new Cons(o, this);
  }

  public int count() {
    throw new UnsupportedOperationException();
  }

  public IPersistentCollection empty() {
    return PersistentList.EMPTY;
  }

  public boolean equiv(Object o) {
    return equals(o);
  }

  public boolean equals(Object o) {
    ensureSuccess();

    if (s != null) {
      return s.equiv(o);
    }
    else {
      return (o instanceof Sequential || o instanceof List) && RT.seq(o) == null;
    }
  }

}
