package picard;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ChannelUpstreamHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

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

    final static Logger logger = Logger.getLogger("picard.internal.pool");

    final int expiration;
    boolean shuttingDown;
    Node head;
    Node tail;
    HashedWheelTimer timer;
    HashMap<InetSocketAddress,Node> localHeadByAddr;

    public ChannelPool(int expireAfter, HashedWheelTimer timer) {
        shuttingDown = false;

        if (expireAfter < 1) {
            throw new IllegalArgumentException("Need a positive expiration");
        }

        this.expiration = (expireAfter * 1000);
        this.timer = timer;
        this.localHeadByAddr = new HashMap<InetSocketAddress,Node>();
    }

    public Channel checkout(InetSocketAddress addr) {
        Channel channel;

        synchronized (this) {
            if (shuttingDown) {
                return null;
            }

            while (true) {
                channel = popChannelByAddr(addr);

                if (channel == null) {
                    return null;
                }

                if (channel.isOpen()) {
                    channel.getPipeline().remove("poolPurger");
                    return channel;
                }
            }
        }
    }

    public void checkin(final Channel channel) {
        final Node node = new Node(channel);
        InetSocketAddress addr = addrFrom(channel);

        synchronized (this) {
            if (shuttingDown) {
                channel.close();
                return;
            }
        }

        ChannelPool.logger.debug("Setting keepalive timer for " + expiration +
                                 " milliseconds from now");

        // This might technically be a race condition, but I hope that the
        // following synchronized code takes less than a second to run.
        node.timeout = timer.newTimeout(new TimerTask() {
            public void run(Timeout timeout) {
                ChannelPool.logger.debug("Expiring channel while in poo: " +
                                         channel);

                synchronized (ChannelPool.this) {
                    ChannelPool.this.expireNode(node);
                }
            }
        }, expiration, TimeUnit.MILLISECONDS);

        channel.getPipeline().addFirst("poolPurger", new ChannelUpstreamHandler() {
            public void handleUpstream(ChannelHandlerContext ctx,
                                       ChannelEvent e) throws Exception {
                if (e instanceof ChannelStateEvent) {
                    ChannelStateEvent evt = (ChannelStateEvent) e;
                    if (evt.getState() == ChannelState.CONNECTED &&
                        evt.getValue() == null) {
                        ChannelPool.logger.debug("Channel disconnected while in " +
                                                 "pool: " + channel);

                        synchronized (ChannelPool.this) {
                            ChannelPool.this.expireNode(node);
                        }
                        return;
                    }
                }
                ctx.sendUpstream(e);
            }
        });

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

    public void shutdown() {
        // First, mark this pool as shutting down
        synchronized (this) {
            shuttingDown = true;

            Node currentNode = head;

            while (currentNode != null) {
                expireNode(currentNode);
                currentNode = head;
            }
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
        node.timeout.cancel();
        node.channel = null;

        return retval;
    }

    private void expireNode(Node node) {
        removeNode(node);

        if (node.channel == null) {
            return;
        }

        if (node.channel.isOpen()) {
            node.channel.close();
        }
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
