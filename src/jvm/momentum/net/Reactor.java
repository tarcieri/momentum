package momentum.net;

import java.net.SocketAddress;
import java.nio.channels.*;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import momentum.buffer.Buffer;
import momentum.util.ArrayAtomicQueue;
import momentum.util.LinkedArrayStack;

public final class Reactor implements Runnable {

  class RegisterTask implements ReactorTask {

    final ReactorChannelHandler handler;

    RegisterTask(ReactorChannelHandler h) {
      handler = h;
    }

    public void run() throws IOException {
      doRegister(handler);
    }
  }

  class BindTask implements ReactorTask {

    final ReactorServerHandler handler;

    BindTask(ReactorServerHandler h) {
      handler = h;
    }

    public void run() throws IOException {
      doStartTcpServer(handler);
    }
  }

  class ConnectTask implements ReactorTask {

    final ReactorUpstreamFactory factory;

    ConnectTask(ReactorUpstreamFactory f) {
      factory = f;
    }

    public void run() throws IOException {
      doConnectTcpClient(factory);
    }
  }

  class ShutdownTask implements ReactorTask {

    public void run() throws IOException {
      doShutdown();
    }
  }

  class ScheduleTimeoutTask implements ReactorTask {

    final Timeout timeout;

    final long milliseconds;

    ScheduleTimeoutTask(Timeout t, long ms) {
      timeout = t;
      milliseconds = ms;
    }

    public void run() throws IOException {
      doScheduleTimeout(timeout, milliseconds);
    }
  }

  class CancelTimeoutTask implements ReactorTask {

    final Timeout timeout;

    CancelTimeoutTask(Timeout t) {
      timeout = t;
    }

    public void run() throws IOException {
      doCancelTimeout(timeout);
    }
  }

  static final int TICKS_PER_WHEEL = 1024;

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
   * The channel on which tick events arrive
   */
  final Pipe.SourceChannel tickerChannel;

  /*
   * The timer that handles all timeouts on the current reactor
   */
  ReactorTimer timer;

  /*
   * Used to read data directly off of sockets.
   */
  final Buffer readBuffer = Buffer.allocate(65536).makeTransient();

  /*
   * Queue for all tasks submitted from other threads
   */
  final ArrayAtomicQueue<ReactorTask> taskQueue = new ArrayAtomicQueue<ReactorTask>(131072);

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

  /*
   * Latch that coordinates the multi-reactor startup process.
   */
  final CountDownLatch startLatch;

  /*
   * Flag that tracks whether the reactor should shutdown
   */
  boolean shutdown;

  /*
   * Counts the number of registered channels
   */
  final AtomicInteger channelCount = new AtomicInteger();

  /*
   * Reactor gets initialized with the cluster that owns it. Starting the
   * reactor happens when the cluster submits the reactor to a thread pool.
   */
  Reactor(ReactorCluster c, CountDownLatch latch, Pipe.SourceChannel ch, long timerInterval) throws IOException {
    cluster       = c;
    tickerChannel = ch;
    startLatch    = latch;
    selector      = Selector.open();

    // The ticker channel needs to be non blocking
    tickerChannel.configureBlocking(false);
  }

  static ReactorChannelHandler bindChannel(SocketChannel ch, ReactorUpstreamFactory factory)
      throws IOException {
    ReactorChannelHandler handler = new ReactorChannelHandler(ch);

    ch.configureBlocking(false);

    try {
      handler.upstream = factory.getUpstream(handler);
    }
    catch (Throwable t) {
      // TODO: Add logging
      ch.close();
      return null;
    }

    return handler;
  }

  public void shutdown() {
    if (onReactorThread()) {
      doShutdown();
    }
    else {
      pushTask(new ShutdownTask());
    }
  }

  void doShutdown() {
    shutdown = true;
  }

  int channelCount() {
    return channelCount.get();
  }

  void incrementChannelCount() {
    channelCount.lazySet(channelCount.get() + 1);
  }

  void decrementChannelCount() {
    channelCount.lazySet(channelCount.get() - 1);
  }

  /*
   * Registers a new channel with this reactor
   */
  void register(ReactorChannelHandler handler) throws IOException {
    if (onReactorThread()) {
      doRegister(handler);
    }
    else {
      pushTask(new RegisterTask(handler));
    }
  }

  void doRegister(ReactorChannelHandler handler) throws IOException {
    handler.register(this);
  }

  /*
   * Schedules a timeout
   */
  void scheduleTimeout(Timeout timeout, long ms) {
    if (onReactorThread()) {
      doScheduleTimeout(timeout, ms);
    }
    else {
      pushTask(new ScheduleTimeoutTask(timeout, ms));
    }
  }

