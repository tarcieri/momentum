package momentum.async;

import clojure.lang.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class AsyncSeq extends Async<ISeq> implements ISeq, Sequential, List, Receiver {

  enum State {
    FRESH,
    INVOKING,
    PENDING,
    FINAL
  }

  final AtomicReference<State> cs = new AtomicReference<State>(State.FRESH);

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
  }

  Object setup() {
    return fn.invoke();
  }

  /*
   * Evaluate the body of the async seq only once. If the return value is an
   * async value of some kind, then register a callback on it.
   */
  public final boolean observe() {
    if (!cs.compareAndSet(State.FRESH, State.INVOKING)) {
      return isRealized();
    }

    try {
      Object ret = setup();

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
  public final boolean abort(Exception err) {
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

  private final void realizeSeq(Object val) {
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
  public final void success(Object val) {
    if (cs.getAndSet(State.FINAL) == State.FINAL) {
      return;
    }

    realizeSeq(val);
  }

  public final void error(Exception e) {
    if (cs.getAndSet(State.FINAL) == State.FINAL) {
      return;
    }

    realizeError(e);
  }

  final void ensureSuccess() {
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
  final public ISeq seq() {
    if (isRealized()) {
      if (err != null) {
        throw Util.runtimeException(err);
      }

      return val;
    }

    return this;
  }

  final public Object first() {
    ensureSuccess();

    if (val == null) {
      return null;
    }

    return val.first();
  }

  final public ISeq next() {
    ensureSuccess();

    if (val == null) {
      return null;
    }

    return val.next();
  }

  final public ISeq more() {
    ensureSuccess();

    if (val == null) {
      return PersistentList.EMPTY;
    }

    return val.more();
  }

  final public ISeq cons(Object o) {
    return new Cons(o, seq());
  }

  final public int count() {
    int c = 0;

	  for (ISeq s = seq(); s != null; s = s.next()) {
      ++c;
    }

    return c;
  }

  final public IPersistentCollection empty() {
    return PersistentList.EMPTY;
  }

  final public boolean equiv(Object o) {
    return equals(o);
  }

  final public boolean equals(Object o) {
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

  final public Object[] toArray() {
  	return RT.seqToArray(seq());
  }

  final public boolean add(Object o) {
  	throw new UnsupportedOperationException();
  }

  final public boolean remove(Object o) {
  	throw new UnsupportedOperationException();
  }

  final public boolean addAll(Collection c) {
  	throw new UnsupportedOperationException();
  }

  final public void clear() {
  	throw new UnsupportedOperationException();
  }

  final public boolean retainAll(Collection c) {
  	throw new UnsupportedOperationException();
  }

  final public boolean removeAll(Collection c) {
  	throw new UnsupportedOperationException();
  }

  final public boolean containsAll(Collection c) {
  	for (Object o : c) {
  		if (!contains(o)) {
  			return false;
      }
  	}

  	return true;
  }

  final public Object[] toArray(Object[] a) {
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

  final public int size() {
  	return count();
  }

  final public boolean isEmpty() {
  	return seq() == null;
  }

  final public boolean contains(Object o) {
  	for (ISeq s = seq(); s != null; s = s.next()) {
  		if (Util.equiv(s.first(), o)) {
  			return true;
      }
  	}

  	return false;
  }

  final public Iterator iterator() {
  	return new SeqIterator(seq());
  }

  /*
   * java.util.List API
   */

  @SuppressWarnings("unchecked")
  final List reify() {
    return new ArrayList<Object>(this);
  }

  final public List subList(int fromIndex, int toIndex) {
  	return reify().subList(fromIndex, toIndex);
  }

  final public Object set(int index, Object element) {
  	throw new UnsupportedOperationException();
  }

  final public Object remove(int index) {
  	throw new UnsupportedOperationException();
  }

  final public int indexOf(Object o) {
  	ISeq s = seq();

  	for (int i = 0; s != null; s = s.next(), i++) {
  		if (Util.equiv(s.first(), o)) {
  			return i;
      }
    }

  	return -1;
  }

  final public int lastIndexOf(Object o) {
  	return reify().lastIndexOf(o);
  }

  final public ListIterator listIterator() {
  	return reify().listIterator();
  }

  final public ListIterator listIterator(int index) {
  	return reify().listIterator(index);
  }

  final public Object get(int index) {
  	return RT.nth(this, index);
  }

  final public void add(int index, Object element) {
  	throw new UnsupportedOperationException();
  }

  final public boolean addAll(int index, Collection c) {
  	throw new UnsupportedOperationException();
  }

}
