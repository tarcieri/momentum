package momentum.net;

import java.net.SocketAddress;

public interface ReactorUpstreamFactory {

  ReactorUpstream getUpstream(ReactorChannelHandler downstream);

  SocketAddress getAddr();

}
