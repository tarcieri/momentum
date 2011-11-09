package momentum.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Queue;
import clojure.lang.AFn;
import momentum.core.Buffer;

/**
 * TODO:
 *   - Improve the handling of Connection header values
 *   - Handle full URIs in the request line
 *   - Possibly handle quotes in URIs (old Mozilla bug)
 */
public final class HttpParser extends AFn {
  public enum MessageType {
    REQUEST,
    RESPONSE
  }

  public static final byte SP = (byte) 0x20; // Space
  public static final byte HT = (byte) 0x09; // Horizontal tab
  public static final String SLASH = new String("/").intern();
  public static final String EMPTY_STRING = new String("").intern();
  public static final byte[] EMPTY_BUFFER = new byte[0];
  public static final Buffer SPACE = Buffer.wrap(new byte[] { SP });

  public static final int MAX_BUFFERED = 4096;

  // Map of hexadecimal chars to their numeric value
  public static final byte[] HEX_MAP = new byte [] {
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
     0,  1,  2,  3,  4,  5,  6,  7,  8,  9, -1, -1, -1, -1, -1, -1,
    -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1
  };

  /*
   * Listing out all the HTTP methods that we care about
   */

  // basic
  public static final String MTH_HEAD    = "HEAD".intern();
  public static final String MTH_GET     = "GET".intern();
  public static final String MTH_POST    = "POST".intern();
  public static final String MTH_PUT     = "PUT".intern();
  public static final String MTH_DELETE  = "DELETE".intern();
  public static final String MTH_CONNECT = "CONNECT".intern();
  public static final String MTH_OPTIONS = "OPTIONS".intern();
  public static final String MTH_TRACE   = "TRACE".intern();

  // webdav
  public static final String MTH_COPY      = "COPY".intern();
  public static final String MTH_LOCK      = "LOCK".intern();
  public static final String MTH_MKCOL     = "MKCOL".intern();
  public static final String MTH_MOVE      = "MOVE".intern();
  public static final String MTH_PROPFIND  = "PROPFIND".intern();
  public static final String MTH_PROPPATCH = "PROPPATCH".intern();
  public static final String MTH_UNLOCK    = "UNLOCK".intern();

  // subversion
  public static final String MTH_REPORT     = "REPORT".intern();
  public static final String MTH_MKACTIVITY = "MKACTIVITY".intern();
  public static final String MTH_CHECKOUT   = "CHECKOUT".intern();
  public static final String MTH_MERGE      = "MERGE".intern();

  // upnp
  public static final String MTH_MSEARCH     = "MSEARCH".intern();
  public static final String MTH_NOTIFY      = "NOTIFY".intern();
  public static final String MTH_SUBSCRIBE   = "SUBSCRIBE".intern();
  public static final String MTH_UNSUBSCRIBE = "UNSUBSCRIBE".intern();

  // RFC-5789
  public static final String MTH_PATCH = "PATCH".intern();

