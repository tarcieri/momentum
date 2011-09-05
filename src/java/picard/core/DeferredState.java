package picard.core;

import clojure.lang.AFn;
import clojure.lang.IFn;
import java.util.Iterator;
import java.util.LinkedList;

// Even though it might make sense otherwise, there can only be a
// single receive callback per deferred value. This is because
// multiple receive callbacks don't make sense for channels and the
// abstraction between deferred values and channels needs to be as
// similar as possible. Also, it is easy to have a receive function
// send out the value to multiple other functions.
//
// TODO: Currently, callbacks can be fired in parallel on different
// threads. It probably makes sense to serialize them.
public class DeferredState extends AFn {
    public enum State {
        INITIALIZED,
        RECEIVING,
        SUCCEEDED,
        ABORTING,
        CAUGHT,
        FAILING,
        FINALIZING,
        FAILED
    }

    // What state are we currently in?
    State state;

    // The realized value
    Object value;

    // The exception that the deferred value was aborted with
    Exception err;

    // The callbacks
    IFn receiveCallback;
    IFn catchAllCallback;
    IFn finallyCallback;
    final LinkedList<Catch> catchCallbacks;

    public DeferredState() {
        state          = State.INITIALIZED;
        catchCallbacks = new LinkedList<Catch>();
    }

    public void registerReceiveCallback(IFn callback) throws Exception {
        if (callback == null) {
            throw new NullPointerException("Callback is null");
        }

        synchronized(this) {
            if (receiveCallback != null) {
                throw new Exception("A receive callback has already been registered");
            }

            receiveCallback = callback;

            if (state != State.RECEIVING) {
                return;
            }
        }

        invokeReceiveCallback();
    }

    public void registerCatchCallback(Class klass, IFn callback) throws Exception {
        if (klass == null) {
            throw new NullPointerException("Class is null");
        } else if (callback == null) {
            throw new NullPointerException("Callback is null");
        }

        final Catch catchCallback = new Catch(klass, callback);

        synchronized(this) {
            if (catchAllCallback != null) {
                throw new Exception("A catch-all callback has already been registered");
            }

            switch (state) {
            case SUCCEEDED:
            case CAUGHT:
            case FAILING:
            case FAILED:
                // There is no need for any further catch statements,
                // so just bail out now.
                return;

            case INITIALIZED:
            case RECEIVING:
                catchCallbacks.add(catchCallback);
                return;

            default:
                // If the catch statement isn't a match, then just
                // bail out right now.
                if (!catchCallback.isMatch(err)) {
                    return;
                }

                state = State.CAUGHT;
            }
        }

        invokeCatchCallback(catchCallback);
    }

    public void registerFinallyCallback(IFn callback) throws Exception {
        if (callback == null) {
            throw new NullPointerException("Callback is null");
        }

        synchronized(this) {
            if (finallyCallback != null) {
                throw new Exception("A finally callback has already been registered");
            }

            if (catchAllCallback != null) {
                throw new Exception("A catch-all callback has already been registered");
            }

            finallyCallback = callback;

            switch (state) {
            case INITIALIZED:
            case RECEIVING:
            case ABORTING:
            case FAILED:
                return;

            case FAILING:
                state = State.FINALIZING;
            }
        }

        invokeFinallyCallback();
    }

    public void registerCatchAllCallback(IFn callback) throws Exception {
        if (callback == null) {
            throw new NullPointerException("Callback is null");
        }

        synchronized(this) {
            if (catchAllCallback != null) {
                throw new Exception("A catch-all callback has already been registered");
            }

            catchAllCallback = callback;

            if (state != State.FAILING) {
                return;
            }

            state = State.FAILED;
        }

        invokeCatchAllCallback();
    }

    public void realize(Object v) throws Exception {
        synchronized(this) {
            if (state != State.INITIALIZED) {
                throw new Exception("The value has already been realized or aborted");
            }

            value = v;
            state = State.RECEIVING;

            if (receiveCallback == null) {
                return;
            }
        }

        invokeReceiveCallback();
    }

    public void abort(Exception e, boolean internal) throws Exception {
        if (e == null) {
            throw new NullPointerException("Exception cannot be null");
        }

        Catch catchCallback = null;

        synchronized(this) {
            // If an exception is thrown when invoking the realize
            // callback, then the abort method is called with internal
            // set to true.
            if ((internal && state != State.RECEIVING) || state != State.INITIALIZED) {
                throw new Exception("The value has already been realized or aborted");
            }

            err   = e;
            state = State.ABORTING;

            Iterator<Catch> i = catchCallbacks.iterator();

            while (i.hasNext()) {
                catchCallback = i.next();

                if (catchCallback.isMatch(err)) {
                    state = State.CAUGHT;
                    break;
                }
            }

            if (state != State.CAUGHT) {
                return;
            }
        }

        invokeCatchCallback(catchCallback);
    }

    private void invokeReceiveCallback() throws Exception {
        try {
            receiveCallback.invoke(this, value, true);
        }
        catch (Exception e) {
            abort(e, true);
            return;
        }

        synchronized(this) {
            state = State.SUCCEEDED;

            if (finallyCallback == null) {
                return;
            }
        }

        invokeFinallyCallback();
    }

    private void invokeCatchCallback(Catch callback) {
        try {
            callback.invoke(err);

            synchronized(this) {
                if (finallyCallback == null) {
                    return;
                }
            }

            invokeFinallyCallback();
        } catch (Exception e) {
            State currentState;

            synchronized(this) {
                err = e;

                if (finallyCallback != null) {
                    state = State.FINALIZING;
                }
                else if (catchAllCallback != null) {
                    state = State.FAILED;
                }
                else {
                    state = State.FAILING;
                }

                currentState = state;
            }

            switch (currentState) {
            case FINALIZING:
                invokeFinallyCallback();
                break;

            case FAILED:
                invokeCatchAllCallback();
                break;
            }
        }
    }

    private void invokeFinallyCallback() {
        try {
            finallyCallback.invoke();
        }
        catch (Exception e) {
            synchronized(this) {
                err   = e;
                state = State.FINALIZING;
            }
        }

        synchronized(this) {
            if (state == State.SUCCEEDED) {
                return;
            }
            else if (catchAllCallback == null) {
                state = State.FAILING;
                return;
            }

            state = State.FAILED;
        }

        invokeCatchAllCallback();
    }

    private void invokeCatchAllCallback() {
        try {
            catchAllCallback.invoke(err);
        }
        catch (Exception e) {
            // If an exception is caught, then we're really boned.
        }
    }

    // ==== Extra stuff

    private boolean isComplete() {
        switch (state) {
        case SUCCEEDED:
            return true;

        default:
            return false;
        }
    }

    public Object invoke(Object value) throws Exception {
        realize(value);
        return null;
    }

    public boolean await(long timeout) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        synchronized(this) {
            if (isComplete() || timeout < 0) {
                return isComplete();
            }

            wait(timeout);

            return isComplete();
        }
    }

    private class Catch {
        final Class klass;
        final IFn   callback;

        public Catch(Class klass, IFn callback) {
            this.klass    = klass;
            this.callback = callback;
        }

        public boolean isMatch(Exception err) {
            return klass.isInstance(err);
        }

        public void invoke(Exception err) throws Exception {
            callback.invoke(err);
        }
    }
}
