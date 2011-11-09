
// line 1 "src/rl/momentum/http/MultipartParser.rl"
package momentum.http;

import momentum.core.Buffer;

public final class MultipartParser {

    static final byte CR        = (byte) 0x0D;
    static final byte LF        = (byte) 0x0A;
    static final byte DASH      = (byte) 0x2D;
    static final byte [] PREFIX = new byte [] { CR, LF, DASH, DASH };

    
// line 184 "src/rl/momentum/http/MultipartParser.rl"


    private int cs;
    private int delimiterPos;

    private boolean parsingBody;

    private Object       headers;
    private String       headerName;
    private ChunkedValue headerNameChunks;
    private HeaderValue  headerValue;

    private final Buffer delimiter;

    private final MultipartParserCallback callback;

    
// line 34 "src/jvm/momentum/http/MultipartParser.java"
private static byte[] init__multipart_actions_0()
{
	return new byte [] {
	    0,    1,    0,    1,    1,    1,    2,    1,    4,    1,    5,    1,
	    7,    1,    8,    1,    9,    1,   10,    1,   11,    2,    2,    6,
	    2,    3,    0,    2,    9,   10,    2,   11,    6,    3,    8,    9,
	   10
	};
}

private static final byte _multipart_actions[] = init__multipart_actions_0();


private static byte[] init__multipart_key_offsets_0()
{
	return new byte [] {
	    0,    0,    0,    0,    4,    7,    8,   23,   24,   24,   41,   44,
	   50,   51,   68,   74,   80,   81
	};
}

private static final byte _multipart_key_offsets[] = init__multipart_key_offsets_0();


private static char[] init__multipart_trans_keys_0()
{
	return new char [] {
	    9,   13,   32,   45,    9,   13,   32,   10,   13,   34,   44,   47,
	  123,  125,  127,    0,   32,   40,   41,   58,   64,   91,   93,   10,
	    9,   32,   34,   44,   47,   58,  123,  125,  127,    0,   31,   40,
	   41,   59,   64,   91,   93,    9,   32,   58,    9,   13,   32,  127,
	    0,   31,   10,    9,   13,   32,   34,   44,   47,  123,  125,  127,
	    0,   31,   40,   41,   58,   64,   91,   93,    9,   13,   32,  127,
	    0,   31,    9,   13,   32,  127,    0,   31,   45,    0
	};
}

private static final char _multipart_trans_keys[] = init__multipart_trans_keys_0();


private static byte[] init__multipart_single_lengths_0()
{
	return new byte [] {
	    0,    0,    0,    4,    3,    1,    7,    1,    0,    9,    3,    4,
	    1,    9,    4,    4,    1,    0
	};
}

private static final byte _multipart_single_lengths[] = init__multipart_single_lengths_0();


private static byte[] init__multipart_range_lengths_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    4,    0,    0,    4,    0,    1,
	    0,    4,    1,    1,    0,    0
	};
}

private static final byte _multipart_range_lengths[] = init__multipart_range_lengths_0();


private static byte[] init__multipart_index_offsets_0()
{
	return new byte [] {
	    0,    0,    1,    2,    7,   11,   13,   25,   27,   28,   42,   46,
	   52,   54,   68,   74,   80,   82
	};
}

private static final byte _multipart_index_offsets[] = init__multipart_index_offsets_0();


private static byte[] init__multipart_indicies_0()
{
	return new byte [] {
	    0,    1,    3,    4,    3,    5,    2,    3,    4,    3,    2,    6,
	    2,    7,    2,    2,    2,    2,    2,    2,    2,    2,    2,    2,
	    8,    9,    2,   10,   11,   11,    2,    2,    2,   13,    2,    2,
	    2,    2,    2,    2,    2,   12,   14,   14,   15,    2,   15,   16,
	   15,    2,    2,   17,   18,    2,   15,   19,   15,    2,    2,    2,
	    2,    2,    2,    2,    2,    2,    2,   20,   21,   22,   21,    2,
	    2,   23,   24,   25,   24,    2,    2,   23,   26,    2,   27,    0
	};
}

private static final byte _multipart_indicies[] = init__multipart_indicies_0();