  // Listing out all of the headers that we are going to use
  public static final String HDR_ACCEPT                    = "accept".intern();
  public static final String HDR_ACCEPT_CHARSET            = "accept-charset".intern();
  public static final String HDR_ACCEPT_ENCODING           = "accept-encoding".intern();
  public static final String HDR_ACCEPT_LANGUAGE           = "accept-language".intern();
  public static final String HDR_ACCEPT_RANGES             = "accept-ranges".intern();
  public static final String HDR_AGE                       = "age".intern();
  public static final String HDR_ALLOW                     = "allow".intern();
  public static final String HDR_AUTHORIZATION             = "authorization".intern();
  public static final String HDR_CACHE_CONTROL             = "cache-control".intern();
  public static final String HDR_CONNECTION                = "connection".intern();
  public static final String HDR_CONTENT_ENCODING          = "content-encoding".intern();
  public static final String HDR_CONTENT_LANGUAGE          = "content-language".intern();
  public static final String HDR_CONTENT_LENGTH            = "content-length".intern();
  public static final String HDR_CONTENT_LOCATION          = "content-location".intern();
  public static final String HDR_CONTENT_MD5               = "content-md5".intern();
  public static final String HDR_CONTENT_DISPOSITION       = "content-disposition".intern();
  public static final String HDR_CONTENT_RANGE             = "content-range".intern();
  public static final String HDR_CONTENT_TYPE              = "content-type".intern();
  public static final String HDR_COOKIE                    = "cookie".intern();
  public static final String HDR_DATE                      = "date".intern();
  public static final String HDR_DNT                       = "dnt".intern();
  public static final String HDR_ETAG                      = "etag".intern();
  public static final String HDR_EXPECT                    = "expect".intern();
  public static final String HDR_EXPIRES                   = "expires".intern();
  public static final String HDR_FROM                      = "from".intern();
  public static final String HDR_HOST                      = "host".intern();
  public static final String HDR_IF_MATCH                  = "if-match".intern();
  public static final String HDR_IF_MODIFIED_SINCE         = "if-modified-since".intern();
  public static final String HDR_IF_NONE_MATCH             = "if-none-match".intern();
  public static final String HDR_IF_RANGE                  = "if-range".intern();
  public static final String HDR_IF_UNMODIFIED_SINCE       = "if-unmodified-since".intern();
  public static final String HDR_KEEP_ALIVE                = "keep-alive".intern();
  public static final String HDR_LAST_MODIFIED             = "last-modified".intern();
  public static final String HDR_LINK                      = "link".intern();
  public static final String HDR_LOCATION                  = "location".intern();
  public static final String HDR_MAX_FORWARDS              = "max-forwards".intern();
  public static final String HDR_P3P                       = "p3p".intern();
  public static final String HDR_PRAGMA                    = "pragma".intern();
  public static final String HDR_PROXY_AUTHENTICATE        = "proxy-authenticate".intern();
  public static final String HDR_PROXY_AUTHORIZATION       = "proxy-authorization".intern();
  public static final String HDR_RANGE                     = "range".intern();
  public static final String HDR_REFERER                   = "referer".intern();
  public static final String HDR_REFRESH                   = "refresh".intern();
  public static final String HDR_RETRY_AFTER               = "retry-after".intern();
  public static final String HDR_SERVER                    = "server".intern();
  public static final String HDR_SET_COOKIE                = "set-cookie".intern();
  public static final String HDR_STRICT_TRANSPORT_SECURITY = "strict-transport-security".intern();
  public static final String HDR_TE                        = "te".intern();
  public static final String HDR_TRAILER                   = "trailer".intern();
  public static final String HDR_TRANSFER_ENCODING         = "transfer-encoding".intern();
  public static final String HDR_UPGRADE                   = "upgrade".intern();
  public static final String HDR_USER_AGENT                = "user-agent".intern();
  public static final String HDR_VARY                      = "vary".intern();
  public static final String HDR_VIA                       = "via".intern();
  public static final String HDR_WARNING                   = "warning".intern();
  public static final String HDR_WWW_AUTHENTICATE          = "www-authenticate".intern();
  public static final String HDR_X_CONTENT_TYPE_OPTIONS    = "x-content-type-options".intern();
  public static final String HDR_X_DO_NOT_TRACK            = "x-do-not-track".intern();
  public static final String HDR_X_FORWARDED_FOR           = "x-forwarded-for".intern();
  public static final String HDR_X_FORWARDED_PROTO         = "x-forwarded-proto".intern();
  public static final String HDR_X_FRAME_OPTIONS           = "x-frame-options".intern();
  public static final String HDR_X_POWERED_BY              = "x-powered-by".intern();
  public static final String HDR_X_REQUESTED_WITH          = "x-requested-with".intern();
  public static final String HDR_X_XSS_PROTECTION          = "x-xss-protection".intern();

