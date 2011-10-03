
// line 1 "src/rl/picard/http/MultipartParser.rl"
package picard.http;

import java.nio.ByteBuffer;

public final class MultipartParser {
    
// line 94 "src/rl/picard/http/MultipartParser.rl"


    private int cs;

    private int boundaryPos;

    private int bodyStart;
    private int bodyEnd;

    private Object headers;

    private final ByteBuffer boundary;

    private final MultipartParserCallback callback;

    
// line 27 "src/jvm/picard/http/MultipartParser.java"
private static byte[] init__multipart_actions_0()
{
	return new byte [] {
	    0,    1,    0,    1,    1,    1,    2,    1,    3,    1,    4,    1,
	    5,    1,    6,    2,    1,    0,    2,    1,    2,    2,    1,    3,
	    2,    1,    4,    2,    1,    5,    2,    2,    0,    2,    2,    4,
	    2,    3,    0,    2,    3,    4,    2,    4,    5,    2,    5,    4,
	    3,    1,    2,    0,    3,    1,    2,    4,    3,    1,    4,    5,
	    3,    1,    5,    4,    3,    2,    1,    4,    3,    2,    3,    0,
	    3,    5,    1,    4,    4,    1,    2,    3,    0,    4,    2,    3,
	    0,    4
	};
}

private static final byte _multipart_actions[] = init__multipart_actions_0();


private static byte[] init__multipart_cond_offsets_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    2,    2,    3,    4,    4,    4,
	    4,    4,    5,    6,    6,    6,    6,    6,    6,    6,    7,    7,
	    8,    9,   10,   11,   12,   13,   14,   14,   15,   16,   17,   18,
	   19,   20,   21,   22,   23,   24,   24,   25
	};
}

private static final byte _multipart_cond_offsets[] = init__multipart_cond_offsets_0();


private static byte[] init__multipart_cond_lengths_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    1,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    1,    0,    1,    1,    0,    0,    0,
	    0,    1,    1,    0,    0,    0,    0,    0,    0,    1,    0,    1,
	    1,    1,    1,    1,    1,    1,    0,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    0,    1,    0
	};
}

private static final byte _multipart_cond_lengths[] = init__multipart_cond_lengths_0();


private static int[] init__multipart_cond_keys_0()
{
	return new int [] {
	    0,65535,    0,65535,    0,65535,    0,65535,    0,65535,    0,65535,
	    0,65535,    0,65535,    0,65535,    0,65535,    0,65535,    0,65535,
	    0,65535,    0,65535,    0,65535,    0,65535,    0,65535,    0,65535,
	    0,65535,    0,65535,    0,65535,    0,65535,    0,65535,    0,65535,
	    0,65535,    0
	};
}

private static final int _multipart_cond_keys[] = init__multipart_cond_keys_0();


private static byte[] init__multipart_cond_spaces_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0
	};
}

private static final byte _multipart_cond_spaces[] = init__multipart_cond_spaces_0();


private static short[] init__multipart_key_offsets_0()
{
	return new short [] {
	    0,    0,    1,    3,    4,    6,    8,   10,   18,   21,   22,   23,
	   24,   25,   26,   28,   30,   32,   42,   43,   55,   65,   66,   67,
	   69,   71,   83,   95,   98,  100,  102,  104,  106,  108,  118,  122,
	  132,  140,  150,  160,  170,  182,  192,  194,  206,  216,  226,  236,
	  246,  256,  268,  278,  290,  300,  304,  312
	};
}

private static final short _multipart_key_offsets[] = init__multipart_key_offsets_0();


