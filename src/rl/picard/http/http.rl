%%{
  machine http;

  include uri "uri.rl";

  CRLF = "\r\n";
  CTL  = (cntrl | 127);
  LWS  = CRLF ? ( " " | "\t" ) +;
  TEXT = any -- CTL;
  LINE = TEXT -- CRLF;

  separators = "(" | ")" | "<" | ">" | "@" | "," | ";"
             | ":" | "\\" | "\"" | "/" | "[" | "]"
             | "?" | "=" | "{" | "}" | " " | "\t";

  token      = TEXT -- separators;
  token_w_sp = token | " " | "\t";
  quoted_str = "\"" ((any -- "\"") | ("\\" any)) * "\"";

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
  header_sep = ":" " " *;
  header_eol = " " * CRLF;

  header_value_line_1 = LINE *;
  header_value_line_n = ( ( " " | "\t" ) * ) <: LINE *;
  header_value        = ( header_value_line_1 ( header_eol <: header_value_line_n ) * )
                        > start_header_value % end_header_value;

  header_name = ( token + ) > start_header_name % end_header_name;

  # Generic headers will catch any header that was not explicitly listed
  generic_header = header_name header_sep <: header_value :> header_eol;

  headers = generic_header *;

  # === HTTP head
  request_line = method " " request_uri " " http_version CRLF;
  request_head = ( request_line headers CRLF ) > start_head @ end_head;

  main := request_head +;
}%%
