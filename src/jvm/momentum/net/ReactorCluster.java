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

  /*
   * The thread pool that the reactors run on.
   */
  volatile ExecutorService threadPool;

  /*
   * References to the reactors
   */
  final Reactor [] reactors;

  public ReactorCluster() throws IOException {
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

    ExecutorService tp = Executors.newFixedThreadPool(reactors.length);

    for (int i = 0; i < reactors.length; ++i) {
      reactors[i] = new Reactor(this);
    }

    // Start the reactors once all of them have been initialized. This creates
    // a proper happens-before relationship between all the reactor threads and
    // the list of reactors.
    for (int i = 0; i < reactors.length; ++i) {
      tp.submit(reactors[i]);
    }

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
    reactors[0].register(handler, sendOpen);
  }

  public ReactorServerHandler startTcpServer(TCPServer srv) throws IOException {
    if (!isStarted())
      start();

    return reactors[0].startTcpServer(srv);
  }
}
