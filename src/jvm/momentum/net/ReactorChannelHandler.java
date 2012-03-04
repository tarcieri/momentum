package momentum.net;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.*;
import momentum.buffer.Buffer;

public final class ReactorChannelHandler {

  enum State {
    BOUND,
    CONNECTED,
    OPEN,
    CLOSED
  }

  /*
   * Used to communicate a write that was initiated off of the reactor thread.
   */
  final class WriteTask implements ReactorTask {

    final Buffer msg;

    WriteTask(Buffer m) {
      msg = m;
    }

    public void run() throws IOException {
      doSendMessageDownstream(msg);
    }
  }

  /*
   * Used to communicate a channel close that was initiated off of the reactor
   * thread.
   */
  final class CloseTask implements ReactorTask {
    public void run() throws IOException {
      doClose();
    }
  }

  final class AbortTask implements ReactorTask {

    final Exception err;

    AbortTask(Exception e) {
      err = e;
    }

    public void run() throws IOException {
      doAbort(err);
    }
  }

  /*
   * Schedule pause events
   */
  final class PauseTask implements ReactorTask {
    public void run() throws IOException {
      clearOpRead();
    }
  }

  final class ResumeTask implements ReactorTask {
    public void run() throws IOException {
      setOpRead();
    }
  }

  /*
   * Queue of pending writes. Implemented as a linked list of arrays. The
   * arrays, represented by MessageQueueSegment are pooled per reactor.
   */
  final class MessageQueue {

    MessageQueueSegment head;

    MessageQueueSegment tail;

    boolean isEmpty() {
      return head == null;
    }

    Buffer peek() {
      MessageQueueSegment curr;

      while ((curr = head) != null) {
        Buffer ret = curr.peek();

        if (ret != null)
          return ret;

        head = curr.next;
        release(curr);
      }

      if (head == null)
        tail = null;

      return null;
    }

    Buffer pop() {
      MessageQueueSegment curr;

      while ((curr = head) != null) {
        Buffer ret = curr.pop();

        if (ret != null)
          return ret;

        head = curr.next;
        release(curr);
      }

      if (head == null)
        tail = null;

      return null;
    }

    void push(Buffer b) {
      if (tail == null) {
        head = tail = acquire();
      }

      if (tail.push(b))
        return;

      tail = tail.next = acquire();

      tail.push(b);
    }

    void releaseAll() {
      MessageQueueSegment curr;

      while ((curr = head) != null) {
        head = curr.next;
        release(curr);
      }

      tail = null;
    }

    MessageQueueSegment acquire() {
      MessageQueueSegment ret;

      ret = reactor.messageQueueSegmentPool.pop();

      if (ret != null)
        return ret;

      return new MessageQueueSegment();
    }

    void release(MessageQueueSegment seg) {
      seg.reset();
      reactor.messageQueueSegmentPool.push(seg);
    }

  }

  /*
   * The current state of the channel
   */
  State cs = State.BOUND;

  /*
   * The reactor that owns the channel. This could change throughout the life
   * of the channel as channels can be moved across reactors to minimize cross
   * thread communication.
   */
  Reactor reactor;

  /*
   * The socket that is being managed.
   */
  final SocketChannel channel;

  /*
   * The selection key for this channel
   */
  SelectionKey key;

  /*
   * The upstream handler that receives all events for the channel
   */
  ReactorUpstream upstream;

  /*
   * Message queue for pending writes
   */
  MessageQueue messageQueue = new MessageQueue();

  /*
   * Is the upstream currently paused?
   */
  boolean isPaused;

  /*
   * Are we currently in an upstream call?
   */
  boolean inUpstream;

  /*
   * The exception that the channel was aborted with
   */
  Exception err;

  ReactorChannelHandler(SocketChannel ch) {
    channel = ch;
  }

  boolean isOpen() {
    return cs == State.OPEN;
  }

  void sendOpenUpstream() throws IOException {
    try {
      inUpstream = true;
      upstream.sendOpen(channel);
      handlePendingUpstreamEvents();
    }
    catch (Exception e) {
      doAbort(e);
    }
    finally {
      inUpstream = false;
    }
  }

