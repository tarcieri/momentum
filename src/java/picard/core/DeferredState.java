package picard.core;

import clojure.lang.AFn;
import clojure.lang.IFn;

public class DeferredState extends AFn {
    // Whether or not the future has been maaterialized
    boolean done;

    // The realized value
    Object value;

    // The callback
    IFn callback;

    public DeferredState() {
        done     = false;
        value    = null;
        callback = null;
    }

    public boolean isDone() {
        return done;
    }

    public Object getValue() {
        return value;
    }

    public IFn getCallback() {
        return callback;
    }

    public void setCallback(IFn callback) {
        this.callback = callback;
    }

    public void registerReceiveCallback(IFn callback, IFn onRealized)
        throws Exception {

        boolean done;
        Object  currentValue;

        synchronized(this) {
            if (this.callback != null) {
                throw new Exception("The value has already been realized");
            }

            this.callback = callback;
            done          = this.done;
            currentValue  = this.value;
        }

        if (done) {
            onRealized.invoke(currentValue);
        }
    }

    public void realize(Object value) throws Exception {
        IFn callback;

        synchronized(this) {
            if (done) {
                throw new Exception("The value has already been realized");
            }

            callback   = this.callback;
            this.done  = true;
            this.value = value;
        }

        if (callback != null) {
            callback.invoke(this, value, true);
        }

        synchronized(this) {
            notifyAll();
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
}
