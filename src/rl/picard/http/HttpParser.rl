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

    public static final byte SP = (byte) 0x20; // Space
    public static final byte HT = (byte) 0x09; // Horizontal tab
    public static final ByteBuffer SPACE = ByteBuffer.wrap(new byte[] { SP });

    public static final String HDR_CONNECTION        = "connection";
    public static final String HDR_CONTENT_LENGTH    = "content-length";
    public static final String HDR_TRANSFER_ENCODING = "transfer-encoding";
    public static final String HDR_CHUNKED           = "chunked";

    public static boolean isWhiteSpace(byte b) {
        return b == SP || b == HT;
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
            pathInfoMark.finalize(fpc);

            pathInfo     = pathInfoMark.materialize();
            pathInfoMark = null;
        }

        action start_query {
            queryStringMark = new Mark(buf, fpc);
        }

        action end_query {
            queryStringMark.finalize(fpc);

            queryString     = queryStringMark.materialize();
            queryStringMark = null;
        }

        action start_header_name {
            headerNameMark = new Mark(buf, fpc);
        }

        action end_header_name {
            headerNameMark.finalize(fpc);

            headerName     = headerNameMark.materialize().toLowerCase();
            headerNameMark = null;
        }

        action start_header_value_line {
            System.out.println("!!!! START HEADER LINE");
            // Handle concatting header lines with a single space
            if (headerValueMark == null) {
                headerValueMark = new HeaderValueMark(buf, fpc, headerValueMark);
            }
            else {
                Mark sp = new HeaderValueMark(SPACE, 0, headerValueMark);
                sp.finalize(1);

                headerValueMark = new HeaderValueMark(buf, fpc, sp);
            }
        }

        action end_header_value_non_ws {
            System.out.println("!!!! ENDING NON WS CHAR");
            headerValueMark.mark(fpc);
        }

        action end_header_value_line {
            System.out.println("[HttpParser#end_header_value_line] headerValueMark: " +
                               headerValueMark);
            headerValueMark.finalize();
            headerValueMark = headerValueMark.trim();
        }

        action end_header_value {
            System.out.println("-------------> CAAAAALLBACK");
            String headerValue = headerValueMark.materialize();
            headerValueMark    = null;

            callback.header(headers, headerName, headerValue);
        }

        # action start_header_value {
        #     headerValueMark = new Mark(buf, fpc);
        # }

        # action end_header_value {
        #     headerValueMark.finalize(fpc);

        #     String headerValue = headerValueMark.materialize();
        #     headerValueMark    = null;

        #     callback.header(headers, headerName, headerValue);
        # }

        action count_content_length {
            if (contentLength >= ALMOST_MAX_LONG) {
                flags |= ERROR;
                throw new HttpParserException("The content-length is WAY too big");
            }

            contentLength *= 10;
            contentLength += fc - '0';
        }

        action content_length_err {
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The content-length is in an invalid format");
            }
        }

        action end_content_length {
            if (isChunkedBody()) {
                flags |= ERROR;
                throw new HttpParserException("The message head is invalid");
            }

            flags |= IDENTITY_BODY;

            callback.header(headers, HDR_CONTENT_LENGTH, String.valueOf(contentLength));
        }

        action transfer_encoding_err {
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The transfer-encoding is in an invalid format");
            }
        }

        action end_transfer_encoding_chunked {
            if (isIdentityBody()) {
                flags |= ERROR;
                throw new HttpParserException("The message head is invalid");
            }

            flags |= CHUNKED_BODY;

            headerValueMark = null;
            callback.header(headers, HDR_TRANSFER_ENCODING, HDR_CHUNKED);
        }

        action end_transfer_encoding {
            if (headerValueMark != null) {
                headerValueMark.finalize(fpc);

                String headerValue = headerValueMark.materialize().toLowerCase();
                headerValueMark    = null;

                callback.header(headers, HDR_TRANSFER_ENCODING, headerValue);
            }
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

        action something_went_wrong {
            flags |= ERROR;

            if (isError()) {
                throw new HttpParserException("Something went wrong");
            }
        }


        include "http.rl";
    }%%

    public static final long ALMOST_MAX_LONG = Long.MAX_VALUE / 10 - 10;
    public static final int  MAX_HEADER_SIZE = 100 * 1024;
    public static final int  PARSING_HEAD    = 1 << 0;
    public static final int  IDENTITY_BODY   = 1 << 1;
    public static final int  CHUNKED_BODY    = 1 << 2;
    public static final int  KEEP_ALIVE      = 1 << 3;
    public static final int  UPGRADE         = 1 << 4;
    public static final int  ERROR           = 1 << 5;


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

    // Track the content length of the HTTP message
    private long contentLength;

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
        int p   = 0;
        int pe  = buf.remaining();
        int eof = pe + 1;

        if (isParsingHead()) {
            pathInfoMark    = bridge(buf, pathInfoMark);
            queryStringMark = bridge(buf, queryStringMark);
            headerNameMark  = bridge(buf, headerNameMark);
            headerValueMark = bridge(buf, headerValueMark);
        }

        %% getkey buf.get(p);
        %% write exec;

        return p;
    }

    private Mark bridge(ByteBuffer buf, Mark mark) {
        if (mark == null) {
            return null;
        }

        return mark.bridge(buf);
    }

    private void reset() {
        flags = 0;
        contentLength = 0;
        resetHeadState();
    }

    private void resetHeadState() {
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
}
