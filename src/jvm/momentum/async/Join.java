package momentum.async;

import clojure.lang.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * TODO: Figure out how to abort async values in progress
 */
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
      realizeError(err);
    }
  }

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
    Iterator i = vs.iterator();
    Object o;

    arr = new JoinedArgs(vs);

    while (i.hasNext()) {
      o = i.next();

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

  void realizeElement(int idx, Object v) {
    arr.set(idx, v);

    if (counter.decrementAndGet() == 0) {
      realizeSuccess(arr);
    }
  }

}
