package picard.http;

import java.nio.ByteBuffer;

public class ChunkedValue {
    private   int  bridgeCount;
    protected Mark complete;
    protected Mark current;
    protected Mark tenative;

    public ChunkedValue(ByteBuffer buf, int offset) {
        current = new Mark(buf, offset);
        current.mark(buf.limit());
    }

    public void mark(int offset) {
        if (tenative != null) {
            tenative.mark(offset);
            current  = tenative;
            tenative = null;
        }
        else if (current != null) {
            current.mark(offset);
        }
        else {
            throw new HttpParserException("Cannot mark at this time");
        }
    }

    public void concat(ByteBuffer buf) {
        start(buf, 0);
        mark(buf.limit());
        push();
    }

    public void start(ByteBuffer buf, int offset) {
        if (current != null || tenative != null) {
            throw new HttpParserException("Cannot start a new segment at this time");
        }

        current = new Mark(buf, offset, complete);
        current.mark(buf.limit());
    }

    public void push() {
        if (current != null) {
            current.finalize();

            complete = current;
            current  = null;
            tenative = null;
        }
    }

    public void push(int offset) {
        mark(offset);
        push();
    }

    public String materialize() {
        return complete.materialize();
    }

    public void bridge(ByteBuffer buf) {
        if (++bridgeCount > 10) {
            String msg = "Value broken into too many chunks";
            throw new HttpParserException(msg);
        }

        // If current is null, then there is no line in progress so we
        // can just discard the new buffer.
        if (current == null) {
            return;
        }

        // If there is a tenative mark, then we can bridge that one
        if (tenative != null) {
            tenative = tenative.bridge(buf);
            return;
        }

        // If there is a current mark and no tenative mark, then
        // things get a bit trickier.
        Mark remaining = current.remaining();

        if (remaining == null) {
            tenative = new Mark(buf, 0, current);
        }
        else {
            tenative = new Mark(buf, 0, remaining);
        }
    }
}
