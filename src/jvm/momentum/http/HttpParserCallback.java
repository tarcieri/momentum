package momentum.http;

import momentum.buffer.Buffer;

public interface HttpParserCallback {
    // Return an object that will contain the HTTP message's headers
    Object blankHeaders();

    // An HTTP header was parsed, returns the new headers object
    Object header(Object headers, String name, String value);

    // The HTTP request head is parsed
    void request(HttpParser parser, Object headers, Buffer body);

    // The HTTP response head is parsed
    void response(HttpParser parser, int status, Object headers, Buffer body);

    // Called with body chunks
    void body(HttpParser parser, Buffer buf);

    // Called with raw messages. This happens when the connection is
    // upgraded
    void message(HttpParser parser, Buffer buf);
}
