
// line 1 "src/rl/picard/http/HttpParser.rl"
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

    
// line 99 "src/rl/picard/http/HttpParser.rl"


    
// line 55 "src/java/picard/http/HttpParser.java"
private static byte[] init__http_actions_0()
{
	return new byte [] {
	    0,    1,    0,    1,    1,    1,    2,    1,    3,    1,    4,    1,
	    5,    1,    6,    1,    7,    1,    8,    1,    9,    1,   10,    1,
	   11,    1,   12,    1,   13,    1,   14,    1,   15,    1,   16,    1,
	   17,    1,   18,    1,   19,    1,   20,    1,   21,    1,   22,    1,
	   23,    1,   24,    1,   25,    1,   26
	};
}

private static final byte _http_actions[] = init__http_actions_0();


private static short[] init__http_key_offsets_0()
{
	return new short [] {
	    0,    0,   13,   15,   16,   17,   18,   19,   20,   21,   22,   23,
	   24,   25,   26,   27,   28,   29,   31,   34,   36,   39,   40,   42,
	   43,   44,   45,   46,   47,   48,   49,   50,   51,   52,   53,   54,
	   55,   56,   60,   61,   62,   63,   65,   66,   67,   68,   69,   70,
	   71,   72,   73,   74,   75,   76,   77,   78,   79,   80,   81,   82,
	   83,   84,   85,   86,   87,   88,   89,   90,   91,   92,   96,   97,
	   98,   99,  100,  101,  102,  103,  105,  106,  107,  108,  109,  110,
	  111,  112,  113,  114,  115,  116,  117,  118,  119,  120,  121,  122,
	  123,  124,  125,  126,  127,  128,  129,  130,  131,  133,  134,  135,
	  136,  137,  138,  139,  140,  141,  142,  143,  144,  145,  146,  147,
	  148,  149,  150,  151,  152,  153,  154,  155,  156,  157,  158,  159,
	  161,  162,  163,  164,  165,  166
	};
}

private static final short _http_key_offsets[] = init__http_key_offsets_0();


private static char[] init__http_trans_keys_0()
{
	return new char [] {
	   67,   68,   71,   72,   76,   77,   78,   79,   80,   82,   83,   84,
	   85,   72,   79,   69,   67,   75,   79,   85,   84,   32,   47,   32,
	   72,   84,   84,   80,   47,   48,   57,   46,   48,   57,   48,   57,
	   13,   48,   57,   10,   13,   72,   10,   69,   76,   69,   84,   69,
	   69,   84,   69,   65,   68,   79,   67,   75,   69,   75,   79,   83,
	   82,   71,   69,   65,   67,   67,   84,   73,   86,   73,   84,   89,
	   79,   76,   86,   69,   69,   65,   82,   67,   72,   79,   84,   73,
	   70,   89,   80,   84,   73,   79,   78,   83,   65,   79,   82,   85,
	   84,   67,   72,   83,   84,   79,   80,   70,   80,   73,   78,   68,
	   65,   84,   67,   72,   84,   69,   80,   79,   82,   84,   85,   66,
	   83,   67,   82,   73,   66,   69,   82,   65,   67,   69,   78,   76,
	   83,   79,   67,   75,   85,   66,   83,   67,   82,   73,   66,   69,
	  111,  115,  116,   58,   32,  108,  111,   99,   97,  108,  104,  111,
	  115,  116,   13,   78,   80,   78,   69,   67,   84,   89,   67,   68,
	   71,   72,   76,   77,   78,   79,   80,   82,   83,   84,   85,    0
	};
}

private static final char _http_trans_keys[] = init__http_trans_keys_0();


private static byte[] init__http_single_lengths_0()
{
	return new byte [] {
	    0,   13,    2,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    0,    1,    0,    1,    1,    2,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    4,    1,    1,    1,    2,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    4,    1,    1,
	    1,    1,    1,    1,    1,    2,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    2,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    2,
	    1,    1,    1,    1,    1,   13
	};
}

private static final byte _http_single_lengths[] = init__http_single_lengths_0();


private static byte[] init__http_range_lengths_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    1,    1,    1,    1,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0
	};
}

private static final byte _http_range_lengths[] = init__http_range_lengths_0();


