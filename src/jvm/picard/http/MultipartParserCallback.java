package picard.http;

import java.nio.ByteBuffer;

public interface MultipartParserCallback {
    // Return an object that will contain the HTTP message's headers
    Object blankHeaders();

    // An HTTP header was parsed
    void header(Object headers, String name, String value);

    // The HTTP request head is parsed
    void part(Object headers, ByteBuffer body);

    void chunk(ByteBuffer chunk);

    void done();
}