private static int[] init__multipart_trans_keys_0()
{
	return new int [] {
	   13,   10,   13,   13,   10,   13,   13,   45,   13,   45,65545,65549,
	65568,65581,131085,131117,131072,196607,    9,   13,   32,   10,   13,   10,
	   13,   13,   10,   13,   13,   45,   13,   45,65545,65549,65568,65581,
	131081,131085,131104,131117,131072,196607,   45,65545,65546,65549,65568,65581,
	131081,131082,131085,131104,131117,131072,196607,65545,65549,65568,65581,131081,
	131085,131104,131117,131072,196607,   10,   13,   10,   13,   13,   45,65545,
	65546,65549,65568,65581,131081,131082,131085,131104,131117,131072,196607,65545,
	65549,65568,65581,131081,131085,131104,131117,65536,131071,131072,196607,    9,
	   13,   32,   10,   13,   13,   45,   10,   13,   13,   45,   13,   45,
	65545,65549,65568,65581,131081,131085,131104,131117,131072,196607,    9,   13,
	   32,   45,65545,65546,65549,65568,65581,131082,131085,131117,131072,196607,
	65545,65549,65568,65581,131085,131117,131072,196607,65545,65546,65549,65568,
	65581,131082,131085,131117,131072,196607,65545,65549,65568,65581,131085,131117,
	65536,131071,131072,196607,65545,65549,65568,65581,131085,131117,65536,131071,
	131072,196607,65545,65546,65549,65568,65581,131082,131085,131117,65536,131071,
	131072,196607,65545,65549,65568,65581,131085,131117,65536,131071,131072,196607,
	   13,   45,65545,65546,65549,65568,65581,131082,131085,131117,65536,131071,
	131072,196607,65545,65549,65568,65581,131085,131117,65536,131071,131072,196607,
	65545,65549,65568,65581,131085,131117,65536,131071,131072,196607,65545,65549,
	65568,65581,131081,131085,131104,131117,131072,196607,65545,65549,65568,65581,
	131081,131085,131104,131117,131072,196607,65545,65549,65568,65581,131081,131085,
	131104,131117,131072,196607,65545,65546,65549,65568,65581,131081,131082,131085,
	131104,131117,131072,196607,65545,65549,65568,65581,131081,131085,131104,131117,
	131072,196607,65545,65546,65549,65568,65581,131081,131082,131085,131104,131117,
	131072,196607,65545,65549,65568,65581,131085,131117,65536,131071,131072,196607,
	    9,   13,   32,   45,65545,65549,65568,65581,131085,131117,131072,196607,
	    0
	};
}

private static final int _multipart_trans_keys[] = init__multipart_trans_keys_0();


private static byte[] init__multipart_single_lengths_0()
{
	return new byte [] {
	    0,    1,    2,    1,    2,    2,    2,    6,    3,    1,    1,    1,
	    1,    1,    2,    2,    2,    8,    1,   10,    8,    1,    1,    2,
	    2,   10,    8,    3,    2,    2,    2,    2,    2,    8,    4,    8,
	    6,    8,    6,    6,    8,    6,    2,    8,    6,    6,    8,    8,
	    8,   10,    8,   10,    6,    4,    6,    0
	};
}

private static final byte _multipart_single_lengths[] = init__multipart_single_lengths_0();


private static byte[] init__multipart_range_lengths_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    1,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    1,    0,    1,    1,    0,    0,    0,
	    0,    1,    2,    0,    0,    0,    0,    0,    0,    1,    0,    1,
	    1,    1,    2,    2,    2,    2,    0,    2,    2,    2,    1,    1,
	    1,    1,    1,    1,    2,    0,    1,    0
	};
}

private static final byte _multipart_range_lengths[] = init__multipart_range_lengths_0();


private static short[] init__multipart_index_offsets_0()
{
	return new short [] {
	    0,    0,    2,    5,    7,   10,   13,   16,   24,   28,   30,   32,
	   34,   36,   38,   41,   44,   47,   57,   59,   71,   81,   83,   85,
	   88,   91,  103,  114,  118,  121,  124,  127,  130,  133,  143,  148,
	  158,  166,  176,  185,  194,  205,  214,  217,  228,  237,  246,  256,
	  266,  276,  288,  298,  310,  319,  324,  332
	};
}

private static final short _multipart_index_offsets[] = init__multipart_index_offsets_0();


