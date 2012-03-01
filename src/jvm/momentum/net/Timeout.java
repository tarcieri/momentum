package momentum.net;

public class Timeout {

  private static final int INIT      = 0;
  private static final int SCHEDULED = 1;
  private static final int CANCELLED = 2;
  private static final int EXPIRED   = 3;

  volatile int cs = INIT;

  Reactor reactor;

  int targetTick;

  final Runnable task;

  public Timeout(Runnable t) {
    if (t == null)
      throw new NullPointerException("Task cannot be null");

    task = t;
  }

  public boolean isReady() {
    return cs == INIT;
  }

  public boolean isScheduled() {
    return cs == SCHEDULED;
  }

  public boolean isCancelled() {
    return cs == CANCELLED;
  }

  public boolean isExpired() {
    return cs == EXPIRED;
  }

  public void reschedule(long ms) {
    // TODO
  }

  public void cancel() {
    if (isReady())
      throw new IllegalStateException("The timeout has not yet been scheduled");

    reactor.cancelTimeout(this);
  }

  void schedule() {
    cs = SCHEDULED;
  }

  void expire() {
    if (cs != SCHEDULED)
      return;

    cs = EXPIRED;

    try {
      task.run();
    }
    catch (Exception e) {
      // Do nothing
    }
  }

  void transitionCanceled() {
    cs = CANCELLED;
  }

}
