package momentum.async;

import clojure.lang.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class AsyncPipeline extends Async<Object> {

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
   * The various states that a pipeline can be in
   */
  enum State {
    ARGS_PENDING,
    INVOKING,
    RET_PENDING,
    CATCHING,
    CATCH_RET_PENDING,
    FINALIZING
  }

  /*
   * If the pipeline is seeded with an async object, ArgsReceiver is
   * used to receive the realized value while maintaining the
   * appropriate state.
   */
  class ArgsReceiver implements Receiver {

    public void success(Object val) {
      if (cs.compareAndSet(State.ARGS_PENDING, State.INVOKING)) {
        invokeHandler(val);
      }
    }

    public void error(Exception err) {
      exceptionThrown(State.ARGS_PENDING, err);
    }
  }

  /*
   * Handles realizing the return value
   */
  class RetReceiver implements Receiver {

    public void success(Object val) {
      if (cs.compareAndSet(State.RET_PENDING, State.FINALIZING)) {
        invokeFinalizer(val, null);
      }
    }

    public void error(Exception err) {
      exceptionThrown(State.RET_PENDING, err);
    }
  }

  /*
   * Handles catch return value realization
   */
  class CatchReceiver implements Receiver {

    public void success(Object val) {
      if (cs.compareAndSet(State.CATCH_RET_PENDING, State.FINALIZING)) {
        invokeFinalizer(val, null);
      }
    }

    public void error(Exception err) {
      if (cs.compareAndSet(State.CATCH_RET_PENDING, State.FINALIZING)) {
        invokeFinalizer(null, err);
      }
    }

  }

  final AtomicReference<State> cs;

  /*
   * Pending normal return async value.
   */
  Async pending;

  /*
   * Pending return value from catch expr. Cannot be combined with
   * pending as it is possible for a thread to set pending after caught
   * has been set
   */
  Async catching;

  /*
   * Callback to invoke once the seed value is realized
   */
  final IFn handler;

  /*
   * List of callbacks to catch various exceptions
   */
  final List<Catcher> catchers;

  /*
   * A callback to be invoked at the end of everything
   */
  final IFn finalizer;

  public AsyncPipeline(Object seed, IFn h, List<Catcher> c, IFn f) {
    handler   = h;
    catchers  = c;
    finalizer = f;

    if (seed instanceof Async) {
      pending = (Async) seed;

      if (handler != null) {
        cs = new AtomicReference<State>(State.ARGS_PENDING);
        pending.receive(new ArgsReceiver());
      }
      else {
        cs  = new AtomicReference<State>(State.RET_PENDING);
        pending.receive(new RetReceiver());
      }
    }
    else {

      if (handler != null) {
        cs = new AtomicReference<State>(State.INVOKING);
        invokeHandler(seed);
      }
      else {
        cs = new AtomicReference<State>(State.FINALIZING);
        invokeFinalizer(seed, null);
      }
    }
  }

  public boolean abort(Exception err) {
    while (true) {
      State curr = cs.get();

      switch (curr) {
        case ARGS_PENDING:
        case INVOKING:
        case RET_PENDING:
          if (catchers != null) {
            if (cs.compareAndSet(curr, State.CATCHING)) {
              if (curr != State.INVOKING) {
                pending.abort(new InterruptedException());
              }

              invokeCatchers(err);
              return true;
            }
          }
          else if (cs.compareAndSet(curr, State.FINALIZING)) {
            if (curr != State.INVOKING) {
              pending.abort(new InterruptedException());
            }

            invokeFinalizer(null, err);
            return true;
          }

          break;

        case CATCHING:
        case CATCH_RET_PENDING:
          if (cs.compareAndSet(curr, State.FINALIZING)) {
            if (curr != State.CATCHING) {
              catching.abort(new InterruptedException());
            }

            invokeFinalizer(null, err);
            return true;
          }

          break;

        default:
          return false;
      }
    }
  }

  private void invokeHandler(Object val) {
    try {
      while (true) {
        // First, invoke the handler
        if (val instanceof JoinedArgs) {
          val = handler.applyTo(((JoinedArgs) val).seq());
        }
        else {
          val = handler.invoke(val);
        }

        if (val instanceof Recur) {
          val = ((Recur) val).val();

          if (val instanceof Async) {
            pending = (Async) val;

            // Avoid stack overflow exceptions w/ recur*
            if (pending.observe() && pending.err == null) {
              val = pending.val;
            }
            else if (cs.compareAndSet(State.INVOKING, State.ARGS_PENDING)) {
              pending.receive(new ArgsReceiver());
              return;
            }
            else {
              // Another thread aborted the pipeline - bail
              pending.abort(new InterruptedException());
              return;
            }
          }
        }
        else {
          if (val instanceof Async) {
            pending = (Async) val;

            if (cs.compareAndSet(State.INVOKING, State.RET_PENDING)) {
              pending.receive(new RetReceiver());
              return;
            }
            else {
              // Another thread aborted the pipeline - bail
              pending.abort(new InterruptedException());
              return;
            }
          }
          else {
            if (cs.compareAndSet(State.INVOKING, State.FINALIZING)) {
              invokeFinalizer(val, null);
            }

            return;
          }
        }

        // Ensure still in invoking state before recuring
        if (cs.get() != State.INVOKING) {
          return;
        }
      }
    }
    catch (Exception e) {
      exceptionThrown(State.INVOKING, e);
    }
  }

  private void exceptionThrown(State from, Exception err) {
    if (catchers != null) {
      if (cs.compareAndSet(from, State.CATCHING)) {
        invokeCatchers(err);
      }
    }
    else if (cs.compareAndSet(from, State.FINALIZING)) {
      invokeFinalizer(null, err);
    }
  }

  private void invokeCatchers(Exception err) {
    try {
      for (Catcher c: catchers) {
        if (c.isMatch(err)) {
          Object val = c.invoke(err);

          if (val instanceof Async) {
            catching = (Async) val;

            if (cs.compareAndSet(State.CATCHING, State.CATCH_RET_PENDING)) {
              catching.receive(new CatchReceiver());
            }
          }
          else if (cs.compareAndSet(State.CATCHING, State.FINALIZING)) {
            invokeFinalizer(val, null);
          }

          return;
        }
      }
    }
    catch (Exception e) {
      err = e;
    }

    if (cs.compareAndSet(State.CATCHING, State.FINALIZING)) {
      invokeFinalizer(null, err);
    }
  }

  private void invokeFinalizer(Object val, Exception err) {
    if (finalizer != null) {
      try {
        finalizer.invoke();
      }
      catch (Exception e) {
        err = e;
      }
    }

    if (err != null) {
      realizeError(err);
    }
    else {
      realizeSuccess(val);
    }
  }
}