private static byte[] init__multipart_indicies_0()
{
	return new byte [] {
	    1,    0,    2,    1,    0,    3,    0,    4,    1,    0,    3,    5,
	    0,    1,    6,    0,    8,    9,    8,   10,   12,   13,   11,    7,
	    8,    9,    8,    7,   14,    7,   15,    7,   16,    7,   18,   17,
	   20,   19,   21,   20,   19,   20,   22,   19,   20,   23,   19,   24,
	   25,   24,   26,   28,   29,   28,   30,   27,    7,   31,    7,   24,
	   14,   25,   24,   26,   28,   32,   29,   28,   30,   27,    7,   24,
	   33,   24,   26,   28,   34,   28,   30,   27,    7,   35,    7,   36,
	   17,   37,   20,   19,   18,   38,   17,   24,   35,   25,   24,   26,
	   28,   39,   29,   28,   30,   27,    7,   40,   41,   40,   42,   28,
	   34,   28,   30,   17,   27,    7,   43,   44,   43,   19,   45,   20,
	   19,   46,   22,   19,   47,   20,   19,   36,   38,   17,   20,   48,
	   19,   24,   25,   24,   49,   28,   29,   28,   50,   27,    7,    8,
	    9,    8,   31,    7,    8,   14,    9,    8,   10,   51,   12,   13,
	   11,    7,    8,   52,    8,   10,   53,   13,   11,    7,    8,   35,
	    9,    8,   10,   54,   12,   13,   11,    7,   55,   56,   55,   57,
	   59,   60,   17,   58,    7,   43,   44,   43,   61,   63,   64,   19,
	   62,    7,   43,   45,   44,   43,   61,   65,   63,   64,   19,   62,
	    7,   43,   66,   43,   67,   68,   69,   19,   62,    7,   20,   70,
	   19,   43,   47,   44,   43,   61,   71,   63,   64,   19,   62,    7,
	   55,   56,   55,   72,   59,   73,   17,   58,    7,   43,   44,   43,
	   74,   63,   75,   19,   62,    7,   24,   25,   24,   76,   28,   29,
	   28,   77,   27,    7,   24,   25,   24,   76,   79,   80,   79,   81,
	   78,    7,   24,   25,   24,   26,   79,   80,   79,   82,   78,    7,
	   24,   14,   25,   24,   26,   79,   83,   80,   79,   82,   78,    7,
	   24,   33,   24,   26,   79,   84,   79,   82,   78,    7,   24,   35,
	   25,   24,   26,   79,   39,   80,   79,   82,   78,    7,   43,   44,
	   43,   85,   63,   86,   19,   62,    7,   43,   44,   43,   48,   19,
	    8,    9,    8,   87,   12,   88,   11,    7,   89,    0
	};
}

private static final byte _multipart_indicies[] = init__multipart_indicies_0();


private static byte[] init__multipart_trans_targs_0()
{
	return new byte [] {
	    1,    2,    3,    4,    5,    6,    7,    0,    8,    9,   18,    7,
	   35,   54,   10,   11,   12,   13,   14,   13,   14,   15,   16,   17,
	    8,    9,   18,   17,   17,   19,   33,    8,   20,   21,   25,   22,
	   23,   24,   16,   26,   27,   30,   32,   27,   28,   29,   23,   31,
	   27,   34,   33,   36,   21,   37,   38,   27,   30,   32,   39,   43,
	   52,   32,   39,   40,   52,   41,   30,   42,   43,   45,   17,   44,
	   42,   45,   46,   47,   34,   33,   48,   48,   49,   47,   47,   50,
	   51,   53,   52,   34,   54,   55
	};
}

private static final byte _multipart_trans_targs[] = init__multipart_trans_targs_0();


private static byte[] init__multipart_trans_actions_0()
{
	return new byte [] {
	    0,    0,    0,    1,    0,    0,    0,   13,    0,    0,    0,    3,
	    3,    3,    0,    5,    0,    7,   36,    0,    1,    0,    0,    0,
	    9,    9,    9,    3,   24,   24,   24,   11,    3,   33,   64,    0,
	   68,    0,    7,    3,   39,   81,   39,    0,    1,    0,   30,    0,
	   11,   42,   56,    3,    5,   18,    3,    7,   68,    7,   21,   76,
	   21,    0,    3,   15,    3,    3,   30,    0,   48,    3,   11,    3,
	    7,   21,   11,   27,   45,   72,    3,   24,   24,   60,   24,    3,
	   52,   11,   27,   11,   27,    0
	};
}

private static final byte _multipart_trans_actions[] = init__multipart_trans_actions_0();


private static byte[] init__multipart_eof_actions_0()
{
	return new byte [] {
	    0,   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,
	   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,
	   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,
	   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,   13,
	   13,   13,   13,   13,   13,   13,   13,   13
	};
}

private static final byte _multipart_eof_actions[] = init__multipart_eof_actions_0();


static final int multipart_start = 1;
static final int multipart_first_final = 56;
static final int multipart_error = 0;

