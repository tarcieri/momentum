package momentum.net;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.io.IOException;
import java.util.Iterator;
import momentum.buffer.Buffer;
import momentum.util.ArrayAtomicQueue;
import momentum.util.LinkedArrayStack;

public final class Reactor implements Runnable {

  class BindTask implements ReactorTask {

    final ReactorServerHandler handler;

    BindTask(ReactorServerHandler h) {
      handler = h;
    }

    public void run() throws IOException {
      doStartTcpServer(handler);
    }
  }

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
  final ArrayAtomicQueue<ReactorTask> bindQueue = new ArrayAtomicQueue<ReactorTask>(1024);

  /*
   * Queue of channels to close
   */
  final ArrayAtomicQueue<ReactorTask> closeQueue = new ArrayAtomicQueue<ReactorTask>(8192);

  /*
   * Queue of writes that need to be scheduled
   */
  final ArrayAtomicQueue<ReactorTask> writeQueue = new ArrayAtomicQueue<ReactorTask>(65536);

  /*
   * A pool of segments for the message queue.
   *
   * Each channel handler has a queue of outbound messages that are pending the
   * channel becoming writable. This queue is growable and also uses arrays for
   * each chunk of 1024 messages to improve cache locality. Also, we don't want
   * to constantly be allocating arrays
   */
  final LinkedArrayStack<MessageQueueSegment> messageQueueSegmentPool =
    new LinkedArrayStack<MessageQueueSegment>();

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

  /*
   * Registers a new channel with this reactor
   */
  void register(SocketChannel ch, ReactorUpstreamFactory factory) throws IOException {
    ReactorChannelHandler handler = ReactorChannelHandler.build(this, ch, factory);

    ch.configureBlocking(false);

    handler.key = ch.register(selector, SelectionKey.OP_READ, handler);

    // TODO: have sending open be guarded by an argument
    handler.sendOpenUpstream();
  }

  public void run() {
    // Grab the current thread
    thread = Thread.currentThread();

    while (true) {
      try {
        // Wait for an event on one of the registered channels
        selector.select();

        processQueue(writeQueue);
        processQueue(closeQueue);
        processQueue(bindQueue);

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

  /*
   * Pops off all tasks from a queue and runs them.
   */
  void processQueue(ArrayAtomicQueue<ReactorTask> q) throws IOException {
    while (true) {
      ReactorTask curr = q.poll();

      if (curr == null)
        return;

      curr.run();
    }
  }

  /*
   * Pushes a task on a queue and wakes up the reactor. If the queue overflows,
   * kills the reactor.
   */
  void pushTask(ArrayAtomicQueue<ReactorTask> q, ReactorTask task) {
    if (!q.offer(task)) {
      apoptosis("Reactor queue overflow");
    }

    wakeup();
  }

  void pushWriteTask(ReactorTask task) {
    pushTask(writeQueue, task);
  }

  void pushCloseTask(ReactorTask task) {
    pushTask(closeQueue, task);
  }

  /*
   * Processes all ready keys.
   */
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

      if (k.isAcceptable()) {
        acceptSocket(k);
      }
      else {
        ReactorChannelHandler handler = (ReactorChannelHandler) k.attachment();
        handler.processIO(readBuffer);
      }
    }
  }

  void acceptSocket(SelectionKey k) throws IOException {
    SocketChannel sockCh;


    ReactorServerHandler handler = (ReactorServerHandler) k.attachment();
    sockCh = handler.channel.accept();

    // Register the new SocketChannel with the selector indicating that we'd
    // like to be notified when there is data waiting to be read.
    register(sockCh, handler.server);
  }

  ReactorServerHandler startTcpServer(TCPServer srv) throws IOException {
    ReactorServerHandler handler = new ReactorServerHandler(this, srv);

    if (onReactorThread()) {
      doStartTcpServer(handler);
    }
    else {
      pushTask(bindQueue, new BindTask(handler));
    }

    return handler;
  }

  void doStartTcpServer(ReactorServerHandler h) throws IOException {
    h.open(selector);
  }

  void debug(String msg) {
    System.out.println(msg);
  }
}