private static byte[] init__multipart_trans_targs_0()
{
	return new byte [] {
	    1,    2,    0,    4,    5,   16,    6,    7,    9,    8,    1,   10,
	    9,   11,   10,   11,   12,   14,   13,    7,    9,   15,   12,   14,
	   15,   12,    4,   17
	};
}

private static final byte _multipart_trans_targs[] = init__multipart_trans_targs_0();


private static byte[] init__multipart_trans_actions_0()
{
	return new byte [] {
	    1,    3,    9,    0,    0,    0,    0,    5,   21,    0,   24,   11,
	    0,   11,    0,    0,   33,   13,    0,   19,   30,   15,   27,    0,
	    0,   17,    7,    0
	};
}

private static final byte _multipart_trans_actions[] = init__multipart_trans_actions_0();


private static byte[] init__multipart_eof_actions_0()
{
	return new byte [] {
	    0,    9,    9,    9,    9,    9,    9,    9,    9,    9,    9,    9,
	    9,    9,    9,    9,    9,    9
	};
}

private static final byte _multipart_eof_actions[] = init__multipart_eof_actions_0();


static final int multipart_start = 1;
static final int multipart_first_final = 18;
static final int multipart_error = 0;

static final int multipart_en_main = 1;
static final int multipart_en_main_multipart_start = 1;
static final int multipart_en_main_multipart_delimiter = 2;
static final int multipart_en_main_multipart_head = 3;
static final int multipart_en_main_multipart_body = 1;
static final int multipart_en_main_multipart_epilogue = 17;


