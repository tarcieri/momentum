
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

    private class Mark {
        public final ByteBuffer buf;
        public final int offset;

        public Mark(ByteBuffer buf, int offset) {
            this.buf    = buf;
            this.offset = offset;
        }
    }

    private class Node {
        public final Node next;
        public final ByteBuffer buf;

        public Node(Node next, ByteBuffer buf) {
            this.next = next;
            this.buf  = buf;
        }
    }

    
// line 166 "src/rl/picard/http/HttpParser.rl"


    public static final int MAX_HEADER_SIZE = 100 * 1024;
    public static final int PARSING_HEAD    = 1 << 0;
    public static final int IDENTITY_BODY   = 1 << 1;
    public static final int CHUNKED_BODY    = 1 << 2;
    public static final int KEEP_ALIVE      = 1 << 3;
    public static final int UPGRADE         = 1 << 4;
    public static final int ERROR           = 1 << 5;


    
// line 84 "src/java/picard/http/HttpParser.java"
private static byte[] init__http_actions_0()
{
	return new byte [] {
	    0,    1,    0,    1,    1,    1,    2,    1,    3,    1,    4,    1,
	    5,    1,    6,    1,    7,    1,    8,    1,    9,    1,   10,    1,
	   11,    1,   12,    1,   13,    1,   14,    1,   15,    1,   16,    1,
	   17,    1,   18,    1,   19,    1,   20,    1,   21,    1,   22,    1,
	   23,    1,   24,    1,   25,    1,   26,    1,   27,    1,   28,    1,
	   29,    1,   30,    1,   31,    1,   32,    1,   33,    1,   34,    1,
	   35,    2,   28,   29,    2,   32,   33
	};
}

private static final byte _http_actions[] = init__http_actions_0();


private static short[] init__http_key_offsets_0()
{
	return new short [] {
	    0,    0,   13,   15,   16,   17,   18,   19,   20,   21,   22,   36,
	   52,   53,   54,   55,   56,   57,   59,   62,   64,   67,   68,   83,
	   84,   85,   86,   87,   88,   89,   90,   91,   92,   93,   94,   95,
	   96,   97,  101,  102,  103,  104,  106,  107,  108,  109,  110,  111,
	  112,  113,  114,  115,  116,  117,  118,  119,  120,  121,  122,  123,
	  124,  125,  126,  127,  128,  129,  130,  131,  132,  133,  137,  138,
	  139,  140,  141,  142,  143,  144,  146,  147,  148,  149,  150,  151,
	  152,  153,  154,  155,  156,  157,  158,  159,  160,  161,  162,  163,
	  164,  165,  166,  167,  168,  169,  170,  171,  172,  174,  175,  176,
	  177,  178,  179,  180,  181,  182,  183,  184,  185,  200,  205,  210,
	  212,  222,  229,  235,  241,  248,  254,  260,  270,  277,  283,  289,
	  298,  307,  314,  320,  326,  337,  351,  358,  364,  370,  391,  401,
	  410,  417,  423,  429,  431,  432,  433,  434,  435,  436
	};
}

private static final short _http_key_offsets[] = init__http_key_offsets_0();