  public static final String VAL_100_CONTINUE = "100-continue".intern();
  public static final String VAL_CHUNKED      = "chunked".intern();
  public static final String VAL_CLOSE        = "close".intern();
  public static final String VAL_UPGRADE      = "upgrade".intern();

  public static boolean isWhiteSpace(byte b) {
    return b == SP || b == HT;
  }

  %%{
    machine http;

    action http_request {
      if (type != MessageType.REQUEST) {
        throw new HttpParserException("Expecting HTTP response but got request");
      }
    }

    action http_response {
      if (type != MessageType.RESPONSE) {
        throw new HttpParserException("Expecting HTTP request but got response");
      }

      method = methodQueue.poll();
    }

    action method_head        { method = MTH_HEAD;        }
    action method_get         { method = MTH_GET;         }
    action method_post        { method = MTH_POST;        }
    action method_put         { method = MTH_PUT;         }
    action method_delete      { method = MTH_DELETE;      }
    action method_connect     { method = MTH_CONNECT;     }
    action method_options     { method = MTH_OPTIONS;     }
    action method_trace       { method = MTH_TRACE;       }
    action method_copy        { method = MTH_COPY;        }
    action method_lock        { method = MTH_LOCK;        }
    action method_mkcol       { method = MTH_MKCOL;       }
    action method_move        { method = MTH_MOVE;        }
    action method_propfind    { method = MTH_PROPFIND;    }
    action method_proppatch   { method = MTH_PROPPATCH;   }
    action method_unlock      { method = MTH_UNLOCK;      }
    action method_report      { method = MTH_REPORT;      }
    action method_mkactivity  { method = MTH_MKACTIVITY;  }
    action method_checkout    { method = MTH_CHECKOUT;    }
    action method_merge       { method = MTH_MERGE;       }
    action method_msearch     { method = MTH_MSEARCH;     }
    action method_notify      { method = MTH_NOTIFY;      }
    action method_subscribe   { method = MTH_SUBSCRIBE;   }
    action method_unsubscribe { method = MTH_UNSUBSCRIBE; }
    action method_patch       { method = MTH_PATCH;       }

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

    action http_status_digit {
      status *= 10;
      status += fc - '0';
    }

    action start_version {
      httpMinor = 0;
    }

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

    action start_uri {
      uriMark = new ChunkedValue(buf, fpc);
    }

    action end_uri {
      uriMark.push(fpc);

      String uriStr = uriMark.materializeStr();

      try {
        uri = new URI(uriStr);
      }
      catch (URISyntaxException e) {
        throw new HttpParserException("The URI is not valid: " + uriStr);
      }

      uriMark = null;
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

      if (isRequest() || method != MTH_HEAD) {
        flags |= IDENTITY_BODY;
      }

      headerName  = null;
      headerValue = null;
      callback.header(headers, HDR_CONTENT_LENGTH, String.valueOf(contentLength));
    }

    action end_transfer_encoding_chunked {
      if (isIdentityBody()) {
        throw new HttpParserException("The message head is invalid");
      }

      if (isRequest() || method != MTH_HEAD) {
        flags |= CHUNKED_BODY;
      }

      headerName  = null;
      headerValue = null;
      callback.header(headers, HDR_TRANSFER_ENCODING, VAL_CHUNKED);
    }

    action end_connection_close {
      flags |= CONN_CLOSE;

      headerName  = null;
      headerValue = null;
      callback.header(headers, HDR_CONNECTION, VAL_CLOSE);
    }

    action end_connection_upgrade {
      flags |= UPGRADE;

      headerName  = null;
      headerValue = null;
      callback.header(headers, HDR_CONNECTION, VAL_UPGRADE);
    }

    action end_expect_continue {
      if (isHttp11()) {
        flags |= EXPECT_CONTINUE;
      }

      headerName  = null;
      headerValue = null;
      callback.header(headers, HDR_EXPECT, VAL_100_CONTINUE);
    }

    action start_head {
      reset();

      flags  |= PARSING_HEAD;
      headers = callback.blankHeaders();
    }

    action end_head {
      // Not parsing the HTTP message head anymore
      flags ^= PARSING_HEAD;

      Buffer b = null;

      if (isUpgrade()) {
        fnext upgraded;
      }
      else if (isRequest() || (status >= 200 && status != 204 && status != 304)){
        if (isIdentityBody()) {
          int remaining = buf.limit() - fpc;
          int toRead;

          // If the remaining content length is present in the
          // buffer, just include it in the callback.
          if (remaining > contentLength && !isExpectingContinue()) {
            toRead = (int) contentLength;

            ++fpc;

            b = slice(buf, fpc, fpc + toRead);
            fpc += toRead - 1;
            contentLength = 0;
          }
          // If the entire body is less than a set maximum (default 4kb), just
          // allocate a new buffer and copy the chunks into it.
          else if (contentLength <= MAX_BUFFERED && !isExpectingContinue()) {
            body   = Buffer.allocate((int) contentLength);
            toRead = remaining - 1;

            if (remaining > 1) {
              ++fpc;

              body.put(buf, fpc, toRead);

              contentLength -= toRead;
              fpc += toRead - 1;
            }

            fnext identity_body;
          }
          else {
            fnext identity_body;
          }
        }
        else if (isChunkedBody()) {
          fnext chunked_body;
        }
        else if (isResponse() && isConnectionClose()) {
          fnext untracked_body;
        }
      }

      if (body == null) {
        if (isRequest()) {
          callback.request(this, headers, b);
        }
        else {
          callback.response(this, status, headers, b);
        }

        // Unset references to allow the GC to reclaim the memory
        resetHeadState();
      }
    }

    action handling_body {
      contentLength > 0
    }

    action handle_body {
      int toRead = min(contentLength, buf.limit() - fpc);

      if (toRead > 0) {
        contentLength -= toRead;

        if (body != null) {
          body.put(buf, fpc, toRead);

          fpc += toRead - 1;

          if (contentLength == 0) {
            body.flip();

            if (isRequest()) {
              callback.request(this, headers, body);
            }
            else {
              callback.response(this, status, headers, body);
            }

            body = null;
            resetHeadState();

            fnext main;
          }
        }
        else {
          callback.body(this, slice(buf, fpc, fpc + toRead));

          fpc += toRead - 1;

          if (contentLength == 0) {
            callback.body(this, null);
            fnext main;
          }
        }
      }
    }

    action handle_untracked_body {
      int toRead = buf.limit() - fpc;

      if (toRead > 0) {
        callback.body(this, slice(buf, fpc, fpc + toRead));
        fpc += toRead - 1;
      }
    }

    action handle_chunk {
      int toRead = min(contentLength, buf.limit() - fpc);

      if (toRead > 0) {
        contentLength -= toRead;

        callback.body(this, slice(buf, fpc, fpc + toRead));

        fpc += toRead - 1;
      }
    }

    action handle_message {
      int remaining = buf.limit() - fpc;

      if (remaining > 0) {
        callback.message(this, slice(buf, fpc, buf.limit()));
        break parseLoop;
      }
    }

    action last_chunk {
      callback.body(this, null);
    }

    action start_chunk_size {
      contentLength = 0;
    }

    action count_chunk_size {
      if (contentLength >= ALMOST_MAX_LONG_HEX) {
        throw new HttpParserException("The content-length is WAY too big");
      }

      contentLength *= 16;
      contentLength += HEX_MAP[fc];
    }

    action chunk_size_err {
      if (true) {
        throw new HttpParserException("Invalid chunk size");
      }
    }

    action reset {
      fnext main;
    }

    action count_message_head {
      if (++hread > MAX_HEADER_SIZE) {
        throw new HttpParserException("The HTTP message head is too large");
      }
    }

    action something_went_wrong {
      if (true) {
        String msg = parseErrorMsg(buf, fpc);
        throw new HttpParserException("Something went wrong:\n" + msg);
      }
    }


    include "http.rl";
  }%%