// line 201 "src/rl/momentum/http/MultipartParser.rl"

    public MultipartParser(Buffer boundary, MultipartParserCallback callback) {
        
// line 175 "src/jvm/momentum/http/MultipartParser.java"
	{
	cs = multipart_start;
	}

// line 204 "src/rl/momentum/http/MultipartParser.rl"

        Buffer delimiter = Buffer.allocate(4 + boundary.remaining());

        delimiter.put(PREFIX);
        delimiter.put(boundary);
        delimiter.flip();

        this.delimiter = delimiter;
        this.callback  = callback;
    }

    public void execute(Buffer buf) {
        int bodyStart = 0;
        int bodyEnd   = 0;

        // Setup ragel variables
        int p   = buf.position();
        int pe  = buf.limit();
        int eof = pe + 1;

        bridge(buf, headerNameChunks);
        bridge(buf, headerValue);

        // System.out.println("================== '" +
        //                    new String(buf.array()).
        //                    replaceAll("\n", "\\\\n").
        //                    replaceAll("\r", "\\\\r")
        //                    + "'");

        
// line 234 "src/rl/momentum/http/MultipartParser.rl"
        
// line 213 "src/jvm/momentum/http/MultipartParser.java"
	{
	int _klen;
	int _trans = 0;
	int _acts;
	int _nacts;
	int _keys;
	int _goto_targ = 0;

	_goto: while (true) {
	switch ( _goto_targ ) {
	case 0:
	if ( p == pe ) {
		_goto_targ = 4;
		continue _goto;
	}
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
case 1:
	_match: do {
	_keys = _multipart_key_offsets[cs];
	_trans = _multipart_index_offsets[cs];
	_klen = _multipart_single_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + _klen - 1;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + ((_upper-_lower) >> 1);
			if ( ( buf.get(p)) < _multipart_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( ( buf.get(p)) > _multipart_trans_keys[_mid] )
				_lower = _mid + 1;
			else {
				_trans += (_mid - _keys);
				break _match;
			}
		}
		_keys += _klen;
		_trans += _klen;
	}

	_klen = _multipart_range_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( ( buf.get(p)) < _multipart_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( ( buf.get(p)) > _multipart_trans_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				_trans += ((_mid - _keys)>>1);
				break _match;
			}
		}
		_trans += _klen;
	}
	} while (false);

	_trans = _multipart_indicies[_trans];
	cs = _multipart_trans_targs[_trans];

	if ( _multipart_trans_actions[_trans] != 0 ) {
		_acts = _multipart_trans_actions[_trans];
		_nacts = (int) _multipart_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _multipart_actions[_acts++] )
			{
	case 0:
// line 15 "src/rl/momentum/http/MultipartParser.rl"
	{
            // System.out.println("#peek_delimiter: BEGIN - " + fpc);

            // If the current character is CR, then we should start
            // attempting to match the delimiter.
            if (CR == ( buf.get(p))) {
                bodyEnd = p;

                // Start at 1 since the first character has already
                // been matched.
                int curr  = 0;
                int limit = Math.min(delimiter.limit(), buf.limit() - p);

                peek_delimiter: {
                    // System.out.println("Starting loop: " + fpc + " - " + limit + " - " + curr);

                    while (++curr < limit) {
                        if (delimiter.get(curr) != buf.get(p + curr)) {
                            // System.out.println("FAIL: " + ( fpc + curr ));
                            {p = (( p + curr))-1;}
                            break peek_delimiter;
                        }
                    }

                    // System.out.println("MATCHED: " + ( fpc + curr));

                    // The delimiter has been matched
                    if (curr == delimiter.limit()) {
                        // System.out.println("-- Full delimiter match");

                        if (parsingBody) {

                            // If the body start pointer is greater
                            // than zero, then the entire body is in
                            // the current chunk.
                            if (headers != null) {
                                callback.part(headers, slice(buf, bodyStart, bodyEnd));
                                headers = null;
                            }
                            else {
                                if (bodyEnd > bodyStart) {
                                    // System.out.println("FINAL CHUNK: " + buf + " " + bodyStart +
                                    //                    " - " + bodyEnd);
                                    callback.chunk(slice(buf, bodyStart, bodyEnd));
                                }

                                callback.chunk(null);
                            }

                            bodyStart = 0;
                            bodyEnd   = 0;
                        }

                        cs = 3;
                        {p = (( p + curr))-1;}
                    }
                    // The end of the current buffer has been reached
                    else {
                        // System.out.println("-- Partial delimiter match");
                        // System.out.println("-- start: " + bodyStart + ", end: " + bodyEnd);

                        if (parsingBody) {

                            if (headers != null) {
                                callback.part(headers, null);
                                headers = null;
                            }

                            if (bodyEnd > bodyStart) {
                                callback.chunk(slice(buf, bodyStart, bodyEnd));
                            }

                            bodyStart = 0;
                            bodyEnd   = 0;
                        }

                        delimiterPos = curr;

                        // System.out.println("#peek_delimiter: fgoto delimiter");
                        cs = 2;

                        // System.out.println("#peek_delimiter: return");
                        return;
                    }
                }
            }
            else {
                bodyEnd = p + 1;
            }
        }
	break;
	case 1:
// line 106 "src/rl/momentum/http/MultipartParser.rl"
	{
            int curr  = 0;
            int limit = Math.min(delimiter.limit() - delimiterPos, buf.limit());

            // System.out.println("#parse_delimiter: BEGIN - " + fpc + ", " + delimiterPos);

            parse_delimiter: {
                while (curr < limit) {
                    if (delimiter.get(curr + delimiterPos) != buf.get(curr)) {
                        // System.out.println("#parse_delimiter: FAIL - " + (fpc + curr));

                        if (parsingBody) {
                            // System.out.println(delimiterPos + " + " + curr + ", " + fpc);
                            callback.chunk(slice(delimiter, 0, delimiterPos + curr));

                            bodyStart = p + curr;

                            {p = (( p + curr))-1;}
                            cs = 1;
                        }
                        else {
                            {p = (( p + curr))-1;}
                            cs = 1;
                        }

                        break parse_delimiter;
                    }

                    ++curr;
                }

                // The delimiter has been matched
                if (curr == delimiter.limit() - delimiterPos) {
                    // System.out.println("#parse_delimiter: MATCH FULL - " + fpc);

                    if (parsingBody) {
                        callback.chunk(null);
                    }

                    cs = 3;
                    {p = (( p + curr))-1;}
                }
                // The end fo the current buffer has been reached
                else {
                    // System.out.println("#parse_delimiter: MATCH PARTIAL - " + fpc);
                    delimiterPos += curr;
                    return;
                }
            }
        }
	break;
	case 2:
// line 157 "src/rl/momentum/http/MultipartParser.rl"
	{
            parsingBody = true;
            // System.out.println("Starting headers: " + fpc);

            headers = callback.blankHeaders();
            bodyStart = 0;
            bodyEnd   = 0;
        }
	break;
	case 3:
// line 166 "src/rl/momentum/http/MultipartParser.rl"
	{
            // System.out.println("Ending headers: " + fpc);
            bodyStart  = p;
        }
	break;
	case 4:
// line 171 "src/rl/momentum/http/MultipartParser.rl"
	{
            // System.out.println("~~~~ ALL DONE");
            callback.done();
            {cs = 17; _goto_targ = 2; if (true) continue _goto;}
        }
	break;
	case 5:
// line 177 "src/rl/momentum/http/MultipartParser.rl"
	{
            if (true) {
                throw new HttpParserException("Something went wrong: " + p);
            }
        }
	break;
	case 6:
// line 7 "src/rl/momentum/http/common.rl"
	{
      headerNameChunks = new ChunkedValue(buf, p);
  }
	break;
	case 7:
// line 11 "src/rl/momentum/http/common.rl"
	{
      if (headerNameChunks != null) {
          headerNameChunks.push(p);

          headerName       = headerNameChunks.materializeStr().toLowerCase();
          headerNameChunks = null;
      }
  }
	break;
	case 8:
// line 20 "src/rl/momentum/http/common.rl"
	{
      if (headerValue == null) {
          headerValue = new HeaderValue(buf, p);
      }
      else {
          headerValue.startLine(buf, p);
      }
  }
	break;
	case 9:
// line 29 "src/rl/momentum/http/common.rl"
	{
      if (headerValue != null) {
          headerValue.mark(p);
      }
  }
	break;
	case 10:
// line 35 "src/rl/momentum/http/common.rl"
	{
      if (headerValue != null) {
          headerValue.push();
      }
  }
	break;
	case 11:
// line 41 "src/rl/momentum/http/common.rl"
	{
      if (headerValue != null) {
          callback.header(headers, headerName, headerValue.materializeStr());

          headerName  = null;
          headerValue = null;
      }
      else if (headerName != null) {
          callback.header(headers, headerName, HttpParser.EMPTY_STRING);
          headerName = null;
      }
  }
	break;
// line 532 "src/jvm/momentum/http/MultipartParser.java"
			}
		}
	}