private static char[] init__http_trans_keys_0()
{
	return new char [] {
	   67,   68,   71,   72,   76,   77,   78,   79,   80,   82,   83,   84,
	   85,   72,   79,   69,   67,   75,   79,   85,   84,   32,   33,   37,
	   47,   59,   61,   64,   95,  126,   36,   57,   65,   90,   97,  122,
	   32,   33,   35,   37,   47,   59,   61,   63,   95,  126,   36,   57,
	   64,   90,   97,  122,   72,   84,   84,   80,   47,   48,   57,   46,
	   48,   57,   48,   57,   13,   48,   57,   10,   13,   34,   44,   47,
	  123,  125,  127,    0,   32,   40,   41,   58,   64,   91,   93,   10,
	   69,   76,   69,   84,   69,   69,   84,   69,   65,   68,   79,   67,
	   75,   69,   75,   79,   83,   82,   71,   69,   65,   67,   67,   84,
	   73,   86,   73,   84,   89,   79,   76,   86,   69,   69,   65,   82,
	   67,   72,   79,   84,   73,   70,   89,   80,   84,   73,   79,   78,
	   83,   65,   79,   82,   85,   84,   67,   72,   83,   84,   79,   80,
	   70,   80,   73,   78,   68,   65,   84,   67,   72,   84,   69,   80,
	   79,   82,   84,   85,   66,   83,   67,   82,   73,   66,   69,   82,
	   65,   67,   69,   78,   76,   83,   79,   67,   75,   85,   66,   83,
	   67,   82,   73,   66,   69,   34,   44,   47,   58,  123,  125,  127,
	    0,   32,   40,   41,   59,   64,   91,   93,   13,   32,  127,    0,
	   31,   13,   32,  127,    0,   31,   13,   32,   32,   37,   95,  126,
	   33,   34,   36,   90,   97,  122,  117,   48,   57,   65,   70,   97,
	  102,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,   97,
	  102,  117,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,
	   97,  102,   48,   57,   65,   70,   97,  102,   32,   35,   37,   63,
	   95,  126,   33,   90,   97,  122,  117,   48,   57,   65,   70,   97,
	  102,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,   97,
	  102,   32,   35,   37,   95,  126,   33,   90,   97,  122,   32,   35,
	   37,   95,  126,   33,   90,   97,  122,  117,   48,   57,   65,   70,
	   97,  102,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,
	   97,  102,   32,   35,   37,   47,   63,   95,  126,   33,   90,   97,
	  122,   32,   34,   35,   37,   47,   60,   62,   63,   95,  126,   33,
	   90,   97,  122,  117,   48,   57,   65,   70,   97,  102,   48,   57,
	   65,   70,   97,  102,   48,   57,   65,   70,   97,  102,   32,   33,
	   35,   37,   43,   47,   58,   59,   61,   63,   64,   95,  126,   36,
	   44,   45,   57,   65,   90,   97,  122,   37,   47,   95,  126,   33,
	   34,   36,   90,   97,  122,   32,   35,   37,   95,  126,   33,   90,
	   97,  122,  117,   48,   57,   65,   70,   97,  102,   48,   57,   65,
	   70,   97,  102,   48,   57,   65,   70,   97,  102,   78,   80,   78,
	   69,   67,   84,   89,   67,   68,   71,   72,   76,   77,   78,   79,
	   80,   82,   83,   84,   85,    0
	};
}

private static final char _http_trans_keys[] = init__http_trans_keys_0();


private static byte[] init__http_single_lengths_0()
{
	return new byte [] {
	    0,   13,    2,    1,    1,    1,    1,    1,    1,    1,    8,   10,
	    1,    1,    1,    1,    1,    0,    1,    0,    1,    1,    7,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    4,    1,    1,    1,    2,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    4,    1,    1,
	    1,    1,    1,    1,    1,    2,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    2,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    7,    3,    3,    2,
	    4,    1,    0,    0,    1,    0,    0,    6,    1,    0,    0,    5,
	    5,    1,    0,    0,    7,   10,    1,    0,    0,   13,    4,    5,
	    1,    0,    0,    2,    1,    1,    1,    1,    1,   13
	};
}

private static final byte _http_single_lengths[] = init__http_single_lengths_0();


private static byte[] init__http_range_lengths_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    3,    3,
	    0,    0,    0,    0,    0,    1,    1,    1,    1,    0,    4,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    4,    1,    1,    0,
	    3,    3,    3,    3,    3,    3,    3,    2,    3,    3,    3,    2,
	    2,    3,    3,    3,    2,    2,    3,    3,    3,    4,    3,    2,
	    3,    3,    3,    0,    0,    0,    0,    0,    0,    0
	};
}

private static final byte _http_range_lengths[] = init__http_range_lengths_0();