  public static final long ALMOST_MAX_LONG     = Long.MAX_VALUE / 10;
  public static final long ALMOST_MAX_LONG_HEX = Long.MAX_VALUE / 16;

  public static final int  MAX_HEADER_SIZE = 100 * 1024;
  public static final int  PARSING_HEAD    = 1 << 0;
  public static final int  IDENTITY_BODY   = 1 << 1;
  public static final int  CHUNKED_BODY    = 1 << 2;
  public static final int  CONN_CLOSE      = 1 << 3;
  public static final int  KEEP_ALIVE      = 1 << 4;
  public static final int  UPGRADE         = 1 << 5;
  public static final int  EXPECT_CONTINUE = 1 << 6;
  public static final int  ERROR           = 1 << 7;

  %% write data;

  /*
  * Variable used by ragel to represent the current state of the
  * parser. This must be an integer and it should persist across
  * invocations of the machine when the data is broken into blocks
  * that are processed independently. This variable may be modified
  * from outside the execution loop, but not from within.
  */
  private int cs;

  /*
  * Stores some miscellaneous parser state such as whether or not
  * the body is chunked or not, whether or not the connection is
  * keep alive or upgraded, etc...
  */
  private int flags;

  /*
  * The number of bytes read while parsing the HTTP message
  * head. This is to protect against a possible attack where
  * somebody sends unbounded HTTP message heads and causes out of
  * memory errors.
  */
  private int hread;

