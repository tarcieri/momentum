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

    public static class CallbackRegistrationError extends RuntimeException {
        public CallbackRegistrationError(String msg) {
            super(msg);
        }
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
    IFn finalizeCallback;
    final LinkedList<Rescue> rescueCallbacks;

    public DeferredState() {
        state           = State.INITIALIZED;
        rescueCallbacks = new LinkedList<Rescue>();
    }

    public void registerReceiveCallback(IFn callback) throws Exception {
        if (callback == null) {
            throw new NullPointerException("Callback is null");
        }

        synchronized(this) {
            if (receiveCallback != null) {
                throw new CallbackRegistrationError(alreadyRegistered("receive"));
            }

            receiveCallback = callback;

            if (state != State.RECEIVING) {
                return;
            }
        }

        invokeReceiveCallback();
    }

    public void registerRescueCallback(Class klass, IFn callback) throws Exception {
        if (klass == null) {
            throw new NullPointerException("Class is null");
        } else if (callback == null) {
            throw new NullPointerException("Callback is null");
        }

        final Rescue rescueCallback = new Rescue(klass, callback);

        synchronized(this) {
            if (catchAllCallback != null) {
                throw new CallbackRegistrationError(alreadyRegistered("catch-all"));
            }

            if (finalizeCallback != null) {
                throw new CallbackRegistrationError(alreadyRegistered("finalize"));
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
                rescueCallbacks.add(rescueCallback);
                return;

            default:
                // If the catch statement isn't a match, then just
                // bail out right now.
                if (!rescueCallback.isMatch(err)) {
                    return;
                }

                state = State.CAUGHT;
            }
        }

        invokeRescueCallback(rescueCallback);
    }

    public void registerFinalizeCallback(IFn callback) throws Exception {
        if (callback == null) {
            throw new NullPointerException("Callback is null");
        }

        synchronized(this) {
            if (finalizeCallback != null) {
                throw new CallbackRegistrationError(alreadyRegistered("finalize"));
            }

            if (catchAllCallback != null) {
                throw new CallbackRegistrationError(alreadyRegistered("catch-all"));
            }

            finalizeCallback = callback;

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
                throw new CallbackRegistrationError(alreadyRegistered("catch-all"));
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
                throw new RuntimeException("The value has already been realized or aborted");
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

        State currentState;
        Rescue rescueCallback = null;

        synchronized(this) {
            // If an exception is thrown when invoking the realize
            // callback, then the abort method is called with internal
            // set to true.
            if ((internal && state != State.RECEIVING) ||
                (!internal && state != State.INITIALIZED)) {
                throw new RuntimeException("The value has already been realized or aborted");
            }

            err   = e;
            state = State.ABORTING;

            Iterator<Rescue> i = rescueCallbacks.iterator();

            while (i.hasNext()) {
                rescueCallback = i.next();

                if (rescueCallback.isMatch(err)) {
                    state = State.CAUGHT;
                    break;
                }
            }

            if (state != State.CAUGHT && finalizeCallback != null) {
                state = State.FINALIZING;
            } else if (state != State.CAUGHT) {
                return;
            }

            currentState = state;
        }

        if (currentState == State.CAUGHT) {
            invokeRescueCallback(rescueCallback);
        }
        else {
            invokeFinallyCallback();
        }
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

            if (finalizeCallback == null) {
                return;
           }
        }

        invokeFinallyCallback();
    }

    private void invokeRescueCallback(Rescue callback) {
        try {
            callback.invoke(err);

            synchronized(this) {
                if (finalizeCallback == null) {
                    return;
                }
            }

            invokeFinallyCallback();
        } catch (Exception e) {
            State currentState;

            synchronized(this) {
                err = e;

                if (finalizeCallback != null) {
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
            finalizeCallback.invoke();
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

    private String alreadyRegistered(String name) {
        return "A " + name + " callback has already been registered";
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

    private class Rescue {
        final Class klass;
        final IFn   callback;

        public Rescue(Class klass, IFn callback) {
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
