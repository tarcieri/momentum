package momentum.async;

import clojure.lang.*;

public final class SplicedAsyncSeq extends AsyncSeq implements IPersistentMap {

  final class SelectReceiver implements Receiver {

    final Object key;

    SelectReceiver(Object k) {
      key = k;
    }

    public void success(Object val) {
      if (val == null) {
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

  final IPersistentMap map;

  volatile IPersistentMap nextMap;

  public SplicedAsyncSeq(IPersistentMap m) {
    super((IAsync) new AsyncVal());

    map     = m;
    nextMap = m;

    if (map.count() == 0) {
      pending.put(null);
      return;
    }

    Object key, val;
    IMapEntry entry;
    IAsync asyncVal;

    for (Object o : map) {
      if (o instanceof IMapEntry) {
        entry = (IMapEntry) o;
        key   = entry.key();
        val   = entry.val();

        if (val instanceof IAsync) {
          asyncVal = (IAsync) val;

          // Check if the value is already realized, if it is, short circuit
          if (asyncVal.isRealized() && asyncVal.val() != null) {
            realizeEntrySuccess(key, asyncVal.val());
            break;
          }
          else {
            asyncVal.receive(new SelectReceiver(key));
          }
        }
        else {
          realizeEntrySuccess(key, val);
          break;
        }

      }
      else {
        throw new RuntimeException("Must pass a map to (splice ...)");
      }
    }
  }

  void keyClosed(Object key) {
    if (nextMap.count() > 1) {
      nextMap = nextMap.without(key);
    }
    else {
      pending.put(null);
    }
  }

  void realizeEntrySuccess(Object key, Object val) {
    ISeq seq, next;
    MapEntry entry;

    if (val instanceof Seqable) {
      seq   = ((Seqable)val).seq();
      next  = seq.next();
      entry = new MapEntry(key, seq.first());

      if (next == null) {
        if (map.count() == 0) {
          pending.put(new Cons(entry, null));
        }
        else {
          pending.put(new Cons(entry, new SplicedAsyncSeq(nextMap.without(key))));
        }
      }
      else {
        pending.put(new Cons(entry, new SplicedAsyncSeq(nextMap.assoc(key, next))));
      }
    }
    else {
      entry = new MapEntry(key, val);

      if (map.count() == 0) {
        pending.put(new Cons(entry, null));
      }
      else {
        pending.put(new Cons(entry, new SplicedAsyncSeq(nextMap.without(key))));
      }
    }
  }

  void realizeEntryError(Object key, Exception err) {
    IMapEntry entry;
    Object val;

    pending.abort(err);

    for (Object o : map) {
      entry = (IMapEntry) o;
      val   = entry.val();

      if (key != entry.key() && val instanceof IAsync) {
        ((IAsync)val).abort(err);
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
    return map.entryAt(key);
  }

  /*
   * ILookup interface implementation
   */
  public Object valAt(Object key) {
    return map.valAt(key);
  }

  public Object valAt(Object key, Object notFound) {
    return map.valAt(key, notFound);
  }

  /*
   * IPersistentMap interface implementation
   */
  public SplicedAsyncSeq assoc(Object key, Object val) {
    return new SplicedAsyncSeq(map.assoc(key, val));
  }

  public SplicedAsyncSeq assocEx(Object key, Object val) {
    return new SplicedAsyncSeq(map.assocEx(key, val));
  }

  public SplicedAsyncSeq without(Object key) {
    return new SplicedAsyncSeq(map.without(key));
  }

}
