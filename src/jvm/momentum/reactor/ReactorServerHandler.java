package momentum.reactor;

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

  final ReactorUpstreamFactory server;

  final AsyncVal bound = new AsyncVal();

  final AsyncVal closed = new AsyncVal();

  ServerSocketChannel channel;

  SelectionKey key;

  ReactorServerHandler(Reactor r, ReactorUpstreamFactory s) {
    reactor = r;
    server  = s;
  }

  public AsyncVal bound() {
    return bound;
  }

  public AsyncVal closed() {
    return closed;
  }

  void open(Selector selector) throws IOException {
    if (channel != null)
      return;

    channel = ServerSocketChannel.open();
    channel.configureBlocking(false);

    channel.socket().bind(server.getAddr());

    key = channel.register(selector, SelectionKey.OP_ACCEPT, this);

    bound.put(this);
  }

  ChannelHandler accept() throws IOException {
    ChannelHandler h = Reactor.bindChannel(channel.accept(), server);

    if (h != null)
      h.setConnected();

    return h;
  }

  public AsyncVal close() throws IOException {
    if (reactor.onReactorThread()) {
      doClose();
    }
    else {
      reactor.pushTask(new CloseTask());
    }

    return closed;
  }

  void doClose() throws IOException {
    channel.close();
    closed.put(this);
  }
}
