package momentum.net;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import java.net.SocketAddress;

public class ClientBootstrap extends org.jboss.netty.bootstrap.ClientBootstrap {

    public ClientBootstrap() {
        super();
    }

    public ClientBootstrap(ChannelFactory factory) {
        super(factory);
    }

    public ChannelFuture connect(final SocketAddress remoteAddr,
                                 ChannelPipeline pipeline) {
        SocketAddress localAddr = (SocketAddress) getOption("localAddress");
        return connect(remoteAddr, localAddr, pipeline);
    }

    public ChannelFuture connect(final SocketAddress remoteAddr,
                                 final SocketAddress localAddr,
                                 ChannelPipeline pipeline) {
        if (remoteAddr == null) {
            throw new NullPointerException("remoteAddr");
        }

        if (pipeline == null) {
            throw new NullPointerException("pipeline");
        }

        // Set the options
        Channel ch = getFactory().newChannel(pipeline);
        ch.getConfig().setOptions(getOptions());

        // Bind
        if (localAddr != null) {
            ch.bind(localAddr);
        }

        // Connect
        return ch.connect(remoteAddr);
    }
}
