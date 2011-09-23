
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

    public static final String HDR_CONNECTION        = "connection";
    public static final String HDR_CONTENT_LENGTH    = "content-length";
    public static final String HDR_TRANSFER_ENCODING = "transfer-encoding";
    public static final String HDR_CHUNKED           = "chunked";

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

    
// line 230 "src/rl/picard/http/HttpParser.rl"


    public static final long ALMOST_MAX_LONG = Long.MAX_VALUE / 10 - 10;
    public static final int  MAX_HEADER_SIZE = 100 * 1024;
    public static final int  PARSING_HEAD    = 1 << 0;
    public static final int  IDENTITY_BODY   = 1 << 1;
    public static final int  CHUNKED_BODY    = 1 << 2;
    public static final int  KEEP_ALIVE      = 1 << 3;
    public static final int  UPGRADE         = 1 << 4;
    public static final int  ERROR           = 1 << 5;


    
// line 90 "src/java/picard/http/HttpParser.java"
private static byte[] init__http_actions_0()
{
	return new byte [] {
	    0,    1,    0,    1,    1,    1,    2,    1,    3,    1,    4,    1,
	    5,    1,    6,    1,    7,    1,    8,    1,    9,    1,   10,    1,
	   11,    1,   12,    1,   13,    1,   14,    1,   15,    1,   16,    1,
	   17,    1,   18,    1,   19,    1,   20,    1,   21,    1,   22,    1,
	   23,    1,   24,    1,   25,    1,   26,    1,   27,    1,   28,    1,
	   29,    1,   30,    1,   31,    1,   32,    1,   33,    1,   34,    1,
	   36,    1,   37,    1,   38,    2,   28,   29,    2,   30,   36,    2,
	   35,   36
	};
}

private static final byte _http_actions[] = init__http_actions_0();


