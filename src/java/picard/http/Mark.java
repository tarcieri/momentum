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
    public static final String EMPTY_STRING = new String("");

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
        this.to       = from;
        this.previous = previous;
    }

    public Mark previous() {
        return previous;
    }

    public void mark(int offset) {
        this.to = offset;
    }

    public void finalize() {
        total = to - from;

        if (previous != null) {
            total += previous.total();
        }
    }

    public void finalize(int offset) {
        mark(offset);
        finalize();
    }

    public Mark bridge(ByteBuffer nextBuf) {
        finalize(buf.limit());
        return new Mark(nextBuf, 0, this);
    }

    public Mark remainingWhiteSpace() {
        if (to == buf.limit()) {
            return null;
        }
        else if (lastByteIsText()) {
            finalize(buf.limit());
            return null;
        }
        else {
            Mark rest = new Mark(buf, to, this);

            finalize();
            rest.finalize(buf.limit());

            return rest;
        }
    }

    public String materialize() {
        if (total() == 0) {
            return EMPTY_STRING;
        }

        byte [] buf = new byte[total()];
        Mark    cur = this;
        int     pos = 0;

        while (cur != null) {
            pos += cur.copy(buf, pos);
            cur  = cur.previous();
        }

        return new String(buf);
    }

    protected int total() {
        return total;
    }

    protected int copy(byte [] dst, int pos) {
        int oldPos = buf.position();
        int oldLim = buf.limit();
        int length = to - from;

        buf.position(from);
        buf.limit(to);
        buf.get(dst, dst.length - (pos + length), length);

        buf.position(oldPos);
        buf.limit(oldLim);

        return length;
    }

    private boolean lastByteIsText() {
        return ! HttpParser.isWhiteSpace(buf.get(buf.limit() - 1));
    }
}