private static short[] init__http_index_offsets_0()
{
	return new short [] {
	    0,    0,   14,   17,   19,   21,   23,   25,   27,   29,   31,   43,
	   57,   59,   61,   63,   65,   67,   69,   72,   74,   77,   79,   91,
	   93,   95,   97,   99,  101,  103,  105,  107,  109,  111,  113,  115,
	  117,  119,  124,  126,  128,  130,  133,  135,  137,  139,  141,  143,
	  145,  147,  149,  151,  153,  155,  157,  159,  161,  163,  165,  167,
	  169,  171,  173,  175,  177,  179,  181,  183,  185,  187,  192,  194,
	  196,  198,  200,  202,  204,  206,  209,  211,  213,  215,  217,  219,
	  221,  223,  225,  227,  229,  231,  233,  235,  237,  239,  241,  243,
	  245,  247,  249,  251,  253,  255,  257,  259,  261,  264,  266,  268,
	  270,  272,  274,  276,  278,  280,  282,  284,  286,  298,  303,  308,
	  311,  319,  324,  328,  332,  337,  341,  345,  354,  359,  363,  367,
	  375,  383,  388,  392,  396,  406,  419,  424,  428,  432,  450,  458,
	  466,  471,  475,  479,  482,  484,  486,  488,  490,  492
	};
}

private static final short _http_index_offsets[] = init__http_index_offsets_0();


private static short[] init__http_trans_targs_0()
{
	return new short [] {
	    2,   24,   29,   31,   34,   37,   58,   63,   69,   86,   91,   99,
	  103,    0,    3,  147,    0,    4,    0,    5,    0,    6,    0,    7,
	    0,    8,    0,    9,    0,   10,    0,   11,  124,  136,   11,   11,
	   11,   11,   11,   11,  141,  141,    0,   12,   11,  120,  124,  127,
	   11,   11,  131,   11,   11,   11,   11,   11,    0,   13,    0,   14,
	    0,   15,    0,   16,    0,   17,    0,   18,    0,   19,   18,    0,
	   20,    0,   21,   20,    0,   22,    0,   23,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,  116,  153,    0,   25,    0,   26,
	    0,   27,    0,   28,    0,    9,    0,   30,    0,    9,    0,   32,
	    0,   33,    0,    9,    0,   35,    0,   36,    0,    9,    0,   38,
	   41,   51,   53,    0,   39,    0,   40,    0,    9,    0,   42,   49,
	    0,   43,    0,   44,    0,   45,    0,   46,    0,   47,    0,   48,
	    0,    9,    0,   50,    0,    9,    0,   52,    0,    9,    0,   54,
	    0,   55,    0,   56,    0,   57,    0,    9,    0,   59,    0,   60,
	    0,   61,    0,   62,    0,    9,    0,   64,    0,   65,    0,   66,
	    0,   67,    0,   68,    0,    9,    0,   70,   73,   75,   85,    0,
	   71,    0,   72,    0,    9,    0,   74,    0,    9,    0,   76,    0,
	   77,    0,   78,   81,    0,   79,    0,   80,    0,    9,    0,   82,
	    0,   83,    0,   84,    0,    9,    0,    9,    0,   87,    0,   88,
	    0,   89,    0,   90,    0,    9,    0,   92,    0,   93,    0,   94,
	    0,   95,    0,   96,    0,   97,    0,   98,    0,    9,    0,  100,
	    0,  101,    0,  102,    0,    9,    0,  104,    0,  105,  108,    0,
	  106,    0,  107,    0,    9,    0,  109,    0,  110,    0,  111,    0,
	  112,    0,  113,    0,  114,    0,  115,    0,    9,    0,    0,    0,
	    0,  117,    0,    0,    0,    0,    0,    0,    0,  116,   21,  117,
	    0,    0,  118,   21,  119,    0,    0,  118,   21,  119,    0,   12,
	  121,  120,  120,  120,  120,  120,    0,  123,  122,  122,  122,    0,
	  120,  120,  120,    0,  122,  122,  122,    0,  126,  125,  125,  125,
	    0,   11,   11,   11,    0,  125,  125,  125,    0,   12,  120,  128,
	  131,  127,  127,  127,  127,    0,  130,  129,  129,  129,    0,  127,
	  127,  127,    0,  129,  129,  129,    0,   12,  120,  133,  132,  132,
	  132,  132,    0,   12,  120,  133,  132,  132,  132,  132,    0,  135,
	  134,  134,  134,    0,  132,  132,  132,    0,  134,  134,  134,    0,
	   12,  120,  128,  137,  131,  127,  127,  127,  127,    0,   12,  127,
	  120,  138,  127,  127,  127,  131,  137,  137,  137,  137,    0,  140,
	  139,  139,  139,    0,  137,  137,  137,    0,  139,  139,  139,    0,
	   12,   11,  120,  124,  141,  127,  142,   11,   11,  131,   11,   11,
	   11,   11,  141,  141,  141,    0,  144,  136,  143,  143,  143,  143,
	  143,    0,   12,  120,  144,  143,  143,  143,  143,    0,  146,  145,
	  145,  145,    0,  143,  143,  143,    0,  145,  145,  145,    0,  148,
	  152,    0,  149,    0,  150,    0,  151,    0,    9,    0,    9,    0,
	    2,   24,   29,   31,   34,   37,   58,   63,   69,   86,   91,   99,
	  103,    0,    0
	};
}

