package picard;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public class ChannelPool {

    private class Node {
        public Channel channel;
        public Timeout timeout;
        public Node nextGlobal;
        public Node prevGlobal;
        public Node nextLocal;
        public Node prevLocal;

        public Node(Channel channel) {
            this.channel = channel;
        }
    }

    final int expiration;
    Node head;
    Node tail;
    HashedWheelTimer timer;
    HashMap<InetSocketAddress,Node> localHeadByAddr;

    public ChannelPool(int expireAfter) {
        if (expireAfter < 1) {
            throw new IllegalArgumentException("Need a positive expiration");
        }

        this.expiration = ((expireAfter * 1000) / 512) * 512;
        this.timer = new HashedWheelTimer((expireAfter * 1000) / 512,
                                          TimeUnit.MILLISECONDS);
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
        final Node node = new Node(channel);
        InetSocketAddress addr = addrFrom(channel);

        // This might technically be a race condition, but I hope that the
        // following synchronized code takes less than a second to run.
        node.timeout = timer.newTimeout(new TimerTask() {
            public void run(Timeout timeout) {
                synchronized (ChannelPool.this) {
                    ChannelPool.this.expireNode(node);
                }
            }
        }, expiration, TimeUnit.MILLISECONDS);

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

    public boolean purge() {
        synchronized (this) {
            if (tail == null) {
                return false;
            }

            tail.timeout.cancel();
            tail.channel.close();
            removeNode(tail);

            return true;
        }
    }

    private Channel popChannelByAddr(InetSocketAddress addr) {
        Channel retval;
        Node node = localHeadByAddr.get(addr);

        if (node == null) {
            return null;
        }

        removeNode(node);

        retval = node.channel;
        node.channel = null;
        node.timeout.cancel();

        return retval;
    }

    private void expireNode(Node node) {
        if (node.channel == null) {
            return;
        }

        node.channel.close();
        removeNode(node);
    }

    private void removeNode(Node node) {
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
            localHeadByAddr.put(addrFrom(node), node.nextLocal);
        } else {
            // Remove the key
            localHeadByAddr.remove(addrFrom(node));
        }
    }

    private InetSocketAddress addrFrom(Channel channel) {
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    private InetSocketAddress addrFrom(Node node) {
        return addrFrom(node.channel);
    }
}
