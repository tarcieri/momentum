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

  /*
   * The socket that is being managed.
   */
  final SocketChannel channel;

  /*
   * The reactor that owns the channel. This could change throughout the life
   * of the channel as channels can be moved across reactors to minimize cross
   * thread communication.
   */
  Reactor reactor;

  /*
   * The upstream handler that receives all events for the channel
   */
  ReactorUpstream upstream;

  /*
   * Returns a ReactorChannelHandler instance that is ready to start accepting
   * events.
   */
  static ReactorChannelHandler build(Reactor r, SocketChannel ch, ReactorUpstreamFactory factory) {
    ReactorChannelHandler handler = new ReactorChannelHandler(r, ch);
    handler.upstream = factory.getUpstream(handler);
    return handler;
  }

  private ReactorChannelHandler(Reactor r, SocketChannel ch) {
    reactor = r;
    channel = ch;
  }

  void sendOpenUpstream() {
    upstream.sendOpen(channel);
  }

  void sendMessageUpstream(Buffer msg) {
    upstream.sendMessage(msg);
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

  void doSendMessageDownstream(Buffer msg) throws IOException {
    ByteBuffer buf = msg.toByteBuffer();

    System.out.println("Sending message");

    channel.write(buf);

    if (buf.remaining() > 0) {
      System.out.println("Failed to write...");
    }
  }

  void doClose() throws IOException {
    channel.close();
    upstream.sendClose();
  }
}
