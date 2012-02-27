package momentum.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import momentum.buffer.Buffer;

public final class ReactorChannelHandler {

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

    ByteBuffer peek() {
      MessageQueueSegment curr;

      while ((curr = head) != null) {
        ByteBuffer ret = curr.peek();

        if (ret != null)
          return ret;

        head = curr.next;
        release(curr);
      }

      if (head == null)
        tail = null;

      return null;
    }

    ByteBuffer pop() {
      MessageQueueSegment curr;

      while ((curr = head) != null) {
        ByteBuffer ret = curr.pop();

        if (ret != null)
          return ret;

        head = curr.next;
        release(curr);
      }

      if (head == null)
        tail = null;

      return null;
    }

    void push(ByteBuffer b) {
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

  ReactorChannelHandler(SocketChannel ch) {
    channel = ch;
  }

  boolean isOpen() {
    return key != null;
  }

  void sendMessageUpstream(Buffer msg) {
    try {
      upstream.sendMessage(msg);
    }
    catch (Exception e) {
      doAbort(e);
    }
  }

  void sendPauseUpstream() {
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

  void sendResumeUpstream() {
    if (!isPaused)
      return;

    isPaused = false;

    try {
      upstream.sendResume();
    }
    catch (Exception e) {
      doAbort(e);
    }
  }

  public void sendMessageDownstream(Buffer msg) throws IOException {
    if (reactor.onReactorThread()) {
      doSendMessageDownstream(msg);
    }
    else {
      reactor.pushWriteTask(new WriteTask(msg));
    }
  }

  public void sendCloseDownstream() throws IOException {
    if (reactor.onReactorThread()) {
      doClose();
    }
    else {
      reactor.pushCloseTask(new CloseTask());
    }
  }

  public void sendPauseDownstream() throws IOException {
    if (reactor.onReactorThread()) {
      clearOpRead();
    }
    else {
      reactor.pushInterestOpTask(new PauseTask());
    }
  }

  public void sendResumeDownstream() throws IOException {
    if (reactor.onReactorThread()) {
      setOpRead();
    }
    else {
      reactor.pushInterestOpTask(new ResumeTask());
    }
  }

  public void sendAbortDownstream(Exception err) {
    if (reactor.onReactorThread()) {
      doAbort(err);
    }
    else {
      reactor.pushCloseTask(new AbortTask(err));
    }
  }

  void doSendMessageDownstream(Buffer msg) throws IOException {
    if (!isOpen())
      return;

    ByteBuffer buf = msg.toByteBuffer();

    if (!messageQueue.isEmpty()) {
      messageQueue.push(buf);
      return;
    }

    channel.write(buf);

    if (buf.remaining() > 0) {
      // The socket is full!
      messageQueue.push(buf);
      setOpWrite();
      sendPauseUpstream();
    }
  }

  void doClose() throws IOException {
    if (!isOpen())
      return;

    key = null;
    channel.close();

    try {
      upstream.sendClose();
    }
    catch (Exception e) {
      // Ignore
    }
  }

  void doAbort(Exception err) {
    if (!isOpen())
      return;

    key = null;

    try {
      channel.close();
    }
    catch (IOException e) {
      // Ignore
    }

    try {
      upstream.sendAbort(err);
    }
    catch (Exception e) {
      // Ignore
    }
  }

  /*
   * Registers the handler with a given reactor
   */
  void register(Reactor r, boolean sendOpen) throws IOException {
    if (reactor != null)
      return;

    reactor = r;

    key = channel.register(r.selector, SelectionKey.OP_READ, this);

    if (sendOpen) {
      try {
        upstream.sendOpen(channel);
      }
      catch (Exception e) {
        doAbort(e);
      }
    }
  }

  /*
   * ==== Handling key ready events ====
   */

  void processIO(ByteBuffer readBuffer) throws IOException {
    if (processReads(readBuffer))
      processWrites();
  }

  boolean processReads(ByteBuffer readBuffer) throws IOException {
    if (!key.isReadable())
      return true;

    int num = 0;
    Buffer buf;
    byte [] arr;
    boolean fail = true;

    // Prep the buffer
    readBuffer.clear();

    try {
      num  = channel.read(readBuffer);
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

      sendMessageUpstream(buf);
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
    if (!isOpen())
      return;

    if (!key.isWritable())
      return;

    ByteBuffer curr;

    while ((curr = messageQueue.peek()) != null) {
      channel.write(curr);

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
  }

  /*
   * ==== Interest Op helpers ====
   */

  boolean isOpRead() {
    return (key.interestOps() & SelectionKey.OP_READ) != 0;
  }

  void setOpRead() {
    if (key == null)
      return;

    int ops = key.interestOps();

    if ((ops & SelectionKey.OP_READ) == 0) {
      ops |= SelectionKey.OP_READ;
      key.interestOps(ops);
    }
  }

  void clearOpRead() {
    if (key == null)
      return;

    int ops = key.interestOps();

    if ((ops & SelectionKey.OP_READ) != 0) {
      ops &= ~SelectionKey.OP_READ;
      key.interestOps(ops);
    }
  }

  boolean isOpWrite() {
    return (key.interestOps() & SelectionKey.OP_WRITE) != 0;
  }

  void setOpWrite() {
    if (key == null)
      return;

    int ops = key.interestOps();

    if ((ops & SelectionKey.OP_WRITE) == 0) {
      ops |= SelectionKey.OP_WRITE;
      key.interestOps(ops);
    }
  }

  void clearOpWrite() {
    if (key == null)
      return;

    int ops = key.interestOps();

    if ((ops & SelectionKey.OP_WRITE) != 0) {
      ops &= SelectionKey.OP_WRITE;
      key.interestOps(ops);
    }
  }
}
