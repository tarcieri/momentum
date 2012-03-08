package momentum.reactor;

import java.net.SocketAddress;

public interface ReactorUpstreamFactory {

  ReactorUpstream getUpstream(ChannelHandler downstream);

  SocketAddress getAddr();

}
