package picard.net;

import java.net.InetSocketAddress;
import java.util.HashMap;

public class ConnectionQueue {

    Connection head;
    Connection tail;
    HashMap<InetSocketAddress,Connection> localHeadByAddr;

    public ConnectionQueue() {
        this.localHeadByAddr = new HashMap<InetSocketAddress,Connection>();
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

    public void checkin(Connection conn) {
        InetSocketAddress addr = conn.addr();

        synchronized (this) {
            if (head != null) {
                head.prevGlobal = conn;
            }

            conn.nextGlobal = head;
            head = conn;

            if (tail == null) {
                tail = conn;
            }

            Connection localHead = localHeadByAddr.get(addr);

            if (localHead != null) {
                localHead.prevLocal = conn;
                conn.nextLocal = localHead;
            }

            localHeadByAddr.put(addr, conn);
        }
    }

    public void remove(Connection node) {
        synchronized (this) {
            removeNode(node, node.addr());
        }
    }

    private Connection popChannelByAddr(InetSocketAddress addr) {
        Connection retval = localHeadByAddr.get(addr);

        if (retval == null) {
            return null;
        }

        removeNode(retval, addr);

        return retval;
    }


    private void removeNode(Connection node, InetSocketAddress addr) {
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
