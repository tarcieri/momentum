package picard.http;

import java.nio.ByteBuffer;

public class HeaderValue {

    private Mark complete;
    private Mark current;
    private Mark tenative;

    public HeaderValue(ByteBuffer buf, int offset) {
        current = new Mark(buf, offset);
    }

    public void startLine(ByteBuffer buf, int offset) {
        Mark sp = new Mark(HttpParser.SPACE, 0, complete);
        sp.finalize(1); // We want the whole space :)

        current = new Mark(buf, offset, sp);
    }

    public void endLine() {
        current.finalize();

        complete = current;
        current  = null;
        tenative = null;
    }

    public void mark(int offset) {
        if (tenative != null) {
            tenative.mark(offset);
            current = tenative;
        }
        else {
            current.mark(offset);
        }
    }

    public String materialize() {
        return complete.materialize();
    }

    public void bridge(ByteBuffer buf) {
        // If current is null, then there is no line in progress so we
        // can just discard the new buffer.
        if (current == null) {
            return;
        }

        // If there is a tenative mark, then we can just bridge that one.
        if (tenative != null) {
            tenative = tenative.bridge(buf);
            return;
        }

        // If there is a current mark and no tenative mark, then
        // things get a bit complicated.
        Mark remaining = current.remainingWhiteSpace();

        if (remaining == null) {
            current = new Mark(buf, 0, current);
        }
        else {
            tenative = new Mark(buf, 0, remaining);
        }
    }
}
