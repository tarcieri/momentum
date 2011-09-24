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
  method = "HEAD"        @ method_head
         | "GET"         @ method_get
         | "POST"        @ method_post
         | "PUT"         @ method_put
         | "DELETE"      @ method_delete
         | "CONNECT"     @ method_connect
         | "OPTIONS"     @ method_options
         | "TRACE"       @ method_trace
         | "COPY"        @ method_copy
         | "LOCK"        @ method_lock
         | "MKCOL"       @ method_mkcol
         | "MOVE"        @ method_move
         | "PROPFIND"    @ method_propfind
         | "PROPPATCH"   @ method_proppatch
         | "UNLOCK"      @ method_unlock
         | "REPORT"      @ method_report
         | "MKACTIVITY"  @ method_mkactivity
         | "CHECKOUT"    @ method_checkout
         | "MERGE"       @ method_merge
         | "MSEARCH"     @ method_msearch
         | "NOTIFY"      @ method_notify
         | "SUBSCRIBE"   @ method_subscribe
         | "UNSUBSCRIBE" @ method_unsubscribe
         | "PATCH"       @ method_patch
         ;

  # === HTTP request URI
  request_uri = ( "*" | uri );

  # === HTTP version
  http_version = "HTTP/" ( digit + $ http_major ) "."
                         ( digit + $ http_minor );


  # === HTTP headers
  header_sep = WS * ":";
  header_eol = WS * CRLF;

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

  header_value = ( WS * <: header_value_line_1 header_value_line_n * )
               % end_header_value
               ;

  header_name = ( token + )
              > start_header_name
              % end_header_name;

  generic_header = header_name header_sep header_value;

  # Header: Content-Length
  # ===
  #
  content_length_val = digit +
                     $ count_content_length
                     % end_content_length
                     $err(content_length_err)
                     ;

  content_length = "content-length"i
                   header_sep
                   content_length_val
                   header_eol;

  # Header: Transfer-Encoding
  # ===
  #

  te_chunked = "chunked"i
             % end_transfer_encoding_chunked
             ;

  transfer_encoding = "transfer-encoding"i header_sep te_chunked header_eol;

  # header = content_length
  #        | transfer_encoding
  #        | generic_header
  #        ;

  header = generic_header;

  headers = header *;

  # === HTTP head
  request_line = method " " request_uri " " http_version CRLF;
  request_head = ( request_line headers CRLF ) > start_head @ end_head;
  exchanges    = ( request_head + )
               $err(something_went_wrong)
               ;

  main := exchanges;
}%%
