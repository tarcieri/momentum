%%{
  machine uri;

  # === URI spec
  delims        = ( "<" | ">" | "#" | "%" | "\"" );
  unwise        = ( "{" | "}" | "|" | "^" | "[" | "]" | "`" | "\\" );
  mark          = ( "-" | "_" | "." | "!" | "~" | "*" | "'" | "(" | ")" );
  reserved      = ( ";" | "/" | "?" | ":" | "@" | "&" | "=" | "+" | "$" | "," );
  escaped       = ( "%" "u"? xdigit xdigit ); # The optional u is to handle an IE 7 bug
  unreserved    = ( alnum | mark );
  ie6_hax       = ( "\"" | "<" | ">" );
  uric          = ( reserved | unreserved | escaped | ie6_hax );
  pchar         = ( unreserved | escaped | ie6_hax | ":" | "@" | "&" | "=" | "+" | "$" | "," );
  opaque        = ( uric -- "/" ) uric *;

  fragment      = ( uric * );
  query         = ( uric * ) > start_query % end_query;
  param         = ( pchar * );
  segment       = ( pchar * ( ";" param ) * );
  segments      = ( segment ( "/" segment ) * );
  abs_path      = ( "/" segments ) > start_path % end_path;
  path          = ( abs_path | opaque ) ?;

  port          = ( digit ) *;
  ipv4addr      = ( digit + "." digit + "." digit + "." digit + );
  toplabel      = ( alpha | alpha ( alnum | "-" ) * alnum );
  domainlabel   = ( alnum | alnum ( alnum | "-" ) * alnum );
  hostname      = ( domainlabel "." ) * toplabel "." ?;
  host          = ( hostname | ipv4addr );
  hostport      = ( host ( ":" port ) ? );

  userinfo      = ( unreserved | escaped | ";" | ":" | "&" | "=" | "+" | "$" | "," ) *;
  server        = ( ( userinfo "@" ) ? hostport ) ?;
  reg_name      = ( unreserved | escaped | "$" | "," | ";" | ":" | "@" | "&" | "=" | "+" ) +;
  authority     = ( server | reg_name );
  scheme        = ( alpha ( alnum | "+" | "-" | "." ) * );
  rel_segment   = ( unreserved | escaped | ";" | "@" | "&" | "=" | "+" | "$" | "," ) + ;
  rel_path      = ( rel_segment abs_path ? );
  net_path      = ( "//" authority abs_path ? );
  hier_part     = ( net_path | abs_path );

  relative_uri  = ( net_path | abs_path | rel_path ) ( "?" query ) ?;
  absolute_uri  = ( scheme ":" ( ( hier_part ( "?" query ) ? ) | opaque ) );
  uri           = ( absolute_uri | relative_uri ) ( "#" fragment ) ?;
}%%
