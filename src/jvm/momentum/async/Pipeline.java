package momentum.async;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IDeref;
import clojure.lang.IBlockingDeref;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class Pipeline extends Async<Object> {

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
   * A stage in the pipeline
   */
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
      Async v;

      try {
        while (true) {
          if (val instanceof JoinedArgs) {
            val = fn.applyTo(((JoinedArgs) val).seq());
          }
          else {
            val = fn.invoke(val);
          }

          if (val instanceof Recur) {
            recur = true;
            val = ((Recur) val).val();
          }

          if (val instanceof Async) {
            v = (Async) val;

            if (recur && v.observe()) {
              val = v.val;
            }
            else {
              v.receive(this);
              return;
            }
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

  // Just a little harmless hack
  static final Stage FINAL = new Stage(null, null, null);

  /*
   * The first stage in the pipeline
   */
  final Stage head;

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
  final AtomicReference<Stage> cs = new AtomicReference<Stage>();

  public Pipeline(List<IFn> stages, List<Catcher> catchers, IFn finalizer) {
    Iterator<IFn> i = stages.iterator();
    Stage curr      = null;

    while (i.hasNext()) {
      curr = new Stage(i.next(), curr, this);
    }

    head = curr;

    this.catchers  = catchers;
    this.finalizer = finalizer;
  }

  public boolean put(final Object v) {
    if (v instanceof Async) {
      Stage first = new Stage(null, head, this);

      // First make sure that the pipeline state is correct
      if (!cs.compareAndSet(null, first)) {
        return false;
      }

      ((Async) v).receive(first);
    }
    else if (head != null) {
      // First make sure that the pipeline state is correct
      if (!cs.compareAndSet(null, head)) {
        return false;
      }

      // The state has successfully been advanced, so we are free to put the
      // value into the first stage.
      head.put(v);
    }
    else {
      return success(v);
    }

    return true;
  }

  public boolean abort(Exception e) {
    return error(e);
  }

  public Object invoke(Object v) {
    return put(v);
  }

  boolean success(Object val) {
    final Stage curr = cs.getAndSet(FINAL);

    if (curr == FINAL) {
      return false;
    }

    try {
      if (finalizer != null) {
        finalizer.invoke();
      }

      realizeSuccess(val);
    }
    catch (Exception err) {
      realizeError(err);
    }

    return true;
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
      realizeSuccess(val);
    }
    else {
      realizeError(err);
    }

    return true;
  }
}
