package momentum.reactor;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
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

  /*
   * Map of the reactor thread to the reactor
   */
  final HashMap<Thread, Reactor> reactorMap = new HashMap<Thread, Reactor>();

  ReactorTicker ticker;

  static ReactorCluster instance;

  public static ReactorCluster getInstance() throws IOException {
    if (instance == null)
      instance = new ReactorCluster();

    return instance;
  }

  ReactorCluster() throws IOException {
    this(1 * Runtime.getRuntime().availableProcessors());
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

    // Used to coordinate the multi-reactor startup process
    CountDownLatch startLatch = new CountDownLatch(reactors.length);

    // Need an extra spot for the ticker thread
    ExecutorService tp = Executors.newFixedThreadPool(reactors.length + 1);

    ticker = new ReactorTicker(reactors.length, TIMER_INTERVAL);

    for (int i = 0; i < reactors.length; ++i) {
      reactors[i] = new Reactor(this, startLatch, ticker.sources[i], TIMER_INTERVAL);
      tp.submit(reactors[i]);
    }

    // Wait for all the reactor threads to finish setting themselves up.
    try {
      startLatch.await();
    }
    catch (InterruptedException e) {
      // Do nothing
    }

    // Start the ticker
    tp.submit(ticker);

    // Save a reference to the thread pool. This also indicates that the
    // reactor is started.
    threadPool = tp;
  }

  public synchronized void stop() throws IOException, InterruptedException {
    if (!isStarted())
      return;

    for (int i = 0; i < reactors.length; ++i) {
      reactors[i].shutdown();
      reactors[i] = null;
    }

    reactorMap.clear();

    try {
      threadPool.awaitTermination(1000, TimeUnit.MILLISECONDS);
    }
    finally {
      threadPool = null;
    }
  }

  void registerReactorThread(Reactor r, Thread t) {
    synchronized(reactorMap) {
      reactorMap.put(t, r);
    }
  }

  Reactor reactorWithLeastLoad() {
    Reactor curr, ret = null;
    int currLoad, load = Integer.MAX_VALUE;

    for (int i = 0; i < reactors.length; ++i) {
      curr     = reactors[i];
      currLoad = curr.channelCount();

      if (currLoad < load) {
        ret  = curr;
        load = currLoad;
      }
    }

    if (ret == null)
      throw new IllegalStateException("Apparently there are no reactors running.");

    return ret;
  }

  public Reactor currentReactor() {
    Reactor ret = reactorMap.get(Thread.currentThread());

    if (ret != null)
      return ret;

    return reactorWithLeastLoad();
  }

  void register(ChannelHandler handler) throws IOException {
    reactorWithLeastLoad().register(handler);
  }

  public void schedule(Runnable runnable) throws IOException {
    if (!isStarted())
      start();

    currentReactor().schedule(runnable);
  }

  public void scheduleTimeout(Timeout timeout, long ms) throws IOException {
    if (!isStarted())
      start();

    currentReactor().scheduleTimeout(timeout, ms);
  }

  public ServerHandler startTcpServer(UpstreamFactory factory)
      throws IOException {

    if (!isStarted())
      start();

    return reactorWithLeastLoad().startTcpServer(factory);
  }

  public void connectTcpClient(UpstreamFactory factory)
      throws IOException {

    if (!isStarted())
      start();

    currentReactor().connectTcpClient(factory);
  }
}