private static short[] init__http_index_offsets_0()
{
	return new short [] {
	    0,    0,   14,   17,   19,   21,   23,   25,   27,   29,   31,   33,
	   35,   37,   39,   41,   43,   45,   47,   50,   52,   55,   57,   60,
	   62,   64,   66,   68,   70,   72,   74,   76,   78,   80,   82,   84,
	   86,   88,   93,   95,   97,   99,  102,  104,  106,  108,  110,  112,
	  114,  116,  118,  120,  122,  124,  126,  128,  130,  132,  134,  136,
	  138,  140,  142,  144,  146,  148,  150,  152,  154,  156,  161,  163,
	  165,  167,  169,  171,  173,  175,  178,  180,  182,  184,  186,  188,
	  190,  192,  194,  196,  198,  200,  202,  204,  206,  208,  210,  212,
	  214,  216,  218,  220,  222,  224,  226,  228,  230,  233,  235,  237,
	  239,  241,  243,  245,  247,  249,  251,  253,  255,  257,  259,  261,
	  263,  265,  267,  269,  271,  273,  275,  277,  279,  281,  283,  285,
	  288,  290,  292,  294,  296,  298
	};
}

private static final short _http_index_offsets[] = init__http_index_offsets_0();


private static short[] init__http_trans_targs_0()
{
	return new short [] {
	    2,   24,   29,   31,   34,   37,   58,   63,   69,   86,   91,   99,
	  103,    0,    3,  131,    0,    4,    0,    5,    0,    6,    0,    7,
	    0,    8,    0,    9,    0,   10,    0,   11,    0,   12,    0,   13,
	    0,   14,    0,   15,    0,   16,    0,   17,    0,   18,    0,   19,
	   18,    0,   20,    0,   21,   20,    0,   22,    0,   23,  116,    0,
	  137,    0,   25,    0,   26,    0,   27,    0,   28,    0,    9,    0,
	   30,    0,    9,    0,   32,    0,   33,    0,    9,    0,   35,    0,
	   36,    0,    9,    0,   38,   41,   51,   53,    0,   39,    0,   40,
	    0,    9,    0,   42,   49,    0,   43,    0,   44,    0,   45,    0,
	   46,    0,   47,    0,   48,    0,    9,    0,   50,    0,    9,    0,
	   52,    0,    9,    0,   54,    0,   55,    0,   56,    0,   57,    0,
	    9,    0,   59,    0,   60,    0,   61,    0,   62,    0,    9,    0,
	   64,    0,   65,    0,   66,    0,   67,    0,   68,    0,    9,    0,
	   70,   73,   75,   85,    0,   71,    0,   72,    0,    9,    0,   74,
	    0,    9,    0,   76,    0,   77,    0,   78,   81,    0,   79,    0,
	   80,    0,    9,    0,   82,    0,   83,    0,   84,    0,    9,    0,
	    9,    0,   87,    0,   88,    0,   89,    0,   90,    0,    9,    0,
	   92,    0,   93,    0,   94,    0,   95,    0,   96,    0,   97,    0,
	   98,    0,    9,    0,  100,    0,  101,    0,  102,    0,    9,    0,
	  104,    0,  105,  108,    0,  106,    0,  107,    0,    9,    0,  109,
	    0,  110,    0,  111,    0,  112,    0,  113,    0,  114,    0,  115,
	    0,    9,    0,  117,    0,  118,    0,  119,    0,  120,    0,  121,
	    0,  122,    0,  123,    0,  124,    0,  125,    0,  126,    0,  127,
	    0,  128,    0,  129,    0,  130,    0,   21,    0,  132,  136,    0,
	  133,    0,  134,    0,  135,    0,    9,    0,    9,    0,    2,   24,
	   29,   31,   34,   37,   58,   63,   69,   86,   91,   99,  103,    0,
	    0
	};
}

private static final short _http_trans_targs[] = init__http_trans_targs_0();


private static byte[] init__http_trans_actions_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,   35,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,   49,    0,    0,
	   49,    0,   51,    0,    0,   51,    0,    0,    0,    0,    0,    0,
	   53,    0,    0,    0,    0,    0,    0,    0,    0,    0,    9,    0,
	    0,    0,    3,    0,    0,    0,    0,    0,    1,    0,    0,    0,
	    0,    0,   19,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,   37,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,   33,    0,    0,    0,   21,    0,
	    0,    0,   23,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	   39,    0,    0,    0,    0,    0,    0,    0,    0,    0,   41,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,   13,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,   47,    0,    0,
	    0,    5,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,   25,    0,    0,    0,    0,    0,    0,    0,   27,    0,
	    7,    0,    0,    0,    0,    0,    0,    0,    0,    0,   31,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,   43,    0,    0,    0,    0,    0,    0,    0,   15,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,   29,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,   45,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,   11,    0,   17,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0
	};
}

