%%{

  machine common;

  # ==== COMMON HEADER ACTIONS ====

  action start_header_name {
    mark = fpc;
  }

  action end_header_name {
    if (headerName == null) {
      if (tmpBuf.position() > 0) {
        tmpBuf.put(buf, mark, fpc - mark);
        tmpBuf.flip();

        headerName = tmpBuf.toString(UTF_8).toLowerCase();

        tmpBuf.clear();
      }
      else {
        headerName = buf.toString(mark, fpc - mark, UTF_8).toLowerCase();
      }
    }

    mark = -1;
  }

  action start_header_value_line {
    if (headerValueMark >= mark) {
      if (headerValueMark > mark)
        tmpBuf.put(buf, mark, headerValueMark - mark);

      maybeHeaderValueEnd = -1;
    }

    if (tmpBuf.position() > 0) {
      if (maybeHeaderValueEnd >= 0)
        tmpBuf.position(maybeHeaderValueEnd);

      tmpBuf.put(HttpParser.SP);
    }

    mark = fpc;
    headerValueMark = -1;
  }

  action end_header_value_no_ws {
    headerValueMark = fpc;
  }

  action end_header_value_line {
  }

  action end_header_value {
    if (headerName != null) {
      String val;

      if (tmpBuf.position() > 0) {
        if (headerValueMark >= mark) {
          // if (mark == -1)
          //   mark = 0;
          if (headerValueMark > mark)
            tmpBuf.put(buf, mark, headerValueMark - mark);
        }
        else if (maybeHeaderValueEnd >= 0) {
          tmpBuf.position(maybeHeaderValueEnd);
        }

        tmpBuf.flip();
        val = tmpBuf.toString(UTF_8);

        tmpBuf.clear();
      }
      else if (headerValueMark > mark) {
        val = buf.toString(mark, headerValueMark - mark, UTF_8);
      }
      else {
        val = HttpParser.EMPTY_STRING;
      }

      headers = callback.header(headers, headerName, val);
      headerName = null;
    }

    mark = -1;
    headerValueMark = -1;
  }

  # ==== TOKENS ====

        CRLF = "\r" ? "\n"; # Fuck you news.ycombinator.com
         CTL = (cntrl | 127);
        LWSP = " " | "\t";
         LWS = CRLF ? LWSP *;
        TEXT = any -- CTL;
        LINE = TEXT -- CRLF;
  separators = "(" | ")" | "<" | ">" | "@" | "," | ";"
             | ":" | "\\" | "\"" | "/" | "[" | "]"
             | "?" | "=" | "{" | "}" | " " | "\t"
             ;
       token = TEXT -- separators;

  # ==== HEADERS ====

         header_sep = LWSP * ":" LWSP *;
         header_eol = LWSP * CRLF;
            ws_line = LWSP +;
         no_ws_line = ( LINE -- LWSP ) + % end_header_value_no_ws;
         blank_line = "" % end_header_value_no_ws;
     non_blank_line = no_ws_line ( ws_line no_ws_line) * ws_line ?;
  header_value_line = ( blank_line | non_blank_line )
                    > start_header_value_line
                    % end_header_value_line
                    ;

  header_value_line_1 = header_value_line CRLF;
  header_value_line_n = LWSP + <: header_value_line_1;
         header_value = header_value_line_1 header_value_line_n *;
  generic_header_name = token +
                          > start_header_name
                          % end_header_name;


}%%
