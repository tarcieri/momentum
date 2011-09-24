package picard.http;

import java.nio.ByteBuffer;

// A helper class that can be used to mark points of interest in
// the buffers that are being parsed. Whenever a point of interest
// is reached, it is marked with an instance of this class. When
// the end of the point of interest is reached, that is also
// marked. Using this class allows spanning multiple chunks since
// when the end of a chunk is reached, we can simply finalize the
// mark and then start up a new one when the next chunk is
// received.
public class Mark {
    // The previous mark in the stack
    protected final Mark previous;

    // The byte buffer that this mark points to
    protected final ByteBuffer buf;

    // The offset in the buffer that the mark starts at
    protected final int from;

    // The offset in the buffer that the mark ends at
    protected int to;

    // The total length of all the previous marks and the current
    // combined.
    protected int total;

    public Mark(ByteBuffer buf, int from) {
        this(buf, from, null);
    }

    public Mark(ByteBuffer buf, int from, Mark previous) {
        this.buf      = buf;
        this.from     = from;
        this.previous = previous;
    }

    public int total() {
        return total;
    }

    public void mark(int offset) {
        this.to = offset;
    }

    public void finalize() {
        System.out.println("[Mark#finalize] -> to: " + to + ", from: " + from);
        total = to - from;

        if (previous != null) {
            System.out.println("[Mark#finalize] previous.total(): " + previous.total());
            total += previous.total();
        }
    }

    public Mark trim() {
        return this;
    }

    public void finalize(int offset) {
        mark(offset);
        finalize();
    }

    public Mark bridge(ByteBuffer nextBuf) {
        finalize(buf.limit());
        return new Mark(nextBuf, 0, this);
    }

    public String materialize() {
        byte [] buf = new byte[total()];
        Mark    cur = this;
        int     pos = 0;

        while (cur != null) {
            pos += cur.copy(buf, pos);
            cur  = cur.previous();
        }

        return new String(buf);
    }

    protected Mark previous() {
        return previous;
    }

    protected int copy(byte [] dst, int pos) {
        int oldPos = buf.position();
        int oldLim = buf.limit();
        int length = to - from;

        buf.position(from);
        buf.limit(to);
        try {
            buf.get(dst, dst.length - (pos + length), length);
        }
        catch (RuntimeException e) {
            System.out.println("CURRENT: ");
            System.out.println(new String(dst));
            System.out.println("-------------");
            System.out.println("from: " + from + ", to: " + to);
            System.out.println("offset: " + (dst.length - (pos + length)));
            System.out.println("length: " + length);
            System.out.println("====");
            System.out.println("BUFFER: '" + new String(buf.array()) + "'");
            System.out.println("====");
            throw e;
        }

        buf.position(oldPos);
        buf.limit(oldLim);

        return length;
    }
}
