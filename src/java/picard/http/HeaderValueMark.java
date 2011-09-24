package picard.http;

import java.nio.ByteBuffer;

public class HeaderValueMark extends Mark {
    public HeaderValueMark(ByteBuffer buf, int from) {
        super(buf, from);
    }

    public HeaderValueMark(ByteBuffer buf, int from, Mark previous) {
        super(buf, from, previous);
    }

    public Mark bridge(ByteBuffer nextBuf) {
        System.out.println("[HeaderValueMark#bridge] -> " + to + " " + buf.limit());
        if (to == buf.limit() || lastByteIsText()) {
            System.out.println("[HeaderValueMark#bridge] to == buf.limit()");
            mark(buf.limit());
            finalize();
            return new HeaderValueMark(nextBuf, 0, this);
        }
        else if (to == 0) {
            System.out.println("[HeaderValueMark#bridge] to == 0");
            Mark current = new TenativeMark(buf, 0, previous, previous);

            current.mark(buf.limit());
            current.finalize();

            return new TenativeMark(nextBuf, 0, current, previous);
        }
        else {
            System.out.println("[HeaderValueMark#bridge] to: " + to + ", from: " + from +
                               ", limit: " + buf.limit());
            // TODO: Is this to + 1?
            Mark rest = new TenativeMark(buf, to, this, this);

            finalize();
            rest.mark(buf.limit());
            rest.finalize();

            return new TenativeMark(nextBuf, 0, rest, this);
        }
    }

    protected boolean lastByteIsText() {
        return ! HttpParser.isWhiteSpace(buf.get(buf.limit() - 1));
    }
}
