package picard.http;

import java.nio.ByteBuffer;

public interface HttpParserCallback {
    // Return an object that will contain the HTTP message's headers
    Object blankHeaders();

    // An HTTP header was parsed
    void header(Object headers, String name, String value);

    // The HTTP request head is parsed
    void message(HttpParser parser, Object headers, ByteBuffer body);

    // Called with body chunks
    void body(HttpParser parser, ByteBuffer buf);
}
