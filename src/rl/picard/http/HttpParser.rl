package picard.http;

import clojure.lang.AFn;
import java.nio.ByteBuffer;

public class HttpParser extends AFn {
    public enum MessageType {
        REQUEST,
        RESPONSE
    }

    public enum HttpMethod {
        HEAD,
        GET,
        POST,
        PUT,
        DELETE,
        CONNECT,
        OPTIONS,
        TRACE,

        // webdav
        COPY,
        LOCK,
        MKCOL,
        MOVE,
        PROPFIND,
        PROPPATCH,
        UNLOCK,

        // subversion
        REPORT,
        MKACTIVITY,
        CHECKOUT,
        MERGE,

        // upnp
        MSEARCH,
        NOTIFY,
        SUBSCRIBE,
        UNSUBSCRIBE,

        // RFC-5789
        PATCH
    }

    private class Mark {
        public final ByteBuffer buf;
        public final int offset;

        public Mark(ByteBuffer buf, int offset) {
            this.buf    = buf;
            this.offset = offset;
        }
    }

    private class Node {
        public final Node next;
        public final ByteBuffer buf;

        public Node(Node next, ByteBuffer buf) {
            this.next = next;
            this.buf  = buf;
        }
    }

    %%{
        machine http;

        action method_head        { method = HttpMethod.HEAD;        }
        action method_get         { method = HttpMethod.GET;         }
        action method_post        { method = HttpMethod.POST;        }
        action method_put         { method = HttpMethod.PUT;         }
        action method_delete      { method = HttpMethod.DELETE;      }
        action method_connect     { method = HttpMethod.CONNECT;     }
        action method_options     { method = HttpMethod.OPTIONS;     }
        action method_trace       { method = HttpMethod.TRACE;       }
        action method_copy        { method = HttpMethod.COPY;        }
        action method_lock        { method = HttpMethod.LOCK;        }
        action method_mkcol       { method = HttpMethod.MKCOL;       }
        action method_move        { method = HttpMethod.MOVE;        }
        action method_propfind    { method = HttpMethod.PROPFIND;    }
        action method_proppatch   { method = HttpMethod.PROPPATCH;   }
        action method_unlock      { method = HttpMethod.UNLOCK;      }
        action method_report      { method = HttpMethod.REPORT;      }
        action method_mkactivity  { method = HttpMethod.MKACTIVITY;  }
        action method_checkout    { method = HttpMethod.CHECKOUT;    }
        action method_merge       { method = HttpMethod.MERGE;       }
        action method_msearch     { method = HttpMethod.MSEARCH;     }
        action method_notify      { method = HttpMethod.NOTIFY;      }
        action method_subscribe   { method = HttpMethod.SUBSCRIBE;   }
        action method_unsubscribe { method = HttpMethod.UNSUBSCRIBE; }
        action method_patch       { method = HttpMethod.PATCH;       }

        action http_major {
            httpMajor *= 10;
            httpMajor += fc - '0';

            if (httpMajor > 999) {
                flags |= ERROR;
                throw new HttpParserException("The HTTP major version is invalid.");
            }
        }

        action http_minor {
            httpMinor *= 10;
            httpMinor += fc - '0';

            if (httpMinor > 999) {
                flags |= ERROR;
                throw new HttpParserException("The HTTP minor version is invalid.");
            }
        }

        action start_path {
            pathInfoMark = new Mark(buf, fpc);
        }

        action end_path {
            pathInfo = extract(pathInfoMark, buf, fpc);
        }

        action start_query {
            queryStringMark = new Mark(buf, fpc);
        }

        action end_query {
            queryString = extract(queryStringMark, buf, fpc);
        }

        action start_header_name {
            headerNameMark = new Mark(buf, fpc);
        }

        action end_header_name {
            headerName = extract(headerNameMark, buf, fpc);
        }

        action start_header_value {
            headerValueMark = new Mark(buf, fpc);
        }

        action end_header_value {
            String headerValue = extract(headerValueMark, buf, fpc);

            callback.header(headers, headerName, headerValue);
        }

        action start_head {
            flags  |= PARSING_HEAD;
            headers = callback.blankHeaders();
        }

        action end_head {
            // Not parsing the HTTP message head anymore
            flags ^= PARSING_HEAD;

            callback.request(this, headers);

            // Unset references to allow the GC to reclaim the memory
            resetHeadState();
        }


        include "http.rl";
    }%%

    public static final int MAX_HEADER_SIZE = 100 * 1024;
    public static final int PARSING_HEAD    = 1 << 0;
    public static final int IDENTITY_BODY   = 1 << 1;
    public static final int CHUNKED_BODY    = 1 << 2;
    public static final int KEEP_ALIVE      = 1 << 3;
    public static final int UPGRADE         = 1 << 4;
    public static final int ERROR           = 1 << 5;


    %% write data;

    // Variable used by ragel to represent the current state of the
    // parser. This must be an integer and it should persist across
    // invocations of the machine when the data is broken into blocks
    // that are processed independently. This variable may be modified
    // from outside the execution loop, but not from within.
    private int cs;

    // Stores some miscellaneous parser state such as whether or not
    // the body is chunked or not, whether or not the connection is
    // keep alive or upgraded, etc...
    private int flags;