  void sendMessageUpstream(Buffer msg) throws IOException {
    if (upstream == null)
      return;

    try {
      inUpstream = true;
      upstream.sendMessage(msg);
      handlePendingUpstreamEvents();
    }
    catch (Exception e) {
      doAbort(e);
    }
    finally {
      inUpstream = false;
    }
  }

  void sendPauseUpstream() throws IOException {
    if (upstream == null)
      return;

    if (isPaused)
      return;

    isPaused = true;

    try {
      upstream.sendPause();
    }
    catch (Exception e) {
      doAbort(e);
    }
  }

  void sendResumeUpstream() throws IOException {
    if (upstream == null)
      return;

    if (!isPaused)
      return;

    isPaused = false;

    try {
      inUpstream = true;
      upstream.sendResume();
      handlePendingUpstreamEvents();
    }
    catch (Exception e) {
      doAbort(e);
    }
    finally {
      inUpstream = false;
    }
  }

  void sendCloseUpstream() {
    if (upstream == null)
      return;

    try {
      upstream.sendClose();
      upstream = null;
    }
    catch (Exception e) {
      // Ignore
    }
  }

  void sendAbortUpstream(Exception e) {
    if (upstream == null)
      return;

    try {
      upstream.sendAbort(e);
      upstream = null;
    }
    catch (Exception e2) {
      // Ignore
    }
  }

  public void sendMessageDownstream(Buffer msg) throws IOException {
    if (reactor.onReactorThread()) {
      doSendMessageDownstream(msg);
    }
    else {
      reactor.pushTask(new WriteTask(msg));
    }
  }

  public void sendCloseDownstream() throws IOException {
    if (reactor.onReactorThread()) {
      if (inUpstream) {
        markClosed();
      }
      else {
        doClose();
      }
    }
    else {
      reactor.pushTask(new CloseTask());
    }
  }

  public void sendPauseDownstream() throws IOException {
    if (reactor.onReactorThread()) {
      clearOpRead();
    }
    else {
      reactor.pushTask(new PauseTask());
    }
  }

  public void sendResumeDownstream() throws IOException {
    if (reactor.onReactorThread()) {
      setOpRead();
    }
    else {
      reactor.pushTask(new ResumeTask());
    }
  }

  public void sendAbortDownstream(Exception err) throws IOException {
    if (reactor.onReactorThread()) {
      if (inUpstream) {
        markAborted(err);
      }
      else {
        doAbort(err);
      }
    }
    else {
      reactor.pushTask(new AbortTask(err));
    }
  }

  void doSendMessageDownstream(Buffer msg) throws IOException {
    if (!isOpen())
      return;

    if (!messageQueue.isEmpty()) {
      // Retain the buffer in case it is transient.
      messageQueue.push(msg.retain());
      return;
    }

    msg.transferTo(channel);

    if (msg.remaining() > 0) {
      // The socket is full!
      messageQueue.push(msg.slice().retain());
      setOpWrite();

      if (!inUpstream)
        sendPauseUpstream();
    }
  }

  void markClosed() throws IOException {
    if (cs == State.CLOSED)
      return;

    cs = State.CLOSED;

    reactor.decrementChannelCount();
    channel.close();
  }

  void markAborted(Exception e) throws IOException {
    if (err != null)
      return;

    err = e;
    markClosed();
  }

  void doClose() throws IOException {
    markClosed();
    sendCloseUpstream();
  }

  void doAbort(Exception e) throws IOException {
    markAborted(e);
    sendAbortUpstream(e);
  }

  void handlePendingUpstreamEvents() throws IOException {
    if (isOpen() && !messageQueue.isEmpty())
      sendPauseUpstream();

    if (!isOpen()) {
      if (err != null) {
        sendAbortUpstream(err);
      }
      else {
        sendCloseUpstream();
      }
    }
  }

