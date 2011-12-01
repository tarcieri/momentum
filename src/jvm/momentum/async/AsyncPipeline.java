package momentum.async;

import clojure.lang.*;
import java.util.*;

public final class AsyncPipeline extends Async<Object> implements Receiver {

  /*
   * Represents a catch clause in doasync
   */
  public static class Catcher {
    final Class klass;
    final IFn callback;

    public Catcher(Class klass, IFn callback) {
      this.klass    = klass;
      this.callback = callback;
    }

    public boolean isMatch(Exception err) {
      return klass.isInstance(err);
    }

    public Object invoke(Exception err) throws Exception {
      return callback.invoke(err);
    }
  }

  /*
   * Indicates that a stage should recur once the return value is realized
   */
  public static class Recur {
    final Object val;

    public Recur(Object val) {
      this.val = val;
    }

    public Object val() {
      return val;
    }
  }

  /*
   * Currently pending async value
   */
  Async pending;

  /*
   * Callback to invoke once the seed value is realized
   */
  IFn handler;

  /*
   * List of callbacks to catch various exceptions
   */
  List<Catcher> catchers;

  /*
   * A callback to be invoked at the end of everything
   */
  IFn finalizer;

  public AsyncPipeline(Object seed, IFn h, List<Catcher> c, IFn f) {
    handler   = h;
    catchers  = c;
    finalizer = f;

    handle(seed);
  }

  private void handle(Object val) {
    if (val instanceof Recur) {
      val = ((Recur) val).val();
    }

    if (val instanceof Async) {
      Async pending = (Async) val;

      synchronized (this) {
        this.pending = pending;
      }

      pending.receive(this);
    }
    else {
      success(val);
    }
  }

  private void doFinalize() throws Exception {
    IFn finalizer;

    synchronized (this) {
      finalizer = this.finalizer;
      this.finalizer = null;
    }

    if (finalizer != null) {
      finalizer.invoke();
    }
  }

  public boolean abort(Exception err) {
    if (!realizeError(err)) {
      return false;
    }

    Async pending;

    synchronized (this) {
      pending = this.pending;

      this.handler  = null;
      this.catchers = null;
      this.pending  = null;
    }

    if (pending != null) {
      pending.abort(err);
    }

    try {
      doFinalize();
    }
    catch (Exception e) {
      // Just discard since the pipeline has already been aborted.
    }

    return true;
  }

  /*
   * Receiver API
   */
  public void success(Object val) {
    IFn h;

    try {
      // Run in a loop in order to handle synchronous recur* without
      // hitting stack overflows.
      while (true) {
        // If the pipeline has been realized already just bail. The most
        // common scenario for this is if the pipeline has been previously
        // aborted.
        synchronized (this) {
          if (isRealized()) {
            return;
          }

          h = this.handler;
        }

        if (h != null) {
          if (val instanceof JoinedArgs) {
            val = h.applyTo(((JoinedArgs) val).seq());
          }
          else {
            val = h.invoke(val);
          }
        }
        // If there is no handler, then the pipeline is done.
        else {
          // Unbox special values
          if (val instanceof JoinedArgs) {
            val = ((JoinedArgs) val).seq();
          }
          else if (val instanceof Recur) {
            val = ((Recur) val).val();
          }

          try {
            doFinalize();
            realizeSuccess(val);
          }
          catch (Exception err) {
            realizeError(err);
          }

          return;
        }

        synchronized (this) {
          if (val instanceof Recur) {
            val = ((Recur) val).val();
          }
          else {
            handler = null;
          }

          if (val instanceof Async) {
            pending = (Async) val;

            if (handler != null && pending.observe() && pending.err == null) {
              val = pending.val;
            }
            else {
              pending.receive(this);
              return;
            }
          }
        }
      }
    }
    catch (Exception e) {
      error(e);
    }
  }

  public void error(Exception err) {
    if (isRealized()) {
      return;
    }

    List<Catcher> catchers;

    synchronized (this) {
      catchers = this.catchers;

      this.handler  = null;
      this.catchers = null;
    }

    if (catchers != null) {
      Iterator<Catcher> i = catchers.iterator();

      try {
        while (i.hasNext()) {
          Catcher c = i.next();

          if (c.isMatch(err)) {
            handle(c.invoke(err));
            return;
          }
        }
      }
      catch (Exception e) {
        err = e;
      }
    }

    try {
      doFinalize();
      realizeError(err);
    }
    catch (Exception e) {
      realizeError(e);
    }
  }
}
