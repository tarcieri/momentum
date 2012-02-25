package momentum.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import momentum.buffer.Buffer;

public final class ReactorDownstream {

  final class WriteTask implements ReactorTask {

    final Buffer msg;

    WriteTask(Buffer m) {
      msg = m;
    }

    public void run() throws IOException {
      doSendMessage(msg);
    }
  }

  final class CloseTask implements ReactorTask {
    public void run() throws IOException {
      channel.close();
    }
  }

  final Reactor reactor;

  final SocketChannel channel;

  ReactorDownstream(Reactor r, SocketChannel ch) {
    reactor = r;
    channel = ch;
  }

  public void sendMessage(Buffer msg) throws IOException {
    if (reactor.onReactorThread()) {
      doSendMessage(msg);
    }
    else {
      reactor.pushWriteTask(new WriteTask(msg));
    }
  }

  public void sendClose() throws IOException {
    if (reactor.onReactorThread()) {
      channel.close();
    }
    else {
      reactor.pushCloseTask(new CloseTask());
    }
  }

  void doSendMessage(Buffer msg) throws IOException {
    ByteBuffer buf = msg.toByteBuffer();

    System.out.println("Sending message");

    channel.write(buf);

    if (buf.remaining() > 0) {
      System.out.println("Failed to write...");
    }
  }
}
