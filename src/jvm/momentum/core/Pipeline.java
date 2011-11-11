package momentum.core;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IDeref;
import clojure.lang.IBlockingDeref;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class Pipeline extends AFn implements Receivable, IDeref, IBlockingDeref {

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

  public static class Recur {
    final Object val;

    public Recur(Object val) {
      this.val = val;
    }

    public Object val() {
      return val;
    }
  }

  final static class Stage implements Receiver {

    final IFn fn;

    final Stage next;

    final Pipeline pipeline;

    boolean recur;

    Stage(IFn fn, Stage next, Pipeline pipeline) {
      this.fn       = fn;
      this.next     = next;
      this.pipeline = pipeline;
    }

    void put(Object val) {
      try {
        while (true) {
          val = fn.invoke(val);

          if (val instanceof Recur) {
            recur = true;
            val = ((Recur) val).val();
          }

          if (val instanceof Receivable) {
            ((Receivable) val).receive(this);
            return;
          }
          else if (!recur) {
            success(val);
            return;
          }

          recur = false;
        }
      }
      catch (Exception e) {
        error(e);
      }
    }

    public void success(Object val) {
      if (recur) {
        if (pipeline.cs.get() == this) {
          recur = false;
          put(val);
        }
      }
      else if (next != null) {
        // Only move on to the next stage if the current state has not been
        // aborted
        if (pipeline.cs.compareAndSet(this, next)) {
          next.put(val);
        }
      }
      else {
        pipeline.success(val);
      }
    }

    public void error(Exception err) {
      pipeline.error(err);
    }
  }

  final static class FirstStage implements Receiver {

    final Stage next;

    final Pipeline pipeline;

    FirstStage(Stage next, Pipeline pipeline) {
      this.next     = next;
      this.pipeline = pipeline;
    }

    public void success(Object val) {
      if (pipeline.cs.get() == next) {
        next.put(val);
      }
    }

    public void error(Exception err) {
      pipeline.error(err);
    }
  }

  // Just a little hack
  static final Stage FINAL = new Stage(null, null, null);

  /*
   * The first stage in the pipeline
   */
  final Stage head;

  /*
   * The deferred value that will be realized upon completion of the pipeline
   */
  final Deferred result;

  /*
   * List of callbacks to catch various exceptions
   */
  final List<Catcher> catchers;

  /*
   * A callback to be invoked at the end of everything
   */
  final IFn finalizer;

  /*
   * The current state of the pipeline
   */
  final AtomicReference<Stage> cs;

  public Pipeline(List<IFn> stages, List<Catcher> catchers, IFn finalizer) {
    Iterator<IFn> i = stages.iterator();
    Stage curr      = null;

    while (i.hasNext()) {
      curr = new Stage(i.next(), curr, this);
    }

    head   = curr;
    cs     = new AtomicReference<Stage>();
    result = new Deferred();

    this.catchers  = catchers;
    this.finalizer = finalizer;
  }

  public boolean put(final Object v) {
    // Step one is to set the current state to the first stage. The expected
    // current state is null.
    if (!cs.compareAndSet(null, head)) {
      return false;
    }

    if (v instanceof Receivable) {
      Receivable r = (Receivable) v;
      r.receive(new FirstStage(head, this));
    }
    else {
      // The state has successfully been advanced, so we are free to put the
      // value into the first stage.
      head.put(v);
    }

    return true;
  }

  public Object invoke(Object v) {
    return put(v);
  }

  public boolean abort(Exception e) {
    return error(e);
  }

  public boolean isRealized() {
    return result.isRealized();
  }

  public boolean isSuccessful() {
    return result.isSuccessful();
  }

  public boolean isAborted() {
    return result.isAborted();
  }

  public void receive(Receiver r) {
    result.receive(r);
  }

  public Object deref() {
    return result.deref();
  }

  public Object deref(long ms, Object timeoutValue) {
    return result.deref(ms, timeoutValue);
  }

  void success(Object val) {
    final Stage curr = cs.getAndSet(FINAL);

    if (curr == FINAL) {
      return;
    }

    try {
      if (finalizer != null) {
        finalizer.invoke();
      }

      result.put(val);
    }
    catch (Exception err) {
      result.abort(err);
    }
  }

  boolean error(Exception err) {
    // First, do a getAndSet so that the current state can be betched as well
    // as ensure that the state gets transitioned to a final state.
    final Stage curr = cs.getAndSet(FINAL);

    // If the state of the pipeline was not already final before being set,
    // then the pipeline was in a valid state and should be aborted, otherwise,
    // we are done.
    if (curr == FINAL) {
      return false;
    }

    boolean caught = false;
    Object val = null;

    if (catchers != null) {
      try {
        Iterator<Catcher> i = catchers.iterator();

        while (i.hasNext()) {
          Catcher c = i.next();

          if (c.isMatch(err)) {
            val    = c.invoke(err);
            caught = true;
            break;
          }
        }
      }
      catch (Exception e) {
        err = e;
      }
    }

    if (finalizer != null) {
      try {
        finalizer.invoke();
      }
      catch (Exception e) {
        caught = false;
        err    = e;
      }
    }

    if (caught) {
      result.put(val);
    }
    else {
      result.abort(err);
    }

    return true;
  }
}
