package momentum.async;

import clojure.lang.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

  final LinkedHashMap map;

  final AtomicBoolean isComplete;

  final AtomicInteger keysRemaining;

  public SplicedAsyncSeq(LinkedHashMap m) {
    super((IAsync) new AsyncVal());

    map           = m;
    isComplete    = new AtomicBoolean();
    keysRemaining = new AtomicInteger(map.size());

    if (map.size() == 0) {
      pending.put(null);
      return;
    }

    for (Entry entry : (Set<Entry>) map.entrySet()) {

      // Handle async and sync values differently.
      if (entry.getValue() instanceof IAsync) {
        IAsync val = (IAsync) entry.getValue();

        // Short circuit if the value is already realized
        if (val.isRealized() && val.val() != null) {
          realizeEntrySuccess(entry.getKey(), val.val());
          break;
        }
        else {
          val.receive(new SelectReceiver(entry.getKey(), val instanceof AsyncSeq));
        }
      }
      else {
        realizeEntrySuccess(entry.getKey(), entry.getValue());
        break;
      }
    }
  }

  LinkedHashMap cloneMap() {
    LinkedHashMap ret = new LinkedHashMap(map.size());

    for (Entry entry : (Set<Entry>) map.entrySet()) {

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

  void keyClosed(Object key) {
    if (keysRemaining.decrementAndGet() > 0) {
      return;
    }

    pending.put(null);
  }

  void realizeEntrySuccess(Object key, Object val) {
    if (isComplete.getAndSet(true)) {
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
          pending.put(new Cons(entry, without(key)));
        }
        else {
          pending.put(new Cons(entry, null));
        }
      }
      else {
        pending.put(new Cons(entry, assoc(key, next)));
      }
    }
    else {
      entry = new MapEntry(key, val);

      if (keysRemaining.get() > 1) {
        pending.put(new Cons(entry, without(key)));
      }
      else {
        pending.put(new Cons(entry, null));
      }
    }
  }

  void realizeEntryError(Object key, Exception err) {
    if (isComplete.getAndSet(true)) {
      return;
    }

    if (pending.abort(err)) {
      for (Entry entry : (Set<Entry>) map.entrySet()) {
        Object val = entry.getValue();

        if (key != entry.getKey() && val instanceof IAsync) {
          ((IAsync)val).abort(err);
        }
      }
    }
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
    LinkedHashMap newMap = cloneMap();

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
    LinkedHashMap newMap = cloneMap();
    newMap.remove(key);

    return new SplicedAsyncSeq(newMap);
  }

}