private static final short _http_trans_targs[] = init__http_trans_targs_0();


private static byte[] init__http_trans_actions_0()
{
	return new byte [] {
	   69,   69,   69,   69,   69,   69,   69,   69,   69,   69,   69,   69,
	   69,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,   35,    0,    0,    0,    0,    0,   53,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,   53,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,   49,    0,    0,   49,    0,
	   51,    0,    0,   51,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,   61,   71,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    9,    0,    0,    0,    3,    0,    0,
	    0,    0,    0,    1,    0,    0,    0,    0,    0,   19,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,   37,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,   33,    0,    0,    0,   21,    0,    0,    0,   23,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,   39,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,   41,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,   13,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,   47,    0,    0,    0,    5,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,   25,    0,    0,
	    0,    0,    0,    0,    0,   27,    0,    7,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,   31,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,   43,    0,    0,
	    0,    0,    0,    0,    0,   15,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,   29,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,   45,    0,    0,    0,
	    0,   63,    0,    0,    0,    0,    0,    0,    0,    0,   76,    0,
	    0,    0,   65,   67,   67,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,   55,   55,    0,
	   55,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,   73,   73,   57,   57,   57,
	   57,   57,    0,   59,   59,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	   55,   55,    0,    0,   55,    0,    0,    0,    0,    0,   55,    0,
	   55,    0,   53,    0,    0,   55,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,   53,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,   53,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,   11,    0,   17,    0,
	   69,   69,   69,   69,   69,   69,   69,   69,   69,   69,   69,   69,
	   69,    0,    0
	};
}

private static final byte _http_trans_actions[] = init__http_trans_actions_0();


static final int http_start = 1;
static final int http_first_final = 153;
static final int http_error = 0;

static final int http_en_main = 1;


