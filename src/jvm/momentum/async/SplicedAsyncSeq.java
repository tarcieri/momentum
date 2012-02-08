package momentum.async;

import clojure.lang.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SplicedAsyncSeq extends AsyncSeq implements IPersistentMap {

  final class SelectReceiver implements Receiver {

    final Object key;
    final boolean isAsyncSeq;

    SelectReceiver(Object k, boolean aseq) {
      key = k;
      isAsyncSeq = aseq;
    }

    public void success(Object val) {
      if (val == null && isAsyncSeq) {
        keyClosed(key);
      }
      else {
        realizeEntrySuccess(key, val);
      }
    }

    public void error(Exception err) {
      realizeEntryError(key, err);
    }

  }

  final LinkedHashMap<Object,Object> map;

  final AtomicInteger keysRemaining;

  final AtomicReference<AsyncVal> asyncVal = new AtomicReference<AsyncVal>();

  public SplicedAsyncSeq(final LinkedHashMap<Object,Object> m) {
    super(null);

    map = m;
    keysRemaining = new AtomicInteger(m.size());
  }

  Object setup() {
    AsyncVal p = new AsyncVal();

    asyncVal.set(p);

    for (Entry<Object,Object> entry : map.entrySet()) {

      // Handle async and sync values differently.
      if (entry.getValue() instanceof IAsync) {
        IAsync val = (IAsync) entry.getValue();

        // Short circuit if the value is already realized
        if (val.isRealized() && val.val() != null) {
          realizeEntrySuccess(entry.getKey(), val.val());
          return p;
        }
        else {
          val.receive(new SelectReceiver(entry.getKey(), val instanceof AsyncSeq));
        }
      }
      else {
        realizeEntrySuccess(entry.getKey(), entry.getValue());
        return p;
      }
    }

    return p;
  }

  LinkedHashMap<Object,Object> cloneMap() {
    LinkedHashMap<Object,Object> ret = new LinkedHashMap<Object,Object>(map.size());

    for (Entry<Object,Object> entry : map.entrySet()) {

      if (entry.getValue() instanceof AsyncSeq) {
        AsyncSeq seq = (AsyncSeq) entry.getValue();

        // Skip any realized async seqs
        if (seq.isRealized() && seq.val() == null) {
          continue;
        }
      }

      ret.put(entry.getKey(), entry.getValue());
    }

    return ret;
  }

  AsyncVal acquireVal() {
    AsyncVal p = asyncVal.get();

    if (p == null) {
      return null;
    }
    else if (!asyncVal.compareAndSet(p, null)) {
      return null;
    }

    return p;
  }

  void keyClosed(Object key) {
    if (keysRemaining.decrementAndGet() > 0) {
      return;
    }

    AsyncVal p = acquireVal();

    if (p != null) {
      p.put(null);
    }
  }

  void realizeEntrySuccess(Object key, Object val) {
    AsyncVal p = acquireVal();

    if (p == null) {
      return;
    }

    ISeq seq, next;
    MapEntry entry;

    if (val instanceof Seqable) {
      seq   = ((Seqable)val).seq();
      next  = seq.next();
      entry = new MapEntry(key, seq.first());

      if (next == null) {
        if (keysRemaining.get() > 1) {
          p.put(new Cons(entry, without(key)));
        }
        else {
          p.put(new Cons(entry, null));
        }
      }
      else {
        p.put(new Cons(entry, assoc(key, next)));
      }
    }
    else {
      entry = new MapEntry(key, val);

      if (keysRemaining.get() > 1) {
        p.put(new Cons(entry, without(key)));
      }
      else {
        p.put(new Cons(entry, null));
      }
    }
  }

  void abortSelectedValues(Exception err) {
    for (Entry<Object,Object> entry : map.entrySet()) {
      Object val = entry.getValue();

      if (val instanceof IAsync) {
        ((IAsync)val).abort(err);
      }
    }
  }

  void realizeEntryError(Object key, Exception err) {
    AsyncVal p = acquireVal();

    if (p == null) {
      return;
    }

    if (p.abort(err)) {
      abortSelectedValues(err);
    }
  }

  public boolean abort(Exception err) {
    boolean ret = super.abort(err);
    abortSelectedValues(err);
    return ret;
  }

  /*
   * Associative interface implementation
   */
  public boolean containsKey(Object key) {
    return map.containsKey(key);
  }

  public IMapEntry entryAt(Object key) {
    Object val = map.get(key);

    if (val != null) {
      return new MapEntry(key, val);
    }

    return null;
  }

  /*
   * ILookup interface implementation
   */
  public Object valAt(Object key) {
    return map.get(key);
  }

  public Object valAt(Object key, Object notFound) {
    Object val = map.get(key);

    if (val == null) {
      return notFound;
    }

    return val;
  }

  /*
   * IPersistentMap interface implementation
   */
  public SplicedAsyncSeq assoc(Object key, Object val) {
    LinkedHashMap<Object,Object> newMap = cloneMap();

    newMap.put(key, val);

    return new SplicedAsyncSeq(newMap);
  }

  public SplicedAsyncSeq assocEx(Object key, Object val) {
    if (map.containsKey(key)) {
      throw new RuntimeException("Key already present");
    }

    return assoc(key, val);
  }

  public SplicedAsyncSeq without(Object key) {
    LinkedHashMap<Object,Object> newMap = cloneMap();
    newMap.remove(key);

    return new SplicedAsyncSeq(newMap);
  }

}
