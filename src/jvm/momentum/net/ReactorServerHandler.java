package momentum.net;

import java.io.IOException;
import java.nio.channels.*;
import momentum.async.AsyncVal;

public class ReactorServerHandler {

  final class CloseTask implements ReactorTask {
    public void run() throws IOException {
      doClose();
    }
  }

  final Reactor reactor;

  final TCPServer server;

  final AsyncVal bound = new AsyncVal();

  final AsyncVal closed = new AsyncVal();

  ServerSocketChannel channel;

  SelectionKey key;

  ReactorServerHandler(Reactor r, TCPServer s) {
    reactor = r;
    server  = s;
  }

  void open(Selector selector) throws IOException {
    if (channel != null)
      return;

    channel = ServerSocketChannel.open();
    channel.configureBlocking(false);

    channel.socket().bind(server.getBindAddr());

    key = channel.register(selector, SelectionKey.OP_ACCEPT, this);

    bound.put(this);
  }

  ReactorChannelHandler accept() throws IOException {
    SocketChannel ch;
    ReactorChannelHandler handler;

    ch = channel.accept();
    ch.configureBlocking(false);

    handler = new ReactorChannelHandler(ch);
    handler.upstream = server.getUpstream(handler);

    return handler;
  }

  public void close() throws IOException {
    if (reactor.onReactorThread()) {
      doClose();
    }
    else {
      reactor.pushCloseTask(new CloseTask());
    }
  }

  void doClose() throws IOException {
    channel.close();
    closed.put(this);
  }
}