  /*
  * When starting to parse an HTTP message head, an object is
  * requested from the callback. This object should be the
  * structure that contains HTTP headers for the message being
  * processed.
  */
  private Object headers;

  /*
  * The HTTP protocol version used by the current message being
  * parsed. The major and minor numbers are broken up since they
  * will be moved into a clojure vector.
  */
  private short httpMajor;
  private short httpMinor;

  /*
  * Tracks whether the current parser instance is parsing an HTTP
  * request or an HTTP response. Even though the parser can be
  * reused to parse multiple messages, each message must be of the
  * same type. In other words, if the first message a parser
  * instance parses is an HTTP request, then all subsequent
  * messages parsed by the same instance must also be HTTP
  * requests.
  */
  final private MessageType type;

  /*
  * Tracks the HTTP method of the currently parsed request. If the
  * HTTP message being currently parsed is a response, then this
  * will be nil.
  */
  private String method;

  /*
  * The response status if the current message being parsed is a
  * response.
  */
  private short status;

  /*
   * Tracks the various message information
   */
  private URI          uri;
  private ChunkedValue uriMark;
  private String       headerName;
  private ChunkedValue headerNameChunks;
  private HeaderValue  headerValue;

  /*
   * Track the content length of the HTTP message
   */
  private long contentLength;

  /*
   * Possibly aggregated body
   */
  private Buffer body;

  /*
   * A queue of the HTTP methods from the requests that generated the responses
   * being parsed.
   *
   * This is only used when parsing HTTP responses since when making a HEAD
   * request, a response will be returned containing information indicating
   * that there will be a response body but there will be no body. Before
   * parsing an HTTP response body, the parser will poll this queue. If the
   * value is HEAD (as an interned string), then the response body will be
   * skipped.
   */
  final private Queue<String> methodQueue;

  /*
   * The object that gets called on various parse events.
   */
  final private HttpParserCallback callback;

  public final static HttpParser request(HttpParserCallback callback) {
    return new HttpParser(MessageType.REQUEST, null, callback);
  }

  public final static HttpParser response(Queue<String> q, HttpParserCallback callback) {
    return new HttpParser(MessageType.RESPONSE, q, callback);
  }

