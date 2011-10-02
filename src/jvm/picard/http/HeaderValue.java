 package picard.http;

import java.nio.ByteBuffer;

public class HeaderValue extends ChunkedValue {

    public HeaderValue(ByteBuffer buf, int offset) {
        super(buf, offset);
    }

    public void startLine(ByteBuffer buf, int offset) {
        concat(HttpParser.SPACE);
        start(buf, offset);
    }
}
