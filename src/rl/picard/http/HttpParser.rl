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
                // TODO: handle error
            }
        }

        action http_minor {
            httpMinor *= 10;
            httpMinor += fc - '0';

            if (httpMinor > 999) {
                // TODO: handle error
            }
        }

        action head_complete {
            callback.request(this);
        }


        include "http.rl";
    }%%

    %% write data;

    // Variable used by ragel to represent the current state of the
    // parser. This must be an integer and it should persist across
    // invocations of the machine when the data is broken into blocks
    // that are processed independently. This variable may be modified
    // from outside the execution loop, but not from within.
    private int cs;

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

    private HttpMethod method;
    private HttpParserCallback callback;

    public HttpParser(HttpParserCallback callback) {
        %% write init;
        this.callback = callback;
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

    public int execute(String str) {
        ByteBuffer buf = ByteBuffer.wrap(str.getBytes());
        return execute(buf);
    }

    public int execute(ByteBuffer buf) {
        // Setup variables needed by ragel
        int p  = 0;
        int pe = buf.remaining();

        %% getkey buf.get(p);
        %% write exec;

        return p;
    }
}
