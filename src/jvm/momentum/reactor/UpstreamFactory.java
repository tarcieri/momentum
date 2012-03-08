package momentum.reactor;

import java.net.SocketAddress;

public interface UpstreamFactory {

  Upstream getUpstream(ChannelHandler downstream);

  SocketAddress getAddr();

}