private static short[] init__http_key_offsets_0()
{
	return new short [] {
	    0,    0,   13,   15,   16,   17,   18,   19,   20,   21,   22,   36,
	   52,   53,   54,   55,   56,   57,   59,   62,   64,   67,   68,   73,
	   74,   75,   76,   77,   78,   79,   80,   81,   82,   83,   84,   85,
	   86,   87,   91,   92,   93,   94,   96,   97,   98,   99,  100,  101,
	  102,  103,  104,  105,  106,  107,  108,  109,  110,  111,  112,  113,
	  114,  115,  116,  117,  118,  119,  120,  121,  122,  123,  127,  128,
	  129,  130,  131,  132,  133,  134,  136,  137,  138,  139,  140,  141,
	  142,  143,  144,  145,  146,  147,  148,  149,  150,  151,  152,  153,
	  154,  155,  156,  157,  158,  159,  160,  161,  162,  164,  165,  166,
	  167,  168,  169,  170,  171,  172,  173,  174,  175,  177,  179,  181,
	  183,  185,  187,  188,  190,  192,  194,  196,  198,  200,  201,  204,
	  208,  210,  212,  214,  216,  218,  220,  222,  224,  225,  227,  229,
	  231,  233,  235,  237,  239,  241,  242,  261,  278,  280,  299,  318,
	  337,  356,  375,  394,  411,  421,  428,  434,  440,  447,  453,  459,
	  469,  476,  482,  488,  497,  506,  513,  519,  525,  536,  550,  557,
	  563,  569,  590,  600,  609,  616,  622,  628,  630,  631,  632,  633,
	  634,  635
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
	   48,   57,   48,   57,   13,   48,   57,   10,   13,   67,   84,   99,
	  116,   10,   69,   76,   69,   84,   69,   69,   84,   69,   65,   68,
	   79,   67,   75,   69,   75,   79,   83,   82,   71,   69,   65,   67,
	   67,   84,   73,   86,   73,   84,   89,   79,   76,   86,   69,   69,
	   65,   82,   67,   72,   79,   84,   73,   70,   89,   80,   84,   73,
	   79,   78,   83,   65,   79,   82,   85,   84,   67,   72,   83,   84,
	   79,   80,   70,   80,   73,   78,   68,   65,   84,   67,   72,   84,
	   69,   80,   79,   82,   84,   85,   66,   83,   67,   82,   73,   66,
	   69,   82,   65,   67,   69,   78,   76,   83,   79,   67,   75,   85,
	   66,   83,   67,   82,   73,   66,   69,   79,  111,   78,  110,   84,
	  116,   69,  101,   78,  110,   84,  116,   45,   76,  108,   69,  101,
	   78,  110,   71,  103,   84,  116,   72,  104,   58,   32,   48,   57,
	   13,   32,   48,   57,   13,   32,   82,  114,   65,   97,   78,  110,
	   83,  115,   70,  102,   69,  101,   82,  114,   45,   69,  101,   78,
	  110,   67,   99,   79,  111,   68,  100,   73,  105,   78,  110,   71,
	  103,   58,   13,   32,   34,   44,   47,   59,   67,   99,  123,  125,
	  127,    0,   31,   40,   41,   58,   64,   91,   93,   13,   32,   34,
	   44,   47,   59,  123,  125,  127,    0,   31,   40,   41,   58,   64,
	   91,   93,   13,   32,   13,   32,   34,   44,   47,   59,   72,  104,
	  123,  125,  127,    0,   31,   40,   41,   58,   64,   91,   93,   13,
	   32,   34,   44,   47,   59,   85,  117,  123,  125,  127,    0,   31,
	   40,   41,   58,   64,   91,   93,   13,   32,   34,   44,   47,   59,
	   78,  110,  123,  125,  127,    0,   31,   40,   41,   58,   64,   91,
	   93,   13,   32,   34,   44,   47,   59,   75,  107,  123,  125,  127,
	    0,   31,   40,   41,   58,   64,   91,   93,   13,   32,   34,   44,
	   47,   59,   69,  101,  123,  125,  127,    0,   31,   40,   41,   58,
	   64,   91,   93,   13,   32,   34,   44,   47,   59,   68,  100,  123,
	  125,  127,    0,   31,   40,   41,   58,   64,   91,   93,   13,   32,
	   34,   44,   47,   59,  123,  125,  127,    0,   31,   40,   41,   58,
	   64,   91,   93,   32,   37,   95,  126,   33,   34,   36,   90,   97,
	  122,  117,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,
	   97,  102,   48,   57,   65,   70,   97,  102,  117,   48,   57,   65,
	   70,   97,  102,   48,   57,   65,   70,   97,  102,   48,   57,   65,
	   70,   97,  102,   32,   35,   37,   63,   95,  126,   33,   90,   97,
	  122,  117,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,
	   97,  102,   48,   57,   65,   70,   97,  102,   32,   35,   37,   95,
	  126,   33,   90,   97,  122,   32,   35,   37,   95,  126,   33,   90,
	   97,  122,  117,   48,   57,   65,   70,   97,  102,   48,   57,   65,
	   70,   97,  102,   48,   57,   65,   70,   97,  102,   32,   35,   37,
	   47,   63,   95,  126,   33,   90,   97,  122,   32,   34,   35,   37,
	   47,   60,   62,   63,   95,  126,   33,   90,   97,  122,  117,   48,
	   57,   65,   70,   97,  102,   48,   57,   65,   70,   97,  102,   48,
	   57,   65,   70,   97,  102,   32,   33,   35,   37,   43,   47,   58,
	   59,   61,   63,   64,   95,  126,   36,   44,   45,   57,   65,   90,
	   97,  122,   37,   47,   95,  126,   33,   34,   36,   90,   97,  122,
	   32,   35,   37,   95,  126,   33,   90,   97,  122,  117,   48,   57,
	   65,   70,   97,  102,   48,   57,   65,   70,   97,  102,   48,   57,
	   65,   70,   97,  102,   78,   80,   78,   69,   67,   84,   89,   67,
	   68,   71,   72,   76,   77,   78,   79,   80,   82,   83,   84,   85,
	    0
	};
}

