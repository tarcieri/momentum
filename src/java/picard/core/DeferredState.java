package picard.core;

import clojure.lang.AFn;
import clojure.lang.IFn;
import java.util.LinkedList;

public class DeferredState extends AFn {
    // Whether or not the future has been maaterialized
    boolean done;

    // The exception that the deferred value was aborted with
    Exception err;

    // Has the exception already been handled
    boolean handled;

    // The realized value
    Object value;

    // The callbacks
    IFn receiveCallback;
    LinkedList<IFn> finallyCallbacks;
    LinkedList<Catch> catchCallbacks;

    public DeferredState() {
        done    = false;
        handled = false;

        finallyCallbacks = new LinkedList<IFn>();
        catchCallbacks   = new LinkedList<Catch>();
    }

    public void registerReceiveCallback(IFn callback) throws Exception {
        boolean done;
        Object  value;

        // Even though it might make sense otherwise, there can only be a single
        // receive callback per deferred value. This is because multiple receive
        // callbacks don't make sense for channels and the abstraction between
        // deferred values and channels needs to be as similar as possible. Also,
        // it is easy to have a receive function send out the value to multiple
        // other functions.
        if (callback == null) {
            throw new NullPointerException("Callback is null");
        }

        synchronized(this) {
            done = this.done && err == null;

            if (receiveCallback != null) {
                throw new Exception("A receive callback has already been registered");
            }

            receiveCallback = callback;
            value = this.value;
        }

        if (done) {
            callback.invoke(this, value, true);
        }
    }

    public void registerCatchCallback(Class klass, IFn callback) throws Exception {
        Catch catchStatement = null;

        if (klass == null) {
            throw new NullPointerException("Class is null");
        } else if (callback == null) {
            throw new NullPointerException("Callback is null");
        }

        synchronized(this) {
            if (this.err != null) {
                catchStatement = new Catch(klass, callback);

                if (!handled && catchStatement.isMatch(this.err)) {
                    handled = true;
                } else {
                    catchStatement = null;
                }
            } else {
                catchCallbacks.add(new Catch(klass, callback));
            }
        }

        if (catchStatement != null) {
            catchStatement.invoke(this.err);
        }
    }

    public void registerFinallyCallback(IFn callback) throws Exception {
        throw new Exception("Not implemented");
    }

    public void realize(Object value) throws Exception {
        IFn callback;

        synchronized(this) {
            if (done) {
                throw new Exception("The value has already been realized");
            }

            this.done  = true;
            this.value = value;
            callback   = receiveCallback;
        }

        try {
            if (callback != null) {
                callback.invoke(this, value, true);
            }
        }
        finally {
            synchronized(this) {
                notifyAll();
            }
        }
    }

    public void abort(Exception err) throws Exception {
        if (err == null) {
            throw new NullPointerException("Exception cannot be null");
        }

        synchronized(this) {
            if (done || this.err != null) {
                throw new Exception("The value has already been realized");
            }

            this.done = true;
            this.err  = err;
        }

        while (true) {
            Catch curr = catchCallbacks.poll();

            if (curr == null) {
                return;
            }

            if (curr.isMatch(err)) {
                this.handled = true;

                try {
                    curr.invoke(err);
                }
                finally {
                    synchronized(this) {
                        notifyAll();
                    }

                    return;
                }
            }
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
            if (done || timeout < 0) {
                return done;
            }

            wait(timeout);

            return done;
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
