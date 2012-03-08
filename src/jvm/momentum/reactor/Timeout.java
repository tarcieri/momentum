package momentum.reactor;

import java.util.concurrent.atomic.AtomicInteger;

public class Timeout {

  private static final int INIT      = 0;
  private static final int SCHEDULED = 1;
  private static final int CANCELLED = 2;
  private static final int EXPIRED   = 3;

  final AtomicInteger cs = new AtomicInteger(INIT);

  Reactor reactor;

  int targetTick;

  final Runnable task;

  public Timeout(Runnable t) {
    if (t == null)
      throw new NullPointerException("Task cannot be null");

    task = t;
  }

  public boolean isReady() {
    return cs.get() == INIT;
  }

  public boolean isScheduled() {
    return cs.get() == SCHEDULED;
  }

  public boolean isCancelled() {
    return cs.get() == CANCELLED;
  }

  public boolean isExpired() {
    return cs.get() == EXPIRED;
  }

  public void reschedule(long ms) {
    // TODO
  }

  public void cancel() {
    cs.set(CANCELLED);

    if (reactor != null)
      reactor.cancelTimeout(this);
  }

  boolean schedule(Reactor r) {
    reactor = r;
    return cs.compareAndSet(INIT, SCHEDULED);
  }

  void expire() {
    if (!cs.compareAndSet(SCHEDULED, EXPIRED))
      return;

    try {
      task.run();
    }
    catch (Exception e) {
      // Do nothing
    }
  }

}
