package momentum.async;

import clojure.lang.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Join extends Async<JoinedArgs> {

  class IndexedReceiver implements Receiver {

    final int idx;

    IndexedReceiver(int i) {
      idx = i;
    }

    public void success(Object val) {
      realizeElement(idx, val);
    }

    public void error(Exception err) {
      if (realizeError(err)) {
        for (Object o: async) {
          if (o instanceof Async) {
            ((Async) o).abort(err);
          }
        }
      }
    }
  }

  final List<Object> async;

  /*
   * The list of realized values
   */
  final JoinedArgs arr;

  /*
   * The number of outstanding values
   */
  final AtomicInteger counter = new AtomicInteger();

  public Join(List<Object> vs) {
    int idx = 0, delta = 0;

    async = vs;
    arr   = new JoinedArgs(vs);

    for (Object o: async) {
      if (o instanceof Async) {
        ++delta;
        ((Async) o).receive(new IndexedReceiver(idx));
      }

      ++idx;
    }

    if (counter.addAndGet(delta) == 0) {
      realizeSuccess(arr);
    }
  }

  public boolean abort(Exception err) {
    if (!realizeError(err)) {
      return false;
    }

    for (Object o: async) {
      if (o instanceof Async) {
        ((Async) o).abort(err);
      }
    }

    return true;
  }

  void realizeElement(int idx, Object v) {
    arr.set(idx, v);

    if (counter.decrementAndGet() == 0) {
      realizeSuccess(arr);
    }
  }

}