  public HttpParser(MessageType type, Queue<String> q, HttpParserCallback callback) {
    %% write init;

    this.type        = type;
    this.callback    = callback;
    this.methodQueue = q;

    reset();
  }

  public boolean isHttp11() {
    return httpMajor == 1 && httpMinor == 1;
  }

  public boolean isRequest() {
    return type == MessageType.REQUEST;
  }

  public boolean isResponse() {
    return type == MessageType.RESPONSE;
  }

  public boolean isParsingHead() {
    return ( flags & PARSING_HEAD ) == PARSING_HEAD;
  }

  public boolean hasBody() {
    return (isIdentityBody() || isChunkedBody()) &&
      (isRequest() || (status >= 200 && status != 204 && status != 304));
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

  public boolean isConnectionClose() {
    return ( flags & CONN_CLOSE ) == CONN_CLOSE;
  }

  public boolean isUpgrade() {
    return ( flags & UPGRADE ) == UPGRADE || method == MTH_CONNECT;
  }

  public boolean isExpectingContinue() {
    return ( flags & EXPECT_CONTINUE ) == EXPECT_CONTINUE;
  }

  public boolean isError() {
    return ( flags & ERROR ) == ERROR;
  }

  public String getMethod() {
    return method;
  }

  public short getHttpMajor() {
    return httpMajor;
  }

  public short getHttpMinor() {
    return httpMinor;
  }

  public String getPathInfo() {
    String pathInfo = uri.getPath();

    if (pathInfo == null) {
      return SLASH;
    }
    else if (pathInfo.equals(EMPTY_STRING)) {
      return SLASH;
    }

    return pathInfo;
  }

  public String getQueryString() {
    String qs = uri.getQuery();

    if (qs == null) {
      return EMPTY_STRING;
    }

    return qs;
  }

  public int execute(Buffer buf) {
    // First make sure that the parser isn't in an error state
    if (isError()) {
      throw new HttpParserException("The parser is in an error state.");
    }

    // Setup ragel variables
    int p   = buf.position();
    int pe  = buf.limit();
    int eof = pe + 1;

    if (isParsingHead()) {
      bridge(buf, uriMark);
      bridge(buf, headerNameChunks);
      bridge(buf, headerValue);
    }

    try {
      parseLoop: {
        %% getkey buf.get(p);
        %% write exec;
      }
    }
    catch (RuntimeException e) {
      flags |= ERROR;
      throw e;
    }

    return p;
  }

  private void setHeaderName(String name) {
    headerName       = name;
    headerNameChunks = null;
  }

  private void bridge(Buffer buf, ChunkedValue chunk) {
    if (chunk != null) {
      chunk.bridge(buf);
    }
  }

  private void reset() {
    flags         = 0;
    hread         = 0;
    status        = 0;
    httpMajor     = 0;
    httpMinor     = 9;
    contentLength = 0;
  }

  private void resetHeadState() {
    headers          = null;
    method           = null;
    uri              = null;
    uriMark          = null;
    headerName       = null;
    headerNameChunks = null;
    headerValue      = null;
  }

  private Buffer slice(Buffer buf, int from, int to) {
    Buffer retval = buf.duplicate();

    retval.position(from);
    retval.limit(to);

    return retval;
  }

  private int min(long a, int b) {
    long cappedA = Math.min((long) Integer.MAX_VALUE, a);
    return Math.min((int) cappedA, b);
  }

  private String parseErrorMsg(Buffer buf, int fpc) {
    int from = Math.max(0, fpc - 35);
    int to   = Math.min(fpc + 35, buf.limit());

    Buffer before = slice(buf, from, fpc);
    Buffer after  = slice(buf, fpc, to);

    byte[] beforeBytes = new byte[before.remaining()];
    byte[] afterBytes  = new byte[after.remaining()];

    before.get(beforeBytes);
    after.get(afterBytes);

    return new String(beforeBytes) + "|" + new String(afterBytes);
  }
}
