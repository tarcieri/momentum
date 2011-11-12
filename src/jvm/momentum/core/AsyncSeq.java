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

public final class AsyncSeq extends Async<ISeq> implements ISeq, Sequential, Receiver {

  /*
   * Function that will realize the sequence
   */
  IFn fn;

  public AsyncSeq(IFn fn) {
    this.fn = fn;
  }

  /*
   * Evaluate the body of the async seq only once. If the return value is an
   * async value of some kind, then register a callback on it.
   */
  public boolean isRealized() {
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
      realizeError(e);

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
   * Receiver API
   */
  public void success(Object val) {
    try {
      // Save off the realized sequence
      ISeq s = RT.seq(val);
      realizeSuccess(s);
    }
    catch (Exception e) {
      realizeError(e);
    }
  }

  public void error(Exception e) {
    realizeError(e);
  }

  void ensureSuccess() {
    if (isRealized()) {
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

    if (val == null) {
      return null;
    }

    return val.first();
  }

  public ISeq next() {
    ensureSuccess();

    if (val == null) {
      return null;
    }

    return val.next();
  }

  public ISeq more() {
    ensureSuccess();

    if (val == null) {
      return PersistentList.EMPTY;
    }

    return val.more();
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

    if (val != null) {
      return val.equiv(o);
    }
    else {
      return (o instanceof Sequential || o instanceof List) && RT.seq(o) == null;
    }
  }

}
