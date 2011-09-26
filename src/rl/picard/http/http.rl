%%{
  machine http;

  include uri "uri.rl";

  CRLF = "\r\n";
   CTL = (cntrl | 127);
    WS = ( " " | "\t" );
   LWS = CRLF ? WS *;
  TEXT = any -- CTL;
  LINE = TEXT -- CRLF;

  separators = "(" | ")" | "<" | ">" | "@" | "," | ";"
             | ":" | "\\" | "\"" | "/" | "[" | "]"
             | "?" | "=" | "{" | "}" | " " | "\t";

        token = TEXT -- separators;
   token_w_sp = token | " " | "\t";
   quoted_str = "\"" ((any -- "\"") | ("\\" any)) * "\"";
    parameter = token + "=" ( token + | quoted_str );
  paramed_val = token * ( ";" ) ?;

  # === HTTP methods
  method = "HEAD"        % method_head
         | "GET"         % method_get
         | "POST"        % method_post
         | "PUT"         % method_put
         | "DELETE"      % method_delete
         | "CONNECT"     % method_connect
         | "OPTIONS"     % method_options
         | "TRACE"       % method_trace
         | "COPY"        % method_copy
         | "LOCK"        % method_lock
         | "MKCOL"       % method_mkcol
         | "MOVE"        % method_move
         | "PROPFIND"    % method_propfind
         | "PROPPATCH"   % method_proppatch
         | "UNLOCK"      % method_unlock
         | "REPORT"      % method_report
         | "MKACTIVITY"  % method_mkactivity
         | "CHECKOUT"    % method_checkout
         | "MERGE"       % method_merge
         | "MSEARCH"     % method_msearch
         | "NOTIFY"      % method_notify
         | "SUBSCRIBE"   % method_subscribe
         | "UNSUBSCRIBE" % method_unsubscribe
         | "PATCH"       % method_patch
         ;

  # === HTTP request URI
  request_uri = ( "*" | uri );

  # === HTTP version
  http_version = "HTTP/"i ( digit + $ http_major ) "."
                          ( digit + $ http_minor );


  # === HTTP headers
  header_sep = WS * ":" WS *;
  header_eol = WS * CRLF;

  header_name = "accept"i                    % hn_accept
              | "accept-charset"i            % hn_accept_charset
              | "accept-encoding"i           % hn_accept_encoding
              | "accept-language"i           % hn_accept_language
              | "accept-ranges"i             % hn_accept_ranges
              | "age"i                       % hn_age
              | "allow"i                     % hn_allow
              | "authorization"i             % hn_authorization
              | "cache-control"i             % hn_cache_control
              | "connection"i                % hn_connection
              | "content-encoding"i          % hn_content_encoding
              | "content-language"i          % hn_content_language
              | "content-length"i            % hn_content_length
              | "content-location"i          % hn_content_location
              | "content-md5"i               % hn_content_md5
              | "content-disposition"i       % hn_content_disposition
              | "content-range"i             % hn_content_range
              | "content-type"i              % hn_content_type
              | "cookie"i                    % hn_cookie
              | "date"i                      % hn_date
              | "dnt"i                       % hn_dnt
              | "etag"i                      % hn_etag
              | "expect"i                    % hn_expect
              | "expires"i                   % hn_expires
              | "from"i                      % hn_from
              | "host"i                      % hn_host
              | "if-match"i                  % hn_if_match
              | "if-modified-since"i         % hn_if_modified_since
              | "if-none-match"i             % hn_if_none_match
              | "if-range"i                  % hn_if_range
              | "if-unmodified-since"i       % hn_if_unmodified_since
              | "keep-alive"i                % hn_keep_alive
              | "last-modified"i             % hn_last_modified
              | "link"i                      % hn_link
              | "location"i                  % hn_location
              | "max-forwards"i              % hn_max_forwards
              | "p3p"i                       % hn_p3p
              | "pragma"i                    % hn_pragma
              | "proxy-authenticate"i        % hn_proxy_authenticate
              | "proxy-authorization"i       % hn_proxy_authorization
              | "range"i                     % hn_range
              | "referer"i                   % hn_referer
              | "refresh"i                   % hn_refresh
              | "retry-after"i               % hn_retry_after
              | "server"i                    % hn_server
              | "set-cookie"i                % hn_set_cookie
              | "strict-transport-security"i % hn_strict_transport_security
              | "te"i                        % hn_te
              | "trailer"i                   % hn_trailer
              | "transfer-encoding"i         % hn_transfer_encoding
              | "upgrade"i                   % hn_upgrade
              | "user-agent"i                % hn_user_agent
              | "vary"i                      % hn_vary
              | "via"i                       % hn_via
              | "warning"i                   % hn_warning
              | "www-authenticate"i          % hn_www_authenticate
              | "x-content-type-options"i    % hn_x_content_type_options
              | "x-do-not-track"i            % hn_x_do_not_track
              | "x-forwarded-for"i           % hn_x_forwarded_for
              | "x-forwarded-proto"i         % hn_x_forwarded_proto
              | "x-frame-options"i           % hn_x_frame_options
              | "x-powered-by"i              % hn_x_powered_by
              | "x-requested-with"i          % hn_x_requested_with
              | "x-xss-protection"i          % hn_x_xss_protection
              | ( token + )
                > start_header_name
                % end_header_name
              ;

  tracking_non_whitespace = ( LINE -- WS ) +
                          % end_header_value_non_ws
                          ;

  header_value_line = tracking_non_whitespace ( WS + tracking_non_whitespace ) *
                    | LINE *
                    > start_header_value_line
                    % end_header_value_line
                    ;

  header_value_line_1 = header_value_line CRLF;
  header_value_line_n = WS+ <: header_value_line_1;

  header_value = ( header_value_line_1 header_value_line_n * )
               % end_header_value
               ;

  generic_header = header_name
                   header_sep <:
                   header_value;

  # Header: Content-Length
  # ===
  #
  content_length_val = digit +
                     $ count_content_length
                     % end_content_length
                     ;

  content_length = "content-length"i
                   header_sep
                   content_length_val $lerr(content_length_err)
                   header_eol;

  # Header: Transfer-Encoding
  # ===
  #
  transfer_encoding = "transfer-encoding"i
                      header_sep
                      "chunked"i
                        % end_transfer_encoding_chunked
                      header_eol;

  # Header: Connection
  # ===
  # This probably isn't completely right since mutliple tokens
  # are permitted in the Connection header.
  # http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.10
  #
  connection = "connection"i
               header_sep
               ( "close"i   % end_connection_close
               | "upgrade"i % end_connection_upgrade )
               header_eol;

  header = content_length
         | transfer_encoding
         | connection
         | generic_header
         ;

  headers = header *;

  # === HTTP head
  request_line  = method " " + request_uri " " + http_version CRLF;
  exchange_head = ( request_line headers CRLF ) > start_head @ end_head;

  # === HTTP chunked body
  chunk_ext  = ";" TEXT *;
  last_chunk = '0' + chunk_ext ? CRLF;
  chunk_size = ( xdigit + - '0' + )
                 > start_chunk_size
                 $ count_chunk_size
                 $! chunk_size_err;

  chunk_head = chunk_size chunk_ext ? CRLF;
  chunk_body = any
                $ handle_chunk
                when handling_body;

  chunk_tail = last_chunk
                 % last_chunk
               ( TEXT + CRLF ) *;

  chunk = chunk_head chunk_body * <: CRLF;

  chunked_body := ( chunk + chunk_tail CRLF )
                    $! something_went_wrong;

  # === HTTP identity body
  identity_chunk = any
                     $ handle_body
                     when handling_body;

  identity_body := identity_chunk *
                     $! something_went_wrong;

  # === Upgraded connections
  upgraded := any * $ handle_message;

  main := exchange_head +
            $ count_message_head
            $err(something_went_wrong);

}%%