private static final char _http_trans_keys[] = init__http_trans_keys_0();


private static byte[] init__http_single_lengths_0()
{
	return new byte [] {
	    0,   13,    2,    1,    1,    1,    1,    1,    1,    1,    8,   10,
	    1,    1,    1,    1,    1,    0,    1,    0,    1,    1,    5,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    4,    1,    1,    1,    2,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    4,    1,    1,
	    1,    1,    1,    1,    1,    2,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    2,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    2,    2,    2,    2,
	    2,    2,    1,    2,    2,    2,    2,    2,    2,    1,    1,    2,
	    2,    2,    2,    2,    2,    2,    2,    2,    1,    2,    2,    2,
	    2,    2,    2,    2,    2,    1,   11,    9,    2,   11,   11,   11,
	   11,   11,   11,    9,    4,    1,    0,    0,    1,    0,    0,    6,
	    1,    0,    0,    5,    5,    1,    0,    0,    7,   10,    1,    0,
	    0,   13,    4,    5,    1,    0,    0,    2,    1,    1,    1,    1,
	    1,   13
	};
}

private static final byte _http_single_lengths[] = init__http_single_lengths_0();


private static byte[] init__http_range_lengths_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    3,    3,
	    0,    0,    0,    0,    0,    1,    1,    1,    1,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    1,    1,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    4,    4,    0,    4,    4,    4,
	    4,    4,    4,    4,    3,    3,    3,    3,    3,    3,    3,    2,
	    3,    3,    3,    2,    2,    3,    3,    3,    2,    2,    3,    3,
	    3,    4,    3,    2,    3,    3,    3,    0,    0,    0,    0,    0,
	    0,    0
	};
}

private static final byte _http_range_lengths[] = init__http_range_lengths_0();


private static short[] init__http_index_offsets_0()
{
	return new short [] {
	    0,    0,   14,   17,   19,   21,   23,   25,   27,   29,   31,   43,
	   57,   59,   61,   63,   65,   67,   69,   72,   74,   77,   79,   85,
	   87,   89,   91,   93,   95,   97,   99,  101,  103,  105,  107,  109,
	  111,  113,  118,  120,  122,  124,  127,  129,  131,  133,  135,  137,
	  139,  141,  143,  145,  147,  149,  151,  153,  155,  157,  159,  161,
	  163,  165,  167,  169,  171,  173,  175,  177,  179,  181,  186,  188,
	  190,  192,  194,  196,  198,  200,  203,  205,  207,  209,  211,  213,
	  215,  217,  219,  221,  223,  225,  227,  229,  231,  233,  235,  237,
	  239,  241,  243,  245,  247,  249,  251,  253,  255,  258,  260,  262,
	  264,  266,  268,  270,  272,  274,  276,  278,  280,  283,  286,  289,
	  292,  295,  298,  300,  303,  306,  309,  312,  315,  318,  320,  323,
	  327,  330,  333,  336,  339,  342,  345,  348,  351,  353,  356,  359,
	  362,  365,  368,  371,  374,  377,  379,  395,  409,  412,  428,  444,
	  460,  476,  492,  508,  522,  530,  535,  539,  543,  548,  552,  556,
	  565,  570,  574,  578,  586,  594,  599,  603,  607,  617,  630,  635,
	  639,  643,  661,  669,  677,  682,  686,  690,  693,  695,  697,  699,
	  701,  703
	};
}

private static final short _http_index_offsets[] = init__http_index_offsets_0();


