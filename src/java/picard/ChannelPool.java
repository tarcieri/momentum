package picard;

import org.jboss.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.util.HashMap;

public class ChannelPool {

    private class Node {
        public Channel channel;
        public Node nextGlobal;
        public Node prevGlobal;
        public Node nextLocal;
        public Node prevLocal;

        public Node(Channel channel) {
            this.channel = channel;
        }
    }

    int  capacity;
    Node head;
    Node tail;
    HashMap<InetSocketAddress,Node> localHeadByAddr;

    public ChannelPool(int capacity) {
        this.capacity = capacity;
        this.localHeadByAddr = new HashMap<InetSocketAddress,Node>();
    }

    public Channel checkout(InetSocketAddress addr) {
        Channel channel;

        synchronized (this) {
            while (true) {
                channel = popChannelByAddr(addr);

                if (channel == null) {
                    return null;
                }

                if (channel.isOpen()) {
                    return channel;
                }
            }
        }
    }

    public void checkin(Channel channel) {
        Node node = new Node(channel);
        InetSocketAddress addr = (InetSocketAddress) channel.getRemoteAddress();

        synchronized (this) {
            if (head != null) {
                head.prevGlobal = node;
            }

            node.nextGlobal = head;
            head = node;

            if (tail == null) {
                tail = node;
            }

            Node localHead = localHeadByAddr.get(addr);

            if (localHead != null) {
                localHead.prevLocal = node;
                node.nextLocal = localHead;
            }

            localHeadByAddr.put(addr, node);
        }
    }

    private Channel popChannelByAddr(InetSocketAddress addr) {
        Node node = localHeadByAddr.get(addr);

        if (node == null) {
            return null;
        }

        if (head == node) {
            head = node.nextGlobal;
        }

        if (tail == node) {
            tail = node.prevGlobal;
        }

        if (node.nextGlobal != null) {
            node.nextGlobal.prevGlobal = node.prevGlobal;
        }

        if (node.prevGlobal != null) {
            node.prevGlobal.nextGlobal = node.nextGlobal;
        }

        if (node.nextLocal != null) {
            node.nextLocal.prevGlobal = null;
            localHeadByAddr.put(addr, node.nextLocal);
        } else {
            // Remove the key
            localHeadByAddr.remove(addr);
        }

        return node.channel;
    }
}
