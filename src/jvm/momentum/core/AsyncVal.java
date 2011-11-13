package momentum.core;

import clojure.lang.*;
import java.util.*;

public final class AsyncVal extends Async<Object> implements IDeref, IBlockingDeref {

  public static AsyncVal aborted(Exception e) {
    AsyncVal ret = new AsyncVal();
    ret.abort(e);
    return ret;
  }

  public static AsyncVal realized(Object v) {
    AsyncVal ret = new AsyncVal();
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
    // Attempt to wait for the async value to become realized
    if (block(ms)) {
      if (err != null) {
        throw Util.runtimeException(err);
      }
      else {
        return val;
      }
    }

    return timeoutValue;
  }
}