private static short[] init__http_indicies_0()
{
	return new short [] {
	    0,    2,    3,    4,    5,    6,    7,    8,    9,   10,   11,   12,
	   13,    1,   14,   15,    1,   16,    1,   17,    1,   18,    1,   19,
	    1,   20,    1,   21,    1,   22,    1,   23,   24,   25,   23,   23,
	   23,   23,   23,   23,   26,   26,    1,   27,   23,   28,   24,   29,
	   23,   23,   30,   23,   23,   23,   23,   23,    1,   31,    1,   32,
	    1,   33,    1,   34,    1,   35,    1,   36,    1,   37,   36,    1,
	   38,    1,   39,   38,    1,   40,    1,   41,   42,   43,   42,   43,
	    1,   44,    1,   45,    1,   46,    1,   47,    1,   48,    1,   49,
	    1,   50,    1,   51,    1,   52,    1,   53,    1,   54,    1,   55,
	    1,   56,    1,   57,    1,   58,   59,   60,   61,    1,   62,    1,
	   63,    1,   64,    1,   65,   66,    1,   67,    1,   68,    1,   69,
	    1,   70,    1,   71,    1,   72,    1,   73,    1,   74,    1,   75,
	    1,   76,    1,   77,    1,   78,    1,   79,    1,   80,    1,   81,
	    1,   82,    1,   83,    1,   84,    1,   85,    1,   86,    1,   87,
	    1,   88,    1,   89,    1,   90,    1,   91,    1,   92,    1,   93,
	    1,   94,   95,   96,   97,    1,   98,    1,   99,    1,  100,    1,
	  101,    1,  102,    1,  103,    1,  104,    1,  105,  106,    1,  107,
	    1,  108,    1,  109,    1,  110,    1,  111,    1,  112,    1,  113,
	    1,  114,    1,  115,    1,  116,    1,  117,    1,  118,    1,  119,
	    1,  120,    1,  121,    1,  122,    1,  123,    1,  124,    1,  125,
	    1,  126,    1,  127,    1,  128,    1,  129,    1,  130,    1,  131,
	    1,  132,    1,  133,  134,    1,  135,    1,  136,    1,  137,    1,
	  138,    1,  139,    1,  140,    1,  141,    1,  142,    1,  143,    1,
	  144,    1,  145,    1,  146,  146,    1,  147,  147,    1,  148,  148,
	    1,  149,  149,    1,  150,  150,    1,  151,  151,    1,  152,    1,
	  153,  153,    1,  154,  154,    1,  155,  155,    1,  156,  156,    1,
	  157,  157,    1,  158,  158,    1,  159,    1,  159,  161,  160,  162,
	  163,  161,  160,   39,  164,    1,  165,  165,    1,  166,  166,    1,
	  167,  167,    1,  168,  168,    1,  169,  169,    1,  170,  170,    1,
	  171,  171,    1,  172,    1,  173,  173,    1,  174,  174,    1,  175,
	  175,    1,  176,  176,    1,  177,  177,    1,  178,  178,    1,  179,
	  179,    1,  180,  180,    1,  181,    1,  183,  184,  182,  182,  182,
	  186,  187,  187,  182,  182,  182,  182,  182,  182,  182,  185,  188,
	  189,  182,  182,  182,  191,  182,  182,  182,  182,  182,  182,  182,
	  190,  188,  189,  182,  188,  189,  182,  182,  182,  191,  192,  192,
	  182,  182,  182,  182,  182,  182,  182,  190,  188,  189,  182,  182,
	  182,  191,  193,  193,  182,  182,  182,  182,  182,  182,  182,  190,
	  188,  189,  182,  182,  182,  191,  194,  194,  182,  182,  182,  182,
	  182,  182,  182,  190,  188,  189,  182,  182,  182,  191,  195,  195,
	  182,  182,  182,  182,  182,  182,  182,  190,  188,  189,  182,  182,
	  182,  191,  196,  196,  182,  182,  182,  182,  182,  182,  182,  190,
	  188,  189,  182,  182,  182,  191,  197,  197,  182,  182,  182,  182,
	  182,  182,  182,  190,  198,  199,  182,  182,  182,  191,  182,  182,
	  182,  182,  182,  182,  182,  190,   27,  200,   28,   28,   28,   28,
	   28,    1,  202,  201,  201,  201,    1,   28,   28,   28,    1,  201,
	  201,  201,    1,  204,  203,  203,  203,    1,   23,   23,   23,    1,
	  203,  203,  203,    1,  205,  207,  208,  209,  206,  206,  206,  206,
	    1,  211,  210,  210,  210,    1,  206,  206,  206,    1,  210,  210,
	  210,    1,  212,  214,  215,  213,  213,  213,  213,    1,  216,  218,
	  219,  217,  217,  217,  217,    1,  221,  220,  220,  220,    1,  217,
	  217,  217,    1,  220,  220,  220,    1,  205,  207,  208,  222,  209,
	  206,  206,  206,  206,    1,  205,  206,  207,  223,   29,  206,  206,
	  209,  222,  222,  222,  222,    1,  225,  224,  224,  224,    1,  222,
	  222,  222,    1,  224,  224,  224,    1,   27,   23,   28,   24,   26,
	   29,  226,   23,   23,   30,   23,   23,   23,   23,   26,   26,   26,
	    1,  228,   25,  227,  227,  227,  227,  227,    1,   27,   28,  228,
	  227,  227,  227,  227,    1,  230,  229,  229,  229,    1,  227,  227,
	  227,    1,  229,  229,  229,    1,  231,  232,    1,  233,    1,  234,
	    1,  235,    1,  236,    1,  237,    1,    0,    2,    3,    4,    5,
	    6,    7,    8,    9,   10,   11,   12,   13,    1,    0
	};
}