// line 178 "src/rl/picard/http/HttpParser.rl"

    // Variable used by ragel to represent the current state of the
    // parser. This must be an integer and it should persist across
    // invocations of the machine when the data is broken into blocks
    // that are processed independently. This variable may be modified
    // from outside the execution loop, but not from within.
    private int cs;

    // Stores some miscellaneous parser state such as whether or not
    // the body is chunked or not, whether or not the connection is
    // keep alive or upgraded, etc...
    private int flags;

    // When starting to parse an HTTP message head, an object is
    // requested from the callback. This object should be the
    // structure that contains HTTP headers for the message being
    // processed.
    private Object headers;

    // The parser saves off all ByteBuffers that traverse an HTTP
    // message's head. The buffers are stored in a stack. Whenever the
    // parser crosses a point of interest (the start of the request
    // URI, header name, header value, etc...), a mark will be created
    // and saved off that points to the buffer in question and an
    // offset in that buffer. When the end of the URI, header,
    // etc... is reached, the stack is walked back until the original
    // mark and the data is copied into a String and saved off.
    private Node head;

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

    // Tracks the HTTP method of the currently parsed request. If the
    // HTTP message being currently parsed is a response, then this
    // will be nil.
    private HttpMethod method;

    // Tracks the various message information
    private String pathInfo;
    private Mark   pathInfoMark;
    private String queryString;
    private Mark   queryStringMark;
    private String headerName;
    private Mark   headerNameMark;
    private Mark   headerValueMark;

    // The object that gets called on various parse events.
    private HttpParserCallback callback;

    public HttpParser(HttpParserCallback callback) {
        
// line 411 "src/java/picard/http/HttpParser.java"
	{
	cs = http_start;
	}

// line 241 "src/rl/picard/http/HttpParser.rl"
        this.callback = callback;
    }

    public boolean isParsingHead() {
        return ( flags & PARSING_HEAD ) == PARSING_HEAD;
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

    public boolean isUpgrade() {
        return ( flags & UPGRADE ) == UPGRADE;
    }

    public boolean isError() {
        return ( flags & ERROR ) == ERROR;
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

    public String getPathInfo() {
        if (pathInfo == null) {
            return "";
        }

        return pathInfo;
    }

    public String getQueryString() {
        if (queryString == null) {
            return "";
        }

        return queryString;
    }

    public int execute(String str) {
        ByteBuffer buf = ByteBuffer.wrap(str.getBytes());
        return execute(buf);
    }

    public int execute(ByteBuffer buf) {
        // First make sure that the parser isn't in an error state
        if (isError()) {
            throw new HttpParserException("The parser is in an error state.");
        }

        // Setup ragel variables
        int p  = 0;
        int pe = buf.remaining();

        
// line 312 "src/rl/picard/http/HttpParser.rl"
        
// line 490 "src/java/picard/http/HttpParser.java"
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
// line 70 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.HEAD;        }
	break;
	case 1:
// line 71 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.GET;         }
	break;
	case 2:
// line 72 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.POST;        }
	break;
	case 3:
// line 73 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PUT;         }
	break;
	case 4:
// line 74 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.DELETE;      }
	break;
	case 5:
// line 75 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.CONNECT;     }
	break;
	case 6:
// line 76 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.OPTIONS;     }
	break;
	case 7:
// line 77 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.TRACE;       }
	break;
	case 8:
// line 78 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.COPY;        }
	break;
	case 9:
// line 79 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.LOCK;        }
	break;
	case 10:
// line 80 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MKCOL;       }
	break;
	case 11:
// line 81 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MOVE;        }
	break;
	case 12:
// line 82 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PROPFIND;    }
	break;
	case 13:
// line 83 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PROPPATCH;   }
	break;
	case 14:
// line 84 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.UNLOCK;      }
	break;
	case 15:
// line 85 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.REPORT;      }
	break;
	case 16:
// line 86 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MKACTIVITY;  }
	break;
	case 17:
// line 87 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.CHECKOUT;    }
	break;
	case 18:
// line 88 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MERGE;       }
	break;
	case 19:
// line 89 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MSEARCH;     }
	break;
	case 20:
// line 90 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.NOTIFY;      }
	break;
	case 21:
// line 91 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.SUBSCRIBE;   }
	break;
	case 22:
// line 92 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.UNSUBSCRIBE; }
	break;
	case 23:
// line 93 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PATCH;       }
	break;
	case 24:
// line 95 "src/rl/picard/http/HttpParser.rl"
	{
            httpMajor *= 10;
            httpMajor += ( buf.get(p)) - '0';

            if (httpMajor > 999) {
                flags |= ERROR;
                throw new HttpParserException("The HTTP major version is invalid.");
            }
        }
	break;
	case 25:
// line 105 "src/rl/picard/http/HttpParser.rl"
	{
            httpMinor *= 10;
            httpMinor += ( buf.get(p)) - '0';

            if (httpMinor > 999) {
                flags |= ERROR;
                throw new HttpParserException("The HTTP minor version is invalid.");
            }
        }
	break;
	case 26:
// line 115 "src/rl/picard/http/HttpParser.rl"
	{
            pathInfoMark = new Mark(buf, p);
        }
	break;
	case 27:
// line 119 "src/rl/picard/http/HttpParser.rl"
	{
            pathInfo = extract(pathInfoMark, buf, p);
        }
	break;
	case 28:
// line 123 "src/rl/picard/http/HttpParser.rl"
	{
            queryStringMark = new Mark(buf, p);
        }
	break;
	case 29:
// line 127 "src/rl/picard/http/HttpParser.rl"
	{
            queryString = extract(queryStringMark, buf, p);
        }
	break;
	case 30:
// line 131 "src/rl/picard/http/HttpParser.rl"
	{
            headerNameMark = new Mark(buf, p);
        }
	break;
	case 31:
// line 135 "src/rl/picard/http/HttpParser.rl"
	{
            headerName = extract(headerNameMark, buf, p);
        }
	break;
	case 32:
// line 139 "src/rl/picard/http/HttpParser.rl"
	{
            headerValueMark = new Mark(buf, p);
        }
	break;
	case 33:
// line 143 "src/rl/picard/http/HttpParser.rl"
	{
            String headerValue = extract(headerValueMark, buf, p);

            callback.header(headers, headerName, headerValue);
        }
	break;
	case 34:
// line 149 "src/rl/picard/http/HttpParser.rl"
	{
            flags  |= PARSING_HEAD;
            headers = callback.blankHeaders();
        }
	break;
	case 35:
// line 154 "src/rl/picard/http/HttpParser.rl"
	{
            // Not parsing the HTTP message head anymore
            flags ^= PARSING_HEAD;

            callback.request(this, headers);

            // Unset references to allow the GC to reclaim the memory
            resetHeadState();
        }
	break;
// line 758 "src/java/picard/http/HttpParser.java"
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

// line 313 "src/rl/picard/http/HttpParser.rl"

        if (isParsingHead()) {
            pushBuffer(buf);
        }

        return p;
    }

    private void reset() {
        flags = 0;
        resetHeadState();
    }

    private void resetHeadState() {
        head            = null;
        headers         = null;
        method          = null;
        pathInfo        = null;
        pathInfoMark    = null;
        queryString     = null;
        queryStringMark = null;
        headerName      = null;
        headerNameMark  = null;
        headerValueMark = null;
    }

    private void pushBuffer(ByteBuffer buf) {
        head = new Node(head, buf);
    }

    // Takes a starting mark (buffer, offset) and an end buffer +
    // offset and copies the bytes to a string and returns it. This is
    // a fairly simple operation when the start and end points are in
    // the same buffer, but when they are not, the buffer stack is
    // traversed, copying all data in between, until the start buffer
    // is found.
    private String extract(Mark from, ByteBuffer toBuf, int toOffset) {
        int size    = extractSize(from, toBuf, toOffset);
        int pos     = 0;
        Node next   = head;
        byte [] buf = new byte[size];

        if (from.buf == toBuf) {
            copy(buf, pos, from.buf, from.offset, toOffset);
        }
        else {
            pos += copy(buf, pos, toBuf, 0, toOffset);

            while (next.buf != from.buf) {
                pos += copy(buf, pos, next.buf, 0, next.buf.limit());
                next = next.next;
            }

            copy(buf, pos, from.buf, from.offset, from.buf.limit());
        }

        return new String(buf);
    }

    private int extractSize(Mark from, ByteBuffer toBuf, int toOffset) {
        int retval = toOffset - from.offset;
        Node next  = head;

        while (from.buf != toBuf) {
            toBuf   = next.buf;
            next    = next.next;
            retval += toBuf.limit();
        }

        return retval;
    }

    private int copy(byte [] dst, int pos, ByteBuffer src, int from, int to) {
        int oldPos = src.position();
        int oldLim = src.limit();
        int length = to - from;

        src.position(from);
        src.limit(to);
        src.get(dst, dst.length - (pos + length), length);

        src.position(oldPos);
        src.limit(oldLim);

        return length;
    }
}
