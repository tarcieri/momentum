%%{

  machine multipart;

  include common "common.rl";

  bchar = alnum | "'" | "(" | ")" | "+" | "_" | ","
        | "-" | "." | "/" | ":" | "=" | "?"
        ;

   preamble = any * CRLF;
   boundary = any $ parse_boundary when parsing_boundary;
      final = "--" @ end_parts ;
    padding = final ? LWSP * CRLF;
  delimiter = ( CRLF "--" boundary * )
                > start_delimiter;

  # ==== HEADERS ====
  header_name = generic_header_name;
       header = header_name header_sep header_value % end_header_value;
      headers = header * CRLF;

  multipart =
    start:
      preamble :>> delimiter padding -> head,

    head:
      headers
        > start_head
        % end_head
      -> body,

    body:
      any * :>> delimiter % end_part padding -> head,

    epilogue: any *;

  main := multipart $! something_went_wrong;

}%%