private static final short _http_indicies[] = init__http_indicies_0();


private static short[] init__http_trans_targs_0()
{
	return new short [] {
	    2,    0,   24,   29,   31,   34,   37,   58,   63,   69,   86,   91,
	   99,  103,    3,  187,    4,    5,    6,    7,    8,    9,   10,   11,
	  164,  176,  181,   12,  160,  167,  171,   13,   14,   15,   16,   17,
	   18,   19,   20,   21,   22,   23,  116,  133,  193,   25,   26,   27,
	   28,    9,   30,    9,   32,   33,    9,   35,   36,    9,   38,   41,
	   51,   53,   39,   40,    9,   42,   49,   43,   44,   45,   46,   47,
	   48,    9,   50,    9,   52,    9,   54,   55,   56,   57,    9,   59,
	   60,   61,   62,    9,   64,   65,   66,   67,   68,    9,   70,   73,
	   75,   85,   71,   72,    9,   74,    9,   76,   77,   78,   81,   79,
	   80,    9,   82,   83,   84,    9,    9,   87,   88,   89,   90,    9,
	   92,   93,   94,   95,   96,   97,   98,    9,  100,  101,  102,    9,
	  104,  105,  108,  106,  107,    9,  109,  110,  111,  112,  113,  114,
	  115,    9,  117,  118,  119,  120,  121,  122,  123,  124,  125,  126,
	  127,  128,  129,  130,    0,  131,   21,  132,  132,  134,  135,  136,
	  137,  138,  139,  140,  141,  142,  143,  144,  145,  146,  147,  148,
	  149,  150,    0,   21,  150,  151,  152,  153,   21,  132,  151,  152,
	  154,  155,  156,  157,  158,  159,   21,  132,  161,  162,  163,  165,
	  166,   12,  167,  160,  168,  171,  169,  170,   12,  172,  160,  173,
	   12,  172,  160,  173,  174,  175,  177,  178,  179,  180,  182,  183,
	  184,  185,  186,  188,  192,  189,  190,  191,    9,    9
	};
}

private static final short _http_trans_targs[] = init__http_trans_targs_0();