static final int multipart_en_main = 1;
static final int multipart_en_main_multipart_start = 1;
static final int multipart_en_main_multipart_body = 13;
static final int multipart_en_main_multipart_epilogue = 55;


// line 110 "src/rl/picard/http/MultipartParser.rl"

    public MultipartParser(ByteBuffer boundary, MultipartParserCallback callback) {
        
// line 290 "src/jvm/picard/http/MultipartParser.java"
	{
	cs = multipart_start;
	}

// line 113 "src/rl/picard/http/MultipartParser.rl"

        this.boundary = boundary;
        this.callback = callback;
    }

    public void execute(ByteBuffer buf) {
        // Setup ragel variables
        int p   = buf.position();
        int pe  = buf.limit();
        int eof = pe + 1;

        
// line 125 "src/rl/picard/http/MultipartParser.rl"
        
// line 310 "src/jvm/picard/http/MultipartParser.java"
	{
	int _klen;
	int _trans = 0;
	int _widec;
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
	_widec = ( buf.get(p));
	_keys = _multipart_cond_offsets[cs]*2
;	_klen = _multipart_cond_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys
;		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( _widec < _multipart_cond_keys[_mid] )
				_upper = _mid - 2;
			else if ( _widec > _multipart_cond_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				switch ( _multipart_cond_spaces[_multipart_cond_offsets[cs] + ((_mid - _keys)>>1)] ) {
	case 0: {
		_widec = 65536 + (( buf.get(p)) - 0);
		if ( 
// line 15 "src/rl/picard/http/MultipartParser.rl"

            boundaryPos < boundary.limit()
         ) _widec += 65536;
		break;
	}
				}
				break;
			}
		}
	}

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
			if ( _widec < _multipart_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( _widec > _multipart_trans_keys[_mid] )
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
			if ( _widec < _multipart_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( _widec > _multipart_trans_keys[_mid+1] )
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
// line 9 "src/rl/picard/http/MultipartParser.rl"
	{
            System.out.println("#start_delimiter - " + p);
            boundaryPos = 0;
            bodyEnd = p;
        }
	break;
	case 1:
// line 19 "src/rl/picard/http/MultipartParser.rl"
	{
            System.out.println("Parsing boundary");
            if (( buf.get(p)) == boundary.get(boundaryPos)) {
                ++boundaryPos;
            }
            else {
                System.out.println("Parsing boundary failed - " + p);
                p--;

                if (headers == null) {
                    {cs = 1; _goto_targ = 2; if (true) continue _goto;}
                }
                else {
                    {cs = 13; _goto_targ = 2; if (true) continue _goto;}
                }
            }
        }
	break;
	case 2:
// line 37 "src/rl/picard/http/MultipartParser.rl"
	{
            System.out.println("Starting headers: " + p);
            headers = callback.blankHeaders();
        }
	break;
	case 3:
// line 42 "src/rl/picard/http/MultipartParser.rl"
	{
            System.out.println("Ending headers: " + p);
            bodyStart = p;
        }
	break;
	case 4:
// line 71 "src/rl/picard/http/MultipartParser.rl"
	{
            System.out.println("!!! BAM: " + bodyStart + " - " + bodyEnd);
            ByteBuffer chunk = buf.asReadOnlyBuffer();

            chunk.position(bodyStart);
            chunk.limit(bodyEnd);

            callback.part(headers, chunk);
        }
	break;
	case 5:
// line 81 "src/rl/picard/http/MultipartParser.rl"
	{
            System.out.println("~~~~ ALL DONE");
            callback.done();
            {cs = 55; _goto_targ = 2; if (true) continue _goto;}
        }
	break;
	case 6:
// line 87 "src/rl/picard/http/MultipartParser.rl"
	{
            if (true) {
                throw new HttpParserException("Something went wrong");
            }
        }
	break;
// line 494 "src/jvm/picard/http/MultipartParser.java"
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
	case 6:
// line 87 "src/rl/picard/http/MultipartParser.rl"
	{
            if (true) {
                throw new HttpParserException("Something went wrong");
            }
        }
	break;
// line 523 "src/jvm/picard/http/MultipartParser.java"
		}
	}
	}

case 5:
	}
	break; }
	}

// line 126 "src/rl/picard/http/MultipartParser.rl"
    }
}