private static final byte _http_trans_actions[] = init__http_trans_actions_0();


static final int http_start = 1;
static final int http_first_final = 137;
static final int http_error = 0;

static final int http_en_main = 1;


// line 102 "src/rl/picard/http/HttpParser.rl"

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
        
// line 286 "src/java/picard/http/HttpParser.java"
	{
	cs = http_start;
	}

// line 130 "src/rl/picard/http/HttpParser.rl"
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

        
// line 156 "src/rl/picard/http/HttpParser.rl"
        
// line 320 "src/java/picard/http/HttpParser.java"
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
	_keys = _http_key_offsets[cs];
	_trans = _http_index_offsets[cs];
	_klen = _http_single_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + _klen - 1;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + ((_upper-_lower) >> 1);
			if ( ( buf.get(p)) < _http_trans_keys[_mid] )
				_upper = _mid - 1;
			else if ( ( buf.get(p)) > _http_trans_keys[_mid] )
				_lower = _mid + 1;
			else {
				_trans += (_mid - _keys);
				break _match;
			}
		}
		_keys += _klen;
		_trans += _klen;
	}

	_klen = _http_range_lengths[cs];
	if ( _klen > 0 ) {
		int _lower = _keys;
		int _mid;
		int _upper = _keys + (_klen<<1) - 2;
		while (true) {
			if ( _upper < _lower )
				break;

			_mid = _lower + (((_upper-_lower) >> 1) & ~1);
			if ( ( buf.get(p)) < _http_trans_keys[_mid] )
				_upper = _mid - 2;
			else if ( ( buf.get(p)) > _http_trans_keys[_mid+1] )
				_lower = _mid + 2;
			else {
				_trans += ((_mid - _keys)>>1);
				break _match;
			}
		}
		_trans += _klen;
	}
	} while (false);

	cs = _http_trans_targs[_trans];

	if ( _http_trans_actions[_trans] != 0 ) {
		_acts = _http_trans_actions[_trans];
		_nacts = (int) _http_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _http_actions[_acts++] )
			{
	case 0:
// line 50 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.HEAD;        }
	break;
	case 1:
// line 51 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.GET;         }
	break;
	case 2:
// line 52 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.POST;        }
	break;
	case 3:
// line 53 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PUT;         }
	break;
	case 4:
// line 54 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.DELETE;      }
	break;
	case 5:
// line 55 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.CONNECT;     }
	break;
	case 6:
// line 56 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.OPTIONS;     }
	break;
	case 7:
// line 57 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.TRACE;       }
	break;
	case 8:
// line 58 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.COPY;        }
	break;
	case 9:
// line 59 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.LOCK;        }
	break;
	case 10:
// line 60 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MKCOL;       }
	break;
	case 11:
// line 61 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MOVE;        }
	break;
	case 12:
// line 62 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PROPFIND;    }
	break;
	case 13:
// line 63 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PROPPATCH;   }
	break;
	case 14:
// line 64 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.UNLOCK;      }
	break;
	case 15:
// line 65 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.REPORT;      }
	break;
	case 16:
// line 66 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MKACTIVITY;  }
	break;
	case 17:
// line 67 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.CHECKOUT;    }
	break;
	case 18:
// line 68 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MERGE;       }
	break;
	case 19:
// line 69 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MSEARCH;     }
	break;
	case 20:
// line 70 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.NOTIFY;      }
	break;
	case 21:
// line 71 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.SUBSCRIBE;   }
	break;
	case 22:
// line 72 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.UNSUBSCRIBE; }
	break;
	case 23:
// line 73 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PATCH;       }
	break;
	case 24:
// line 75 "src/rl/picard/http/HttpParser.rl"
	{
            httpMajor *= 10;
            httpMajor += ( buf.get(p)) - '0';

            if (httpMajor > 999) {
                // TODO: handle error
            }
        }
	break;
	case 25:
// line 84 "src/rl/picard/http/HttpParser.rl"
	{
            httpMinor *= 10;
            httpMinor += ( buf.get(p)) - '0';

            if (httpMinor > 999) {
                // TODO: handle error
            }
        }
	break;
	case 26:
// line 93 "src/rl/picard/http/HttpParser.rl"
	{
            callback.request(this);
        }
	break;
// line 523 "src/java/picard/http/HttpParser.java"
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
case 5:
	}
	break; }
	}

// line 157 "src/rl/picard/http/HttpParser.rl"

        return p;
    }
}