private static byte[] init__http_trans_actions_0()
{
	return new byte [] {
	   73,    0,   73,   73,   73,   73,   73,   73,   73,   73,   73,   73,
	   73,   73,    0,    0,    0,    0,    0,    0,    0,   35,    0,    0,
	    0,   53,    0,    0,    0,   53,    0,    0,    0,    0,    0,    0,
	   49,    0,   51,    0,    0,    0,    0,    0,   75,    0,    0,    0,
	    0,    9,    0,    3,    0,    0,    1,    0,    0,   19,    0,    0,
	    0,    0,    0,    0,   37,    0,    0,    0,    0,    0,    0,    0,
	    0,   33,    0,   21,    0,   23,    0,    0,    0,    0,   39,    0,
	    0,    0,    0,   41,    0,    0,    0,    0,    0,   13,    0,    0,
	    0,    0,    0,    0,   47,    0,    5,    0,    0,    0,    0,    0,
	    0,   25,    0,    0,    0,   27,    7,    0,    0,    0,    0,   31,
	    0,    0,    0,    0,    0,    0,    0,   43,    0,    0,    0,   15,
	    0,    0,    0,    0,    0,   29,    0,    0,    0,    0,    0,    0,
	    0,   45,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,   65,   63,   67,   67,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,   69,   80,   80,   61,   61,   61,   71,   71,    0,    0,
	    0,    0,    0,    0,    0,    0,   83,   83,    0,    0,    0,    0,
	    0,   55,    0,   55,    0,   55,    0,    0,   77,   57,   77,   57,
	   59,    0,   59,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,   11,   17
	};
}

private static final byte _http_trans_actions[] = init__http_trans_actions_0();


private static byte[] init__http_eof_actions_0()
{
	return new byte [] {
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,   65,   65,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,   69,   69,   69,   69,   69,   69,
	   69,   69,   69,   69,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0
	};
}

private static final byte _http_eof_actions[] = init__http_eof_actions_0();


static final int http_start = 1;
static final int http_first_final = 193;
static final int http_error = 0;

static final int http_en_main = 1;