  /*
   * Registers the handler with a given reactor
   */
  void register(Reactor r) throws IOException {
    if (reactor != null)
      return;

    reactor = r;
    reactor.incrementChannelCount();

    switch (cs) {
      case CONNECTED:
        cs  = State.OPEN;
        key = channel.register(r.selector, SelectionKey.OP_READ, this);
        sendOpenUpstream();
        return;

      case OPEN:
        key = channel.register(r.selector, SelectionKey.OP_READ, this);
        return;

      case BOUND:
        key = channel.register(r.selector, SelectionKey.OP_CONNECT, this);
        return;
    }
  }

  void connect(SocketAddress addr) throws IOException {
    if (cs != State.BOUND)
      return;

    if (channel.connect(addr)) {
      cs = State.CONNECTED;
    }
  }

  void setConnected() {
    cs = State.CONNECTED;
  }

  /*
   * ==== Handling key events ====
   */

  // TODO: Figure out what could cause this to be invoked
  void processInvalidKey() throws IOException {
    // Just close the connection for now, there might be cases where we would
    // want to doAbort() instead.
    doClose();
  }

  void processIO(Buffer readBuffer) throws IOException {
    if (processReads(readBuffer)) {
      if (cs == State.CLOSED)
        return;

      if (key.isWritable()) {
        processWrites();
      }
      else if (key.isConnectable()) {
        processConnect();
      }
    }
  }

  boolean processReads(Buffer readBuffer) throws IOException {
    if (!key.isReadable())
      return true;

    int num = 0;
    Buffer buf;
    byte [] arr;
    boolean fail = true;

    // Prep the buffer
    readBuffer.clear();

    try {
      num  = readBuffer.transferFrom(channel);
      fail = false;
    }
    catch (IOException e) {
      // Don't need to do anything about this.
    }

    if (num > 0) {
      readBuffer.flip();
      sendMessageUpstream(readBuffer);
    }
    else if (num < 0 || fail) {
      // Some JDK implementations run into an infinite loop without this.
      key.cancel();
      doClose();
      return false;
    }

    return true;
  }

  void processWrites() throws IOException {
    Buffer curr;

    while ((curr = messageQueue.peek()) != null) {
      curr.transferTo(channel);

      // Socket still full
      if (curr.remaining() > 0) {
        return;
      }

      messageQueue.pop();
    }

    // We got through the entire write queue, so remove the write interest op
    // and send an upstream resume
    clearOpWrite();
    sendResumeUpstream();
    return;
  }

  void processConnect() throws IOException {
    // ZOMG, no more interest in OP_CONNECT
    clearOpConnect();

    // Alright, let's try to finish connecting.. SOMETHING MIGHT GO WRONG.
    try {
      if (channel.finishConnect()) {
        // YAY, let'swhat they have to say...
        cs = State.OPEN;
        setOpRead();
        sendOpenUpstream();
      }
      else {
        // Connecting sockets is hard, let's go shopping.
        doClose();
      }
    }
    catch (IOException e) {
      // I am crying :'(
      doAbort(e);
    }
  }

  /*
   * ==== Interest Op helpers ====
   */

  boolean isOpRead() {
    return isOp(SelectionKey.OP_READ);
  }

  void setOpRead() {
    setOp(SelectionKey.OP_READ);
  }

  void clearOpRead() {
    clearOp(SelectionKey.OP_READ);
  }

  boolean isOpWrite() {
    return isOp(SelectionKey.OP_WRITE);
  }

  void setOpWrite() {
    setOp(SelectionKey.OP_WRITE);
  }

  void clearOpWrite() {
    clearOp(SelectionKey.OP_WRITE);
  }

  boolean isOpConnect() {
    return isOp(SelectionKey.OP_CONNECT);
  }

  void setOpConnect() {
    setOp(SelectionKey.OP_CONNECT);
  }

  void clearOpConnect() {
    clearOp(SelectionKey.OP_CONNECT);
  }

  boolean isOp(int op) {
    return (key.interestOps() & op) != 0;
  }

  void setOp(int op) {
    if (key == null)
      return;

    int ops = key.interestOps();

    if ((ops & op) == 0) {
      ops |= op;
      key.interestOps(ops);
    }
  }

  void clearOp(int op) {
    if (key == null)
      return;

    int ops = key.interestOps();

    if ((ops & op) != 0) {
      ops &= ~op;
      key.interestOps(ops);
    }
  }
}
