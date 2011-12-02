package momentum.async;

import clojure.lang.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class AsyncSeq extends Async<ISeq> implements ISeq, Sequential, List, Receiver {

  /*
   * Function that will realize the sequence
   */
  IFn fn;

  /*
   * Pending async value that will realize the seq
   */
  Async pending;

  /*
   * Latch used in the edge case that the seq is being aborted while being
   * realized on another thread.
   */
  CountDownLatch latch;

  public AsyncSeq(IFn fn) {
    this.fn = fn;
  }

  public AsyncSeq(Async a) {
    pending = a;
    a.receive(this);
  }

  synchronized void unlatch() {
    if (latch != null) {
      latch.countDown();
    }
  }

  void latch() throws InterruptedException {
    if (observe()) {
      return;
    }

    synchronized (this) {
      if (isRealized() || pending != null) {
        return;
      }

      if (latch == null) {
        latch = new CountDownLatch(1);
      }
    }

    latch.await();
  }

  /*
   * Evaluate the body of the async seq only once. If the return value is an
   * async value of some kind, then register a callback on it.
   */
  public boolean observe() {
    if (isRealized()) {
      return true;
    }

    final IFn fn;
    final Object ret;

    synchronized (this) {
      // If the fn is null, then we're already in the process of realizing the
      // sequence
      if (this.fn == null) {
        return isRealized();
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

      // The seq is realized, so unlatch
      unlatch();

      // Return true since the seq is realized
      return true;
    }

    if (ret instanceof Async) {
      pending = (Async) ret;

      unlatch();

      // Register the current async sequence as the receiver for the returned
      // async value
      pending.receive(this);

      // The volatile isRealized variable must be read in order to determine if
      // the sequence was realized since registering the reciever
      return isRealized();
    }
    else {
      // The returned value is a normal object, so the sequence has been
      // realized.
      success(ret);

      unlatch();

      return true;
    }
  }

  // Find the first unrealized element and abort it
  public boolean abort(Exception err) {
    ISeq next;
    AsyncSeq curr = this;

    while (true) {
      try {
        curr.latch();
      }
      catch (Exception e) {
        return false;
      }

      if (curr.realizeError(err)) {
        pending.abort(err);
        return true;
      }
      else if (curr.val == null) {
        return false;
      }

      next = curr.val.next();

      if (next instanceof AsyncSeq) {
        curr = (AsyncSeq) next;
      }
      else {
        return false;
      }
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
    if (isRealized()) {
      if (err != null) {
        throw Util.runtimeException(err);
      }

      return val;
    }

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
    return new Cons(o, seq());
  }

  public int count() {
    int c = 0;

	  for (ISeq s = seq(); s != null; s = s.next()) {
      ++c;
    }

    return c;
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

  /*
   * java.util.Collection API
   */

  public Object[] toArray() {
  	return RT.seqToArray(seq());
  }

  public boolean add(Object o) {
  	throw new UnsupportedOperationException();
  }

  public boolean remove(Object o) {
  	throw new UnsupportedOperationException();
  }

  public boolean addAll(Collection c) {
  	throw new UnsupportedOperationException();
  }

  public void clear() {
  	throw new UnsupportedOperationException();
  }

  public boolean retainAll(Collection c) {
  	throw new UnsupportedOperationException();
  }

  public boolean removeAll(Collection c) {
  	throw new UnsupportedOperationException();
  }

  public boolean containsAll(Collection c) {
  	for (Object o : c) {
  		if (!contains(o)) {
  			return false;
      }
  	}

  	return true;
  }

  public Object[] toArray(Object[] a) {
  	if (a.length >= count()) {
  		ISeq s = seq();

  		for (int i = 0; s != null; ++i, s = s.next()) {
  			a[i] = s.first();
  		}

  		if (a.length > count()) {
  			a[count()] = null;
      }

  		return a;
  	}
  	else {
  		return toArray();
    }
  }

  public int size() {
  	return count();
  }

  public boolean isEmpty() {
  	return seq() == null;
  }

  public boolean contains(Object o) {
  	for (ISeq s = seq(); s != null; s = s.next()) {
  		if (Util.equiv(s.first(), o)) {
  			return true;
      }
  	}

  	return false;
  }

  public Iterator iterator() {
  	return new SeqIterator(seq());
  }

  /*
   * java.util.List API
   */

  @SuppressWarnings("unchecked")
  List reify() {
    return new ArrayList<Object>(this);
  }

  public List subList(int fromIndex, int toIndex) {
  	return reify().subList(fromIndex, toIndex);
  }

  public Object set(int index, Object element) {
  	throw new UnsupportedOperationException();
  }

  public Object remove(int index) {
  	throw new UnsupportedOperationException();
  }

  public int indexOf(Object o) {
  	ISeq s = seq();

  	for (int i = 0; s != null; s = s.next(), i++) {
  		if (Util.equiv(s.first(), o)) {
  			return i;
      }
    }

  	return -1;
  }

  public int lastIndexOf(Object o) {
  	return reify().lastIndexOf(o);
  }

  public ListIterator listIterator() {
  	return reify().listIterator();
  }

  public ListIterator listIterator(int index) {
  	return reify().listIterator(index);
  }

  public Object get(int index) {
  	return RT.nth(this, index);
  }

  public void add(int index, Object element) {
  	throw new UnsupportedOperationException();
  }

  public boolean addAll(int index, Collection c) {
  	throw new UnsupportedOperationException();
  }

}