// line 243 "src/rl/picard/http/HttpParser.rl"

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

    // Track the content length of the HTTP message
    private long contentLength;

    // The object that gets called on various parse events.
    private HttpParserCallback callback;

    public HttpParser(HttpParserCallback callback) {
        
// line 503 "src/java/picard/http/HttpParser.java"
	{
	cs = http_start;
	}

// line 309 "src/rl/picard/http/HttpParser.rl"
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
        int eof = pe + 1;

        
// line 381 "src/rl/picard/http/HttpParser.rl"
        
// line 583 "src/java/picard/http/HttpParser.java"
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

	_trans = _http_indicies[_trans];
	cs = _http_trans_targs[_trans];

	if ( _http_trans_actions[_trans] != 0 ) {
		_acts = _http_trans_actions[_trans];
		_nacts = (int) _http_actions[_acts++];
		while ( _nacts-- > 0 )
	{
			switch ( _http_actions[_acts++] )
			{
	case 0:
// line 75 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.HEAD;        }
	break;
	case 1:
// line 76 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.GET;         }
	break;
	case 2:
// line 77 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.POST;        }
	break;
	case 3:
// line 78 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PUT;         }
	break;
	case 4:
// line 79 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.DELETE;      }
	break;
	case 5:
// line 80 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.CONNECT;     }
	break;
	case 6:
// line 81 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.OPTIONS;     }
	break;
	case 7:
// line 82 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.TRACE;       }
	break;
	case 8:
// line 83 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.COPY;        }
	break;
	case 9:
// line 84 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.LOCK;        }
	break;
	case 10:
// line 85 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MKCOL;       }
	break;
	case 11:
// line 86 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MOVE;        }
	break;
	case 12:
// line 87 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PROPFIND;    }
	break;
	case 13:
// line 88 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PROPPATCH;   }
	break;
	case 14:
// line 89 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.UNLOCK;      }
	break;
	case 15:
// line 90 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.REPORT;      }
	break;
	case 16:
// line 91 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MKACTIVITY;  }
	break;
	case 17:
// line 92 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.CHECKOUT;    }
	break;
	case 18:
// line 93 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MERGE;       }
	break;
	case 19:
// line 94 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MSEARCH;     }
	break;
	case 20:
// line 95 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.NOTIFY;      }
	break;
	case 21:
// line 96 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.SUBSCRIBE;   }
	break;
	case 22:
// line 97 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.UNSUBSCRIBE; }
	break;
	case 23:
// line 98 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PATCH;       }
	break;
	case 24:
// line 100 "src/rl/picard/http/HttpParser.rl"
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
// line 110 "src/rl/picard/http/HttpParser.rl"
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
// line 120 "src/rl/picard/http/HttpParser.rl"
	{
            pathInfoMark = new Mark(buf, p);
        }
	break;
	case 27:
// line 124 "src/rl/picard/http/HttpParser.rl"
	{
            pathInfo = extract(pathInfoMark, buf, p);
        }
	break;
	case 28:
// line 128 "src/rl/picard/http/HttpParser.rl"
	{
            queryStringMark = new Mark(buf, p);
        }
	break;
	case 29:
// line 132 "src/rl/picard/http/HttpParser.rl"
	{
            queryString = extract(queryStringMark, buf, p);
        }
	break;
	case 30:
// line 144 "src/rl/picard/http/HttpParser.rl"
	{
            headerValueMark = new Mark(buf, p);
        }
	break;
	case 31:
// line 154 "src/rl/picard/http/HttpParser.rl"
	{
            if (contentLength >= ALMOST_MAX_LONG) {
                flags |= ERROR;
                throw new HttpParserException("The content-length is WAY too big");
            }

            contentLength *= 10;
            contentLength += ( buf.get(p)) - '0';
        }
	break;
	case 32:
// line 164 "src/rl/picard/http/HttpParser.rl"
	{
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The content-length is in an invalid format");
            }
        }
	break;
	case 33:
// line 173 "src/rl/picard/http/HttpParser.rl"
	{
            if (isChunkedBody()) {
                flags |= ERROR;
                throw new HttpParserException("The message head is invalid");
            }

            flags |= IDENTITY_BODY;

            callback.header(headers, HDR_CONTENT_LENGTH, String.valueOf(contentLength));
        }
	break;
	case 34:
// line 184 "src/rl/picard/http/HttpParser.rl"
	{
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The transfer-encoding is in an invalid format");
            }
        }
	break;
	case 35:
// line 193 "src/rl/picard/http/HttpParser.rl"
	{
            if (isIdentityBody()) {
                flags |= ERROR;
                throw new HttpParserException("The message head is invalid");
            }

            flags |= CHUNKED_BODY;

            headerValueMark = null;
            callback.header(headers, HDR_TRANSFER_ENCODING, HDR_CHUNKED);
        }
	break;
	case 36:
// line 205 "src/rl/picard/http/HttpParser.rl"
	{
            if (headerValueMark != null) {
                String headerValue = extract(headerValueMark, buf, p);
                callback.header(headers, HDR_TRANSFER_ENCODING, headerValue.toLowerCase());
                headerValueMark = null;
            }
        }
	break;
	case 37:
// line 213 "src/rl/picard/http/HttpParser.rl"
	{
            flags  |= PARSING_HEAD;
            headers = callback.blankHeaders();
        }
	break;
	case 38:
// line 218 "src/rl/picard/http/HttpParser.rl"
	{
            // Not parsing the HTTP message head anymore
            flags ^= PARSING_HEAD;

            callback.request(this, headers);

            // Unset references to allow the GC to reclaim the memory
            resetHeadState();
        }
	break;
// line 903 "src/java/picard/http/HttpParser.java"
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
	int __acts = _http_eof_actions[cs];
	int __nacts = (int) _http_actions[__acts++];
	while ( __nacts-- > 0 ) {
		switch ( _http_actions[__acts++] ) {
	case 32:
// line 164 "src/rl/picard/http/HttpParser.rl"
	{
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The content-length is in an invalid format");
            }
        }
	break;
	case 34:
// line 184 "src/rl/picard/http/HttpParser.rl"
	{
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The transfer-encoding is in an invalid format");
            }
        }
	break;
// line 946 "src/java/picard/http/HttpParser.java"
		}
	}
	}

case 5:
	}
	break; }
	}

// line 382 "src/rl/picard/http/HttpParser.rl"

        if (isParsingHead()) {
            pushBuffer(buf);
        }

        return p;
    }

    private void reset() {
        flags = 0;
        contentLength = 0;
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
