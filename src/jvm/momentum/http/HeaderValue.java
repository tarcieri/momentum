package momentum.http;

import momentum.core.Buffer;

public class HeaderValue extends ChunkedValue {

    public HeaderValue(Buffer buf, int offset) {
        super(buf, offset);
    }

    public void startLine(Buffer buf, int offset) {
        concat(HttpParser.SPACE);
        start(buf, offset);
    }
}
