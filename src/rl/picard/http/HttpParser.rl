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

    // Listing out all of the headers that we are going to use
    public static final String HDR_ACCEPT                    = "accept";
    public static final String HDR_ACCEPT_CHARSET            = "accept-charset";
    public static final String HDR_ACCEPT_ENCODING           = "accept-encoding";
    public static final String HDR_ACCEPT_LANGUAGE           = "accept-language";
    public static final String HDR_ACCEPT_RANGES             = "accept-ranges";
    public static final String HDR_AGE                       = "age";
    public static final String HDR_ALLOW                     = "allow";
    public static final String HDR_AUTHORIZATION             = "authorization";
    public static final String HDR_CACHE_CONTROL             = "cache-control";
    public static final String HDR_CONNECTION                = "connection";
    public static final String HDR_CONTENT_ENCODING          = "content-encoding";
    public static final String HDR_CONTENT_LANGUAGE          = "content-language";
    public static final String HDR_CONTENT_LENGTH            = "content-length";
    public static final String HDR_CONTENT_LOCATION          = "content-location";
    public static final String HDR_CONTENT_MD5               = "content-md5";
    public static final String HDR_CONTENT_DISPOSITION       = "content-disposition";
    public static final String HDR_CONTENT_RANGE             = "content-range";
    public static final String HDR_CONTENT_TYPE              = "content-type";
    public static final String HDR_COOKIE                    = "cookie";
    public static final String HDR_DATE                      = "date";
    public static final String HDR_DNT                       = "dnt";
    public static final String HDR_ETAG                      = "etag";
    public static final String HDR_EXPECT                    = "expect";
    public static final String HDR_EXPIRES                   = "expires";
    public static final String HDR_FROM                      = "from";
    public static final String HDR_HOST                      = "host";
    public static final String HDR_IF_MATCH                  = "if-match";
    public static final String HDR_IF_MODIFIED_SINCE         = "if-modified-since";
    public static final String HDR_IF_NONE_MATCH             = "if-none-match";
    public static final String HDR_IF_RANGE                  = "if-range";
    public static final String HDR_IF_UNMODIFIED_SINCE       = "if-unmodified-since";
    public static final String HDR_KEEP_ALIVE                = "keep-alive";
    public static final String HDR_LAST_MODIFIED             = "last-modified";
    public static final String HDR_LINK                      = "link";
    public static final String HDR_LOCATION                  = "location";
    public static final String HDR_MAX_FORWARDS              = "max-forwards";
    public static final String HDR_P3P                       = "p3p";
    public static final String HDR_PRAGMA                    = "pragma";
    public static final String HDR_PROXY_AUTHENTICATE        = "proxy-authenticate";
    public static final String HDR_PROXY_AUTHORIZATION       = "proxy-authorization";
    public static final String HDR_RANGE                     = "range";
    public static final String HDR_REFERER                   = "referer";
    public static final String HDR_REFRESH                   = "refresh";
    public static final String HDR_RETRY_AFTER               = "retry-after";
    public static final String HDR_SERVER                    = "server";
    public static final String HDR_SET_COOKIE                = "set-cookie";
    public static final String HDR_STRICT_TRANSPORT_SECURITY = "strict-transport-security";
    public static final String HDR_TE                        = "te";
    public static final String HDR_TRAILER                   = "trailer";
    public static final String HDR_TRANSFER_ENCODING         = "transfer-encoding";
    public static final String HDR_UPGRADE                   = "upgrade";
    public static final String HDR_USER_AGENT                = "user-agent";
    public static final String HDR_VARY                      = "vary";
    public static final String HDR_VIA                       = "via";
    public static final String HDR_WARNING                   = "warning";
    public static final String HDR_WWW_AUTHENTICATE          = "www-authenticate";
    public static final String HDR_X_CONTENT_TYPE_OPTIONS    = "x-content-type-options";
    public static final String HDR_X_DO_NOT_TRACK            = "x-do-not-track";
    public static final String HDR_X_FORWARDED_FOR           = "x-forwarded-for";
    public static final String HDR_X_FORWARDED_PROTO         = "x-forwarded-proto";
    public static final String HDR_X_FRAME_OPTIONS           = "x-frame-options";
    public static final String HDR_X_POWERED_BY              = "x-powered-by";
    public static final String HDR_X_REQUESTED_WITH          = "x-requested-with";
    public static final String HDR_X_XSS_PROTECTION          = "x-xss-protection";

    public static final String VAL_CHUNKED = "chunked";

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

        action hn_accept                    { setHeaderName(HDR_ACCEPT);                    }
        action hn_accept_charset            { setHeaderName(HDR_ACCEPT_CHARSET);            }
        action hn_accept_encoding           { setHeaderName(HDR_ACCEPT_ENCODING);           }
        action hn_accept_language           { setHeaderName(HDR_ACCEPT_LANGUAGE);           }
        action hn_accept_ranges             { setHeaderName(HDR_ACCEPT_RANGES);             }
        action hn_age                       { setHeaderName(HDR_AGE);                       }
        action hn_allow                     { setHeaderName(HDR_ALLOW);                     }
        action hn_authorization             { setHeaderName(HDR_AUTHORIZATION);             }
        action hn_cache_control             { setHeaderName(HDR_CACHE_CONTROL);             }
        action hn_connection                { setHeaderName(HDR_CONNECTION);                }
        action hn_content_encoding          { setHeaderName(HDR_CONTENT_ENCODING);          }
        action hn_content_language          { setHeaderName(HDR_CONTENT_LANGUAGE);          }
        action hn_content_length            { setHeaderName(HDR_CONTENT_LENGTH);            }
        action hn_content_location          { setHeaderName(HDR_CONTENT_LOCATION);          }
        action hn_content_md5               { setHeaderName(HDR_CONTENT_MD5);               }
        action hn_content_disposition       { setHeaderName(HDR_CONTENT_DISPOSITION);       }
        action hn_content_range             { setHeaderName(HDR_CONTENT_RANGE);             }
        action hn_content_type              { setHeaderName(HDR_CONTENT_TYPE);              }
        action hn_cookie                    { setHeaderName(HDR_COOKIE);                    }
        action hn_date                      { setHeaderName(HDR_DATE);                      }
        action hn_dnt                       { setHeaderName(HDR_DNT);                       }
        action hn_etag                      { setHeaderName(HDR_ETAG);                      }
        action hn_expect                    { setHeaderName(HDR_EXPECT);                    }
        action hn_expires                   { setHeaderName(HDR_EXPIRES);                   }
        action hn_from                      { setHeaderName(HDR_FROM);                      }
        action hn_host                      { setHeaderName(HDR_HOST);                      }
        action hn_if_match                  { setHeaderName(HDR_IF_MATCH);                  }
        action hn_if_modified_since         { setHeaderName(HDR_IF_MODIFIED_SINCE);         }
        action hn_if_none_match             { setHeaderName(HDR_IF_NONE_MATCH);             }
        action hn_if_range                  { setHeaderName(HDR_IF_RANGE);                  }
        action hn_if_unmodified_since       { setHeaderName(HDR_IF_UNMODIFIED_SINCE);       }
        action hn_keep_alive                { setHeaderName(HDR_KEEP_ALIVE);                }
        action hn_last_modified             { setHeaderName(HDR_LAST_MODIFIED);             }
        action hn_link                      { setHeaderName(HDR_LINK);                      }
        action hn_location                  { setHeaderName(HDR_LOCATION);                  }
        action hn_max_forwards              { setHeaderName(HDR_MAX_FORWARDS);              }
        action hn_p3p                       { setHeaderName(HDR_P3P);                       }
        action hn_pragma                    { setHeaderName(HDR_PRAGMA);                    }
        action hn_proxy_authenticate        { setHeaderName(HDR_PROXY_AUTHENTICATE);        }
        action hn_proxy_authorization       { setHeaderName(HDR_PROXY_AUTHORIZATION);       }
        action hn_range                     { setHeaderName(HDR_RANGE);                     }
        action hn_referer                   { setHeaderName(HDR_REFERER);                   }
        action hn_refresh                   { setHeaderName(HDR_REFRESH);                   }
        action hn_retry_after               { setHeaderName(HDR_RETRY_AFTER);               }
        action hn_server                    { setHeaderName(HDR_SERVER);                    }
        action hn_set_cookie                { setHeaderName(HDR_SET_COOKIE);                }
        action hn_strict_transport_security { setHeaderName(HDR_STRICT_TRANSPORT_SECURITY); }
        action hn_te                        { setHeaderName(HDR_TE);                        }
        action hn_trailer                   { setHeaderName(HDR_TRAILER);                   }
        action hn_transfer_encoding         { setHeaderName(HDR_TRANSFER_ENCODING);         }
        action hn_upgrade                   { setHeaderName(HDR_UPGRADE);                   }
        action hn_user_agent                { setHeaderName(HDR_USER_AGENT);                }
        action hn_vary                      { setHeaderName(HDR_VARY);                      }
        action hn_via                       { setHeaderName(HDR_VIA);                       }
        action hn_warning                   { setHeaderName(HDR_WARNING);                   }
        action hn_www_authenticate          { setHeaderName(HDR_WWW_AUTHENTICATE);          }
        action hn_x_content_type_options    { setHeaderName(HDR_X_CONTENT_TYPE_OPTIONS);    }
        action hn_x_do_not_track            { setHeaderName(HDR_X_DO_NOT_TRACK);            }
        action hn_x_forwarded_for           { setHeaderName(HDR_X_FORWARDED_FOR);           }
        action hn_x_forwarded_proto         { setHeaderName(HDR_X_FORWARDED_PROTO);         }
        action hn_x_frame_options           { setHeaderName(HDR_X_FRAME_OPTIONS);           }
        action hn_x_powered_by              { setHeaderName(HDR_X_POWERED_BY);              }
        action hn_x_requested_with          { setHeaderName(HDR_X_REQUESTED_WITH);          }
        action hn_x_xss_protection          { setHeaderName(HDR_X_XSS_PROTECTION);          }

        action http_major {
            httpMajor *= 10;
            httpMajor += fc - '0';

            if (httpMajor > 999) {
                throw new HttpParserException("The HTTP major version is invalid.");
            }
        }

        action http_minor {
            httpMinor *= 10;
            httpMinor += fc - '0';

            if (httpMinor > 999) {
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
            if (headerNameMark != null) {
                headerNameMark.finalize(fpc);

                headerName     = headerNameMark.materialize().toLowerCase();
                headerNameMark = null;
            }
        }

        action start_header_value_line {
            if (headerValue == null) {
                headerValue = new HeaderValue(buf, fpc);
            }
            else {
                headerValue.startLine(buf, fpc);
            }
        }

        action end_header_value_non_ws {
            if (headerValue != null) {
                headerValue.mark(fpc);
            }
        }

        action end_header_value_line {
            if (headerValue != null) {
                headerValue.endLine();
            }
        }

        action end_header_value {
            if (headerValue != null) {
                callback.header(headers, headerName, headerValue.materialize());
                headerValue = null;
            }
        }

        action count_content_length {
            if (contentLength >= ALMOST_MAX_LONG) {
                throw new HttpParserException("The content-length is WAY too big");
            }

            contentLength *= 10;
            contentLength += fc - '0';
        }

        action content_length_err {
            // Hack to get Java to compile
            if (true) {
                throw new HttpParserException("The content-length is in an invalid format");
            }
        }

        action end_content_length {
            if (isChunkedBody()) {
                throw new HttpParserException("The message head is invalid");
            }

            flags |= IDENTITY_BODY;

            headerValue = null;
            callback.header(headers, HDR_CONTENT_LENGTH, String.valueOf(contentLength));
        }

        action end_transfer_encoding_chunked {
            if (isIdentityBody()) {
                throw new HttpParserException("The message head is invalid");
            }

            flags |= CHUNKED_BODY;

            headerValue = null;
            callback.header(headers, HDR_TRANSFER_ENCODING, VAL_CHUNKED);
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
            if (true) {
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

    private HeaderValue headerValue;

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
        int p   = buf.position();
        int pe  = buf.limit();
        int eof = pe + 1;

        if (isParsingHead()) {
            pathInfoMark    = bridge(buf, pathInfoMark);
            queryStringMark = bridge(buf, queryStringMark);
            headerNameMark  = bridge(buf, headerNameMark);

            if (headerValue != null) {
                headerValue.bridge(buf);
            }
        }

        try {
            %% getkey buf.get(p);
            %% write exec;
        }
        catch (RuntimeException e) {
            flags |= ERROR;
            throw e;
        }

        return p;
    }

    private void setHeaderName(String name) {
        headerName     = name;
        headerNameMark = null;
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
        headerValue     = null;
    }
}