  void doScheduleTimeout(Timeout timeout, long ms) {
    timer.schedule(timeout, ms);
  }

  void cancelTimeout(Timeout timeout) {
    if (onReactorThread()) {
      doCancelTimeout(timeout);
    }
    else {
      pushTask(new CancelTimeoutTask(timeout));
    }
  }

  void doCancelTimeout(Timeout timeout) {
    timer.cancel(timeout);
  }

  boolean onReactorThread() {
    return Thread.currentThread() == thread;
  }

  void wakeup() {
    // TODO: Wakeup is an expensive operation.
    selector.wakeup();
  }

  public void run() {
    SelectionKey tickerKey = null;

    // Grab the current thread
    thread = Thread.currentThread();
    timer  = new ReactorTimer(this, TICKS_PER_WHEEL);

    // Register the thread w/ the cluster
    cluster.registerReactorThread(this, thread);

    // Inform the main thread that the setup work has been completed.
    startLatch.countDown();

    // Now wait for all the other reactor threads to finish their startup work
    // and establish a happens-before relationship w/ variables that might be
    // accessed from inside the reactor loop.
    try {
      startLatch.await();
    }
    catch (InterruptedException e) {
      // Do nothing
    }

    try {
      tickerKey = tickerChannel.register(selector, SelectionKey.OP_READ);
    }
    catch (ClosedChannelException e) {
      // TODO: Seeeeems bad bro
    }

    try {
      while (!shutdown) {
        try {
          selector.select();

          processTaskQueue();
          processIO(tickerKey);
        }
        catch (Throwable t) {
          debug(" ++++ ERROR : " + t.getClass().getName());
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
    finally {
      // TODO: Cleanup all open connections
    }
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
  void processTaskQueue() throws IOException {
    while (true) {
      ReactorTask curr = taskQueue.poll();

      if (curr == null)
        return;

      curr.run();
    }
  }

  /*
   * Pushes a task on a queue and wakes up the reactor. If the queue overflows,
   * kills the reactor.
   */
  void pushTask(ReactorTask task) {
    if (!taskQueue.offer(task)) {
      apoptosis("Reactor queue overflow");
    }

    wakeup();
  }

  /*
   * Processes all ready keys.
   */
  void processIO(SelectionKey tickerKey) throws IOException {
    SelectionKey k;
    Iterator<SelectionKey> i;

    i = selector.selectedKeys().iterator();

    while (i.hasNext()) {
      k = i.next();
      i.remove();

      // Is this a necessary check?
      if (!k.isValid()) {
        Object h = k.attachment();

        if (h instanceof ReactorChannelHandler)
          ((ReactorChannelHandler) h).processInvalidKey();

        continue;
      }

      if (k.isAcceptable()) {
        acceptSocket(k);
      }
      else {
        if (k == tickerKey) {
          processTimerTicks();
        }
        else {
          ReactorChannelHandler handler = (ReactorChannelHandler) k.attachment();
          handler.processIO(readBuffer);
        }
      }
    }
  }

  void processTimerTicks() throws IOException {
    // Prep the buffer
    readBuffer.clear();

    int num = readBuffer.transferFrom(tickerChannel);

    if (num > 0) {
      while (num-- > 0)
        timer.tick();
    }
    else if (num < 0) {
      // TODO: Something went wrong.
    }
  }

  void acceptSocket(SelectionKey k) throws IOException {
    ReactorServerHandler srvHandler;
    ReactorChannelHandler chHandler;

    srvHandler = (ReactorServerHandler) k.attachment();
    chHandler  = srvHandler.accept();

    if (chHandler != null)
      cluster.register(chHandler);
  }

  ReactorServerHandler startTcpServer(ReactorUpstreamFactory srv) throws IOException {
    ReactorServerHandler handler = new ReactorServerHandler(this, srv);

    if (onReactorThread()) {
      doStartTcpServer(handler);
    }
    else {
      pushTask(new BindTask(handler));
    }

    return handler;
  }

  void doStartTcpServer(ReactorServerHandler h) throws IOException {
    h.open(selector);
  }

  void connectTcpClient(ReactorUpstreamFactory factory) throws IOException {
    if (onReactorThread()) {
      doConnectTcpClient(factory);
    }
    else {
      pushTask(new ConnectTask(factory));
    }
  }


  void doConnectTcpClient(ReactorUpstreamFactory factory) throws IOException {
    ReactorChannelHandler handler =
      Reactor.bindChannel(SocketChannel.open(), factory);

    handler.connect(factory.getAddr());
    doRegister(handler);
  }

  void debug(String msg) {
    System.out.println(msg);
  }
}
