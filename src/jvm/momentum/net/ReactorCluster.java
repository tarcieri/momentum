package momentum.net;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/*
 * Manages a cluster of reactors. Ideally there would be one reactor per
 * thread.
 */
public final class ReactorCluster {

  // In milliseconds
  static final long TIMER_INTERVAL = 100;

  /*
   * The thread pool that the reactors run on.
   */
  volatile ExecutorService threadPool;

  /*
   * References to the reactors
   */
  final Reactor [] reactors;

  ReactorTicker ticker;

  static ReactorCluster instance;

  public static ReactorCluster getInstance() throws IOException {
    if (instance == null)
      instance = new ReactorCluster();

    return instance;
  }

  ReactorCluster() throws IOException {
    this(Runtime.getRuntime().availableProcessors());
  }

  public ReactorCluster(int count) throws IOException {
    reactors = new Reactor[count];
  }

  public boolean isStarted() {
    return threadPool != null;
  }

  /*
   * Start all the reactors.
   */
  public synchronized void start() throws IOException {
    if (isStarted())
      return;

    // Need an extra spot for the ticker thread
    ExecutorService tp = Executors.newFixedThreadPool(reactors.length + 1);

    ticker = new ReactorTicker(reactors.length, TIMER_INTERVAL);

    for (int i = 0; i < reactors.length; ++i) {
      reactors[i] = new Reactor(this, ticker.sources[i], TIMER_INTERVAL);
    }

    // Start the reactors once all of them have been initialized. This creates
    // a proper happens-before relationship between all the reactor threads and
    // the list of reactors.
    for (int i = 0; i < reactors.length; ++i) {
      tp.submit(reactors[i]);
    }

    tp.submit(ticker);

    threadPool = tp;
  }

  public synchronized void stop() throws IOException, InterruptedException {
    if (!isStarted())
      return;

    for (int i = 0; i < reactors.length; ++i) {
      reactors[i].shutdown();
      reactors[i] = null;
    }

    try {
      threadPool.awaitTermination(1000, TimeUnit.MILLISECONDS);
    }
    finally {
      threadPool = null;
    }
  }

  void register(ReactorChannelHandler handler, boolean sendOpen) throws IOException {
    // TODO: Do this right
    reactors[0].register(handler, sendOpen);
  }

  public void scheduleTimeout(Timeout timeout, long ms) throws IOException {
    if (!isStarted())
      start();

    // TODO: Do this right
    reactors[0].scheduleTimeout(timeout, ms);
  }

  public ReactorServerHandler startTcpServer(TCPServer srv) throws IOException {
    if (!isStarted())
      start();

    // TODO: Do this right
    return reactors[0].startTcpServer(srv);
  }
}
