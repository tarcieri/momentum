package picard.net;

import java.net.InetSocketAddress;

public class Connection {
    final public InetSocketAddress addr;
    final public Object connectFn;

    public Object  addrs;
    public Object  downstream;
    public Object  exchangeUp;
    public Object  exchangeDn;
    public boolean isOpen;

    // Stuff related to the linked lists
    protected Connection nextGlobal;
    protected Connection prevGlobal;
    protected Connection nextLocal;
    protected Connection prevLocal;

    public Connection(InetSocketAddress addr, Object connectFn) {
        this.isOpen    = true;
        this.addr      = addr;
        this.connectFn = connectFn;
    }

    public InetSocketAddress addr() {
        return addr;
    }

    public synchronized boolean isOpen() {
        return isOpen;
    }
}