case 2:
	if ( cs == 0 ) {
		_goto_targ = 5;
		continue _goto;
	}
	if ( ++p != pe ) {
		_goto_targ = 1;
		continue _goto;
	}
case 4:
	if ( p == eof )
	{
	int __acts = _multipart_eof_actions[cs];
	int __nacts = (int) _multipart_actions[__acts++];
	while ( __nacts-- > 0 ) {
		switch ( _multipart_actions[__acts++] ) {
	case 5:
// line 177 "src/rl/momentum/http/MultipartParser.rl"
	{
            if (true) {
                throw new HttpParserException("Something went wrong: " + p);
            }
        }
	break;
// line 561 "src/jvm/momentum/http/MultipartParser.java"
		}
	}
	}

case 5:
	}
	break; }
	}

// line 235 "src/rl/momentum/http/MultipartParser.rl"

        // System.out.println("~~~ DONE WITH PARSING LOOP ~~~");
        // System.out.println("start: " + bodyStart + ", end: " + bodyEnd);
        // System.out.println(headers == null);

        if (parsingBody && bodyEnd > bodyStart) {
            // System.out.println("FLUSHING CHUNK: " + bodyStart + " - " + buf.limit());

            if (headers != null) {
                callback.part(headers, null);
                headers = null;
            }

            callback.chunk(slice(buf, bodyStart, buf.limit()));
        }
    }

    private void bridge(Buffer buf, ChunkedValue chunk) {
        if (chunk != null) {
            chunk.bridge(buf);
        }
    }

    private Buffer slice(Buffer buf, int from, int to) {
        Buffer chunk = buf.duplicate();

        chunk.position(from);
        chunk.limit(to);

        return chunk;
    }
}
