package picard.http;

import java.nio.ByteBuffer;

public class TenativeMark extends HeaderValueMark {
    private final Mark backtrack;
    private boolean marked;

    public TenativeMark(ByteBuffer buf, int from, Mark previous, Mark backtrack) {
        super(buf, from, previous);
        this.backtrack = backtrack;
    }

    public void mark(int offset) {
        super.mark(offset);
        marked = true;
    }

    public Mark trim() {
        if (marked) {
            return super.trim();
        }
        else {
            System.out.println("[TenativeMark#trim] Going back to: " + backtrack.to);
            return backtrack;
        }
    }

    public Mark bridge(ByteBuffer nextBuf) {
        System.out.println("[TenativeMark#bridge] ->");
        if (lastByteIsText()) {
            mark(buf.limit());
        }

        if (marked) {
            return super.bridge(nextBuf);
        }
        else {
            finalize(buf.limit());
            return new TenativeMark(nextBuf, 0, this, backtrack);
        }
    }

    public String materialize() {
        if (marked) {
            return super.materialize();
        }
        else {
            return backtrack.materialize();
        }
    }
}