    // When starting to parse an HTTP message head, an object is
    // requested from the callback. This object should be the
    // structure that contains HTTP headers for the message being
    // processed.
    private Object headers;

    // The parser saves off all ByteBuffers that traverse an HTTP
    // message's head. The buffers are stored in a stack. Whenever the
    // parser crosses a point of interest (the start of the request
    // URI, header name, header value, etc...), a mark will be created
    // and saved off that points to the buffer in question and an
    // offset in that buffer. When the end of the URI, header,
    // etc... is reached, the stack is walked back until the original
    // mark and the data is copied into a String and saved off.
    private Node head;

    // The HTTP protocol version used by the current message being
    // parsed. The major and minor numbers are broken up since they
    // will be moved into a clojure vector.
    private short httpMajor;
    private short httpMinor;

    // Tracks whether the current parser instance is parsing an HTTP
    // request or an HTTP response. Even though the parser can be
    // reused to parse multiple messages, each message must be of the
    // same type. In other words, if the first message a parser
    // instance parses is an HTTP request, then all subsequent
    // messages parsed by the same instance must also be HTTP
    // requests.
    private MessageType type;

    // Tracks the HTTP method of the currently parsed request. If the
    // HTTP message being currently parsed is a response, then this
    // will be nil.
    private HttpMethod method;

    // Tracks the various message information
    private String pathInfo;
    private Mark   pathInfoMark;
    private String queryString;
    private Mark   queryStringMark;
    private String headerName;
    private Mark   headerNameMark;
    private Mark   headerValueMark;

    // The object that gets called on various parse events.
    private HttpParserCallback callback;

    public HttpParser(HttpParserCallback callback) {
        %% write init;
        this.callback = callback;
    }

    public boolean isParsingHead() {
        return ( flags & PARSING_HEAD ) == PARSING_HEAD;
    }

    public boolean isIdentityBody() {
        return ( flags & IDENTITY_BODY ) == IDENTITY_BODY;
    }

    public boolean isChunkedBody() {
        return ( flags & CHUNKED_BODY ) == CHUNKED_BODY;
    }

    public boolean isKeepAlive() {
        return ( flags & KEEP_ALIVE ) == KEEP_ALIVE;
    }

    public boolean isUpgrade() {
        return ( flags & UPGRADE ) == UPGRADE;
    }

    public boolean isError() {
        return ( flags & ERROR ) == ERROR;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public short getHttpMajor() {
        return httpMajor;
    }

    public short getHttpMinor() {
        return httpMinor;
    }

    public String getPathInfo() {
        if (pathInfo == null) {
            return "";
        }

        return pathInfo;
    }

    public String getQueryString() {
        if (queryString == null) {
            return "";
        }

        return queryString;
    }

    public int execute(String str) {
        ByteBuffer buf = ByteBuffer.wrap(str.getBytes());
        return execute(buf);
    }

    public int execute(ByteBuffer buf) {
        // First make sure that the parser isn't in an error state
        if (isError()) {
            throw new HttpParserException("The parser is in an error state.");
        }

        // Setup ragel variables
        int p  = 0;
        int pe = buf.remaining();

        %% getkey buf.get(p);
        %% write exec;

        if (isParsingHead()) {
            pushBuffer(buf);
        }

        return p;
    }

    private void reset() {
        flags = 0;
        resetHeadState();
    }

    private void resetHeadState() {
        head            = null;
        headers         = null;
        method          = null;
        pathInfo        = null;
        pathInfoMark    = null;
        queryString     = null;
        queryStringMark = null;
        headerName      = null;
        headerNameMark  = null;
        headerValueMark = null;
    }

    private void pushBuffer(ByteBuffer buf) {
        head = new Node(head, buf);
    }

    // Takes a starting mark (buffer, offset) and an end buffer +
    // offset and copies the bytes to a string and returns it. This is
    // a fairly simple operation when the start and end points are in
    // the same buffer, but when they are not, the buffer stack is
    // traversed, copying all data in between, until the start buffer
    // is found.
    private String extract(Mark from, ByteBuffer toBuf, int toOffset) {
        int size    = extractSize(from, toBuf, toOffset);
        int pos     = 0;
        Node next   = head;
        byte [] buf = new byte[size];

        if (from.buf == toBuf) {
            copy(buf, pos, from.buf, from.offset, toOffset);
        }
        else {
            pos += copy(buf, pos, toBuf, 0, toOffset);

            while (next.buf != from.buf) {
                pos += copy(buf, pos, next.buf, 0, next.buf.limit());
                next = next.next;
            }

            copy(buf, pos, from.buf, from.offset, from.buf.limit());
        }

        return new String(buf);
    }

    private int extractSize(Mark from, ByteBuffer toBuf, int toOffset) {
        int retval = toOffset - from.offset;
        Node next  = head;

        while (from.buf != toBuf) {
            toBuf   = next.buf;
            next    = next.next;
            retval += toBuf.limit();
        }

        return retval;
    }

    private int copy(byte [] dst, int pos, ByteBuffer src, int from, int to) {
        int oldPos = src.position();
        int oldLim = src.limit();
        int length = to - from;

        src.position(from);
        src.limit(to);
        src.get(dst, dst.length - (pos + length), length);

        src.position(oldPos);
        src.limit(oldLim);

        return length;
    }
}
