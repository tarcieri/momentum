package momentum.net;

import java.net.SocketAddress;

public interface TCPServer extends ReactorUpstreamFactory {

  SocketAddress getBindAddr();

}
