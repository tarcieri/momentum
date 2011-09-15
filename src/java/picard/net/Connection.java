package picard.net;

import java.net.InetSocketAddress;

public interface Connection {
    boolean isOpen();
    InetSocketAddress addr();
}
