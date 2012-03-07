package momentum.reactor;

import java.net.SocketAddress;

public interface ReactorUpstreamFactory {

  ReactorUpstream getUpstream(ReactorChannelHandler downstream);

  SocketAddress getAddr();

}
