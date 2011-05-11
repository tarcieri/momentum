package picard;

import java.net.InetSocketAddress;

public interface ChannelPoolCallback {
    public void channelClosed(InetSocketAddress addr);
}
