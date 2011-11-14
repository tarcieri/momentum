package momentum.http;

import momentum.buffer.Buffer;

public class HeaderValue extends ChunkedValue {

    public HeaderValue(Buffer buf, int offset) {
        super(buf, offset);
    }

    public void startLine(Buffer buf, int offset) {
        concat(HttpParser.SPACE);
        start(buf, offset);
    }
}
