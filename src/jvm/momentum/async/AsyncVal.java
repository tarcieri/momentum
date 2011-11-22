package momentum.async;

import clojure.lang.*;
import java.util.*;

public final class AsyncVal extends Async<Object> implements Realizer, IDeref, IBlockingDeref {

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

}
