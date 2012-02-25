package momentum.net;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.io.IOException;
import java.util.Iterator;
import momentum.buffer.Buffer;
import momentum.util.ArrayAtomicQueue;

public final class Reactor implements Runnable {

  /*
   * Reference to the reactor cluster that owns this reactor. This is used when
   * a new connection is established and the channel is sent to the least busy
   * reactor.
   */
  final ReactorCluster cluster;

  /*
   * The reactor's selector.
   */
  final Selector selector;

  /*
   * Used to read data directly off of sockets.
   */
  final ByteBuffer readBuffer = ByteBuffer.allocate(65536);

  /*
   * Queue of servers waiting to be bound
   */
  final ArrayAtomicQueue<TCPServer> serverQueue = new ArrayAtomicQueue<TCPServer>(1024);

  /*
   * Queue of writes that need to be scheduled
   */
  final ArrayAtomicQueue<ReactorTask> writeQueue = new ArrayAtomicQueue<ReactorTask>(65536);

  /*
   * Set on start, never set again. Used to determine if currently on the
   * reactor thread. There is no reason for synchronization around this
   * variable. If any thread sees the value as null, then it is not the reactor
   * thread.
   */
  Thread thread = null;

  Reactor(ReactorCluster c) throws IOException {
    cluster  = c;
    selector = Selector.open();
  }

  public void run() {

    thread = Thread.currentThread();

    while (true) {
      try {
        // Wait for an event on one of the registered channels
        selector.select();

        bindNewServers();
        processIO();
      }
      catch (Throwable t) {
        debug(t.getMessage());
        t.printStackTrace();
        // Something wack is going on...

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
          Thread.sleep(100);
        }
        catch (InterruptedException e) {
          // Ignore
        }
      }
    }
  }

  boolean onReactorThread() {
    return Thread.currentThread() == thread;
  }

  void wakeup() {
    // TODO: Wakeup is an expensive operation.
    selector.wakeup();
  }

  /*
   * The queues used to communicate across threads are really fast, but do not
   * tolerate overflows. Overflowing should not happen unless the reactor loop
   * gets slowed down by evil callbacks and another thread is hammering the
   * reactor with events. The queues have a fairly large capacity, so
   * overflowing them is bad.
   *
   * So, if a queue does overflow, the reactor will forcibly kill itself and
   * shutdown all open connections. Then it will communicate to the cluster
   * that it went down 
   */
  void apoptosis(String msg) {
    // TODO: Implement
    throw new ReactorApoptosis(msg);
  }

  // Registers a new channel with this reactor
  void register(SocketChannel ch, ReactorUpstreamFactory factory) throws IOException {
    ReactorDownstream dn = new ReactorDownstream(this, ch);
    ReactorUpstream   up = factory.getUpstream(dn);

    ch.configureBlocking(false);
    ch.register(selector, SelectionKey.OP_READ, up);
  }

  void processIO() throws IOException {
    SelectionKey k;
    Iterator<SelectionKey> i;

    i = selector.selectedKeys().iterator();

    while (i.hasNext()) {
      k = i.next();
      i.remove();

      // Is this a necessary check?
      if (!k.isValid()) {
        continue;
      }

      if (k.isReadable()) {
        read(k);
      }
      else if (k.isAcceptable()) {
        acceptSocket(k);
      }
    }
  }

  void close(SocketChannel ch) {
  }

  void acceptSocket(SelectionKey k) throws IOException {
    ServerSocketChannel srvCh;
    SocketChannel sockCh;

    // For an accept to be pending, the channel must be a server socket channel
    srvCh = (ServerSocketChannel) k.channel();

    // Accept the connection and make it non-blocking
    sockCh = srvCh.accept();

    debug("Registering accepted socket");

    // Register the new SocketChannel with the selector indicating that we'd
    // like to be notified when there is data waiting to be read.
    register(sockCh, (TCPServer) k.attachment());
  }

  void bindNewServers() throws IOException {
    TCPServer srv = serverQueue.poll();

    while (srv != null) {
      doStartTcpServer(srv);
      srv = serverQueue.poll();
    }
  }

  void startTcpServer(TCPServer srv) throws IOException {
    if (onReactorThread()) {
      doStartTcpServer(srv);
    }
    else {
      if (!serverQueue.offer(srv)) {
        apoptosis("New server queue overflow");
      }

      wakeup();
    }
  }

  void doStartTcpServer(TCPServer srv) throws IOException {
    SelectionKey k;
    ServerSocketChannel ch;

    ch = ServerSocketChannel.open();
    ch.configureBlocking(false);

    // Bind the socket to the specified address and port
    ch.socket().bind(srv.getBindAddr());

    // Register the channel and indicate an interest in accepting new
    // connections
    k = ch.register(selector, SelectionKey.OP_ACCEPT, srv);
  }

  void queueWrite(ReactorTask task) {
    if (!writeQueue.offer(task)) {
      apoptosis("Write queue overflow");
    }

    wakeup();
  }

  boolean read(SelectionKey k) {
    int num = 0;
    Buffer buf;
    byte [] arr;
    boolean fail = true;
    SocketChannel ch;
    ReactorUpstream up;

    // Grab the channel
    ch = (SocketChannel) k.channel();
    // Grab the upstream
    up = (ReactorUpstream) k.attachment();
    // Prep the buffer
    readBuffer.clear();

    try {
      num  = ch.read(readBuffer);
      fail = false;
    }
    catch (IOException e) {
      // Don't need to do anything about this.
    }

    if (num > 0) {
      readBuffer.flip();

      // Create an array.
      arr = new byte[readBuffer.remaining()];
      readBuffer.get(arr);

      buf = Buffer.wrap(arr);

      up.sendMessage(buf);
    }
    else if (num < 0 || fail) {
      // Some JDK implementations run into an infinite loop without this.
      k.cancel();
      close(ch);
      return false;
    }

    return true;
  }

  void debug(String msg) {
    System.out.println(msg);
  }
}
