package picard.net;

import java.net.InetSocketAddress;
import java.util.HashMap;

public class ConnectionQueue {

    private class Node {
        public Connection channel;
        public Node nextGlobal;
        public Node prevGlobal;
        public Node nextLocal;
        public Node prevLocal;

        public Node(Connection channel) {
            this.channel = channel;
        }
    }

    Node head;
    Node tail;
    HashMap<InetSocketAddress,Node> localHeadByAddr;

    public ConnectionQueue() {
        this.localHeadByAddr = new HashMap<InetSocketAddress,Node>();
    }

    public Connection checkout(InetSocketAddress addr) {
        Connection retval;

        synchronized (this) {
            while (true) {
                retval = popChannelByAddr(addr);

                if (retval == null) {
                    return null;
                }

                if (retval.isOpen()) {
                    return retval;
                }
            }
        }
    }

    public void checkin(Connection channel) {
        final Node node = new Node(channel);
        InetSocketAddress addr = channel.addr();

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

    private Connection popChannelByAddr(InetSocketAddress addr) {
        Connection retval;
        Node node = localHeadByAddr.get(addr);

        if (node == null) {
            return null;
        }

        removeNode(node, addr);

        retval = node.channel;
        node.channel = null;

        return retval;
    }


    private void removeNode(Node node, InetSocketAddress addr) {
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
            node.nextLocal.prevLocal = node.prevLocal;
        }

        if (node.prevLocal != null) {
            node.prevLocal.nextLocal = node.nextLocal;
        }

        if (localHeadByAddr.get(addr) == node) {
            if (node.nextLocal != null) {
                node.nextLocal.prevLocal = null;
                localHeadByAddr.put(addr, node.nextLocal);
            } else {
                localHeadByAddr.remove(addr);
            }
        }
    }
}
