package momentum.net;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/*
 * Manages a cluster of reactors. Ideally there would be one reactor per
 * thread.
 */
public final class ReactorCluster {

  /*
   * The thread pool that the reactors run on.
   */
  final ExecutorService threadPool;

  /*
   * References to the reactors
   */
  final Reactor [] reactors;

  public ReactorCluster() throws IOException {
    this(Runtime.getRuntime().availableProcessors());
  }

  public ReactorCluster(int count) throws IOException {
    threadPool = Executors.newFixedThreadPool(count);
    reactors   = new Reactor[count];

    for (int i = 0; i < count; ++i ) {
      reactors[i] = new Reactor(this);
    }
  }

  /*
   * Start all the reactors.
   */
  public void start() {
    for (int i = 0; i < reactors.length; ++i) {
      threadPool.submit(reactors[i]);
    }
  }

  public void startTcpServer(TCPServer srv) {
    reactors[0].startTcpServer(srv);
  }
}
