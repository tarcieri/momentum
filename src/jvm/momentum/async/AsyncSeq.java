package momentum.async;

import clojure.lang.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class AsyncSeq extends Async<ISeq> implements ISeq, Sequential, List, Receiver {

  enum State {
    FRESH,
    INVOKING,
    PENDING,
    FINAL
  }

  final AtomicReference<State> cs;

  /*
   * Function that will realize the sequence
   */
  final IFn fn;

  /*
   * Pending async value that will realize the seq
   */
  IAsync pending;

  public AsyncSeq(IFn f) {
    fn = f;
    cs = new AtomicReference<State>(State.FRESH);
  }

  /*
   * Evaluate the body of the async seq only once. If the return value is an
   * async value of some kind, then register a callback on it.
   */
  public boolean observe() {
    if (!cs.compareAndSet(State.FRESH, State.INVOKING)) {
      return isRealized();
    }

    try {
      Object ret = fn.invoke();

      if (ret instanceof IAsync) {
        pending = (IAsync) ret;

        if (cs.compareAndSet(State.INVOKING, State.PENDING)) {
          pending.receive(this);
        }
        else {
          pending.abort(new InterruptedException());
        }
      }
      else {
        if (cs.compareAndSet(State.INVOKING, State.FINAL)) {
          realizeSeq(ret);
          return true;
        }
      }
    }
    catch (Exception e) {
      if (cs.compareAndSet(State.INVOKING, State.FINAL)) {
        realizeError(e);
        return true;
      }
    }

    return isRealized();
  }

  // Find the first unrealized element and abort it
  public boolean abort(Exception err) {
    ISeq next;
    AsyncSeq curr = this;

    while (true) {
      curr.observe();

      State prev = curr.cs.getAndSet(State.FINAL);

      if (prev != State.FINAL) {
        if (prev == State.PENDING) {
          pending.abort(new InterruptedException());
        }

        // i am epic win
        realizeError(err);
        return true;
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

  private void realizeSeq(Object val) {
    try {
      realizeSuccess(RT.seq(val));
    }
    catch (Exception e) {
      realizeError(e);
    }
  }

  /*
   * Receiver API
   */
  public void success(Object val) {
    if (cs.getAndSet(State.FINAL) == State.FINAL) {
      return;
    }

    realizeSeq(val);
  }

  public void error(Exception e) {
    if (cs.getAndSet(State.FINAL) == State.FINAL) {
      return;
    }

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
