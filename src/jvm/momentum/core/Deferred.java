package momentum.core;

import clojure.lang.IDeref;
import clojure.lang.IBlockingDeref;
import java.util.LinkedList;

public final class Deferred extends Async<Object> implements IDeref, IBlockingDeref {

  public static Deferred aborted(Exception e) {
    Deferred ret = new Deferred();
    ret.abort(e);
    return ret;
  }

  public static Deferred realized(Object v) {
    Deferred ret = new Deferred();
    ret.put(v);
    return ret;
  }

  public boolean put(Object v) {
    return realizeSuccess(v);
  }

  public Object invoke(Object v) {
    return realizeSuccess(v);
  }

  public boolean abort(Exception e) {
    return realizeError(e);
  }

  public Object deref() {
    return deref(-1, null);
  }

  public Object deref(long ms, Object timeoutValue) {
    if (!isRealized()) {

      if (ms == 0) {
        return timeoutValue;
      }

      block(ms);

      if (!isRealized()) {
        return timeoutValue;
      }
    }

    if (err != null) {
      throw new RuntimeException(err);
    }
    else {
      return val;
    }
  }
}
