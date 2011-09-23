
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

    // A helper class that can be used to mark points of interest in
    // the buffers that are being parsed. Whenever a point of interest
    // is reached, it is marked with an instance of this class. When
    // the end of the point of interest is reached, that is also
    // marked. Using this class allows spanning multiple chunks since
    // when the end of a chunk is reached, we can simply finalize the
    // mark and then start up a new one when the next chunk is
    // received.
    private class Mark {
        // The previous mark in the stack
        private final Mark previous;

        // The byte buffer that this mark points to
        private final ByteBuffer buf;

        // The offset in the buffer that the mark starts at
        private final int from;

        // The offset in the buffer that the mark ends at
        private int to;

        // The total length of all the previous marks and the current
        // combined.
        private int total;

        public Mark(ByteBuffer buf, int from) {
            this(buf, from, null);
        }

        public Mark(ByteBuffer buf, int from, Mark previous) {
            this.buf      = buf;
            this.from     = from;
            this.previous = previous;
        }

        public int total() {
            return total;
        }

        public void finalize() {
            finalize(buf.limit());
        }

        public void finalize(int offset) {
            this.to    = offset;
            this.total = offset - from;

            if (previous != null) {
                this.total += previous.total();
            }
        }

        public Mark link(ByteBuffer buf) {
            return link(buf, buf.position());
        }

        public Mark link(ByteBuffer buf, int from) {
            return new Mark(buf, from, this);
        }

        public String materialize() {
            byte [] buf = new byte[total()];
            Mark    cur = this;
            int     pos = 0;

            while (cur != null) {
                pos += cur.copy(buf, pos);
                cur  = cur.previous();
            }

            return new String(buf);
        }

        protected Mark previous() {
            return previous;
        }

        protected int copy(byte [] dst, int pos) {
            int oldPos = buf.position();
            int oldLim = buf.limit();
            int length = to - from;

            buf.position(from);
            buf.limit(to);
            buf.get(dst, dst.length - (pos + length), length);

            buf.position(oldPos);
            buf.limit(oldLim);

            return length;
        }
    }

    
// line 318 "src/rl/picard/http/HttpParser.rl"


    public static final long ALMOST_MAX_LONG = Long.MAX_VALUE / 10 - 10;
    public static final int  MAX_HEADER_SIZE = 100 * 1024;
    public static final int  PARSING_HEAD    = 1 << 0;
    public static final int  IDENTITY_BODY   = 1 << 1;
    public static final int  CHUNKED_BODY    = 1 << 2;
    public static final int  KEEP_ALIVE      = 1 << 3;
    public static final int  UPGRADE         = 1 << 4;
    public static final int  ERROR           = 1 << 5;


    
// line 163 "src/java/picard/http/HttpParser.java"
private static byte[] init__http_actions_0()
{
	return new byte [] {
	    0,    1,    0,    1,    1,    1,    2,    1,    3,    1,    4,    1,
	    5,    1,    6,    1,    7,    1,    8,    1,    9,    1,   10,    1,
	   11,    1,   12,    1,   13,    1,   14,    1,   15,    1,   16,    1,
	   17,    1,   18,    1,   19,    1,   20,    1,   21,    1,   22,    1,
	   23,    1,   24,    1,   25,    1,   26,    1,   27,    1,   28,    1,
	   29,    1,   30,    1,   31,    1,   32,    1,   33,    1,   34,    1,
	   35,    1,   36,    1,   37,    1,   39,    1,   40,    1,   41,    2,
	   28,   29,    2,   32,   33,    2,   32,   39,    2,   38,   39
	};
}

private static final byte _http_actions[] = init__http_actions_0();


private static short[] init__http_key_offsets_0()
{
	return new short [] {
	    0,    0,   13,   15,   16,   17,   18,   19,   20,   21,   22,   36,
	   52,   53,   54,   55,   56,   57,   59,   62,   64,   67,   68,   87,
	   88,   89,   90,   91,   92,   93,   94,   95,   96,   97,   98,   99,
	  100,  101,  105,  106,  107,  108,  110,  111,  112,  113,  114,  115,
	  116,  117,  118,  119,  120,  121,  122,  123,  124,  125,  126,  127,
	  128,  129,  130,  131,  132,  133,  134,  135,  136,  137,  141,  142,
	  143,  144,  145,  146,  147,  148,  150,  151,  152,  153,  154,  155,
	  156,  157,  158,  159,  160,  161,  162,  163,  164,  165,  166,  167,
	  168,  169,  170,  171,  172,  173,  174,  175,  176,  178,  179,  180,
	  181,  182,  183,  184,  185,  186,  187,  188,  189,  204,  209,  214,
	  216,  233,  250,  267,  284,  301,  318,  334,  351,  368,  385,  402,
	  419,  436,  451,  454,  458,  475,  492,  509,  526,  543,  560,  577,
	  593,  610,  627,  644,  661,  678,  695,  712,  729,  744,  763,  780,
	  782,  801,  820,  839,  858,  877,  896,  913,  923,  930,  936,  942,
	  949,  955,  961,  971,  978,  984,  990,  999, 1008, 1015, 1021, 1027,
	 1038, 1052, 1059, 1065, 1071, 1092, 1102, 1111, 1118, 1124, 1130, 1132,
	 1133, 1134, 1135, 1136, 1137
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
	   67,   84,   99,  116,  123,  125,  127,    0,   32,   40,   41,   58,
	   64,   91,   93,   10,   69,   76,   69,   84,   69,   69,   84,   69,
	   65,   68,   79,   67,   75,   69,   75,   79,   83,   82,   71,   69,
	   65,   67,   67,   84,   73,   86,   73,   84,   89,   79,   76,   86,
	   69,   69,   65,   82,   67,   72,   79,   84,   73,   70,   89,   80,
	   84,   73,   79,   78,   83,   65,   79,   82,   85,   84,   67,   72,
	   83,   84,   79,   80,   70,   80,   73,   78,   68,   65,   84,   67,
	   72,   84,   69,   80,   79,   82,   84,   85,   66,   83,   67,   82,
	   73,   66,   69,   82,   65,   67,   69,   78,   76,   83,   79,   67,
	   75,   85,   66,   83,   67,   82,   73,   66,   69,   34,   44,   47,
	   58,  123,  125,  127,    0,   32,   40,   41,   59,   64,   91,   93,
	   13,   32,  127,    0,   31,   13,   32,  127,    0,   31,   13,   32,
	   34,   44,   47,   58,   79,  111,  123,  125,  127,    0,   32,   40,
	   41,   59,   64,   91,   93,   34,   44,   47,   58,   78,  110,  123,
	  125,  127,    0,   32,   40,   41,   59,   64,   91,   93,   34,   44,
	   47,   58,   84,  116,  123,  125,  127,    0,   32,   40,   41,   59,
	   64,   91,   93,   34,   44,   47,   58,   69,  101,  123,  125,  127,
	    0,   32,   40,   41,   59,   64,   91,   93,   34,   44,   47,   58,
	   78,  110,  123,  125,  127,    0,   32,   40,   41,   59,   64,   91,
	   93,   34,   44,   47,   58,   84,  116,  123,  125,  127,    0,   32,
	   40,   41,   59,   64,   91,   93,   34,   44,   45,   47,   58,  123,
	  125,  127,    0,   32,   40,   41,   59,   64,   91,   93,   34,   44,
	   47,   58,   76,  108,  123,  125,  127,    0,   32,   40,   41,   59,
	   64,   91,   93,   34,   44,   47,   58,   69,  101,  123,  125,  127,
	    0,   32,   40,   41,   59,   64,   91,   93,   34,   44,   47,   58,
	   78,  110,  123,  125,  127,    0,   32,   40,   41,   59,   64,   91,
	   93,   34,   44,   47,   58,   71,  103,  123,  125,  127,    0,   32,
	   40,   41,   59,   64,   91,   93,   34,   44,   47,   58,   84,  116,
	  123,  125,  127,    0,   32,   40,   41,   59,   64,   91,   93,   34,
	   44,   47,   58,   72,  104,  123,  125,  127,    0,   32,   40,   41,
	   59,   64,   91,   93,   34,   44,   47,   58,  123,  125,  127,    0,
	   32,   40,   41,   59,   64,   91,   93,   32,   48,   57,   13,   32,
	   48,   57,   34,   44,   47,   58,   82,  114,  123,  125,  127,    0,
	   32,   40,   41,   59,   64,   91,   93,   34,   44,   47,   58,   65,
	   97,  123,  125,  127,    0,   32,   40,   41,   59,   64,   91,   93,
	   34,   44,   47,   58,   78,  110,  123,  125,  127,    0,   32,   40,
	   41,   59,   64,   91,   93,   34,   44,   47,   58,   83,  115,  123,
	  125,  127,    0,   32,   40,   41,   59,   64,   91,   93,   34,   44,
	   47,   58,   70,  102,  123,  125,  127,    0,   32,   40,   41,   59,
	   64,   91,   93,   34,   44,   47,   58,   69,  101,  123,  125,  127,
	    0,   32,   40,   41,   59,   64,   91,   93,   34,   44,   47,   58,
	   82,  114,  123,  125,  127,    0,   32,   40,   41,   59,   64,   91,
	   93,   34,   44,   45,   47,   58,  123,  125,  127,    0,   32,   40,
	   41,   59,   64,   91,   93,   34,   44,   47,   58,   69,  101,  123,
	  125,  127,    0,   32,   40,   41,   59,   64,   91,   93,   34,   44,
	   47,   58,   78,  110,  123,  125,  127,    0,   32,   40,   41,   59,
	   64,   91,   93,   34,   44,   47,   58,   67,   99,  123,  125,  127,
	    0,   32,   40,   41,   59,   64,   91,   93,   34,   44,   47,   58,
	   79,  111,  123,  125,  127,    0,   32,   40,   41,   59,   64,   91,
	   93,   34,   44,   47,   58,   68,  100,  123,  125,  127,    0,   32,
	   40,   41,   59,   64,   91,   93,   34,   44,   47,   58,   73,  105,
	  123,  125,  127,    0,   32,   40,   41,   59,   64,   91,   93,   34,
	   44,   47,   58,   78,  110,  123,  125,  127,    0,   32,   40,   41,
	   59,   64,   91,   93,   34,   44,   47,   58,   71,  103,  123,  125,
	  127,    0,   32,   40,   41,   59,   64,   91,   93,   34,   44,   47,
	   58,  123,  125,  127,    0,   32,   40,   41,   59,   64,   91,   93,
	   13,   32,   34,   44,   47,   59,   67,   99,  123,  125,  127,    0,
	   31,   40,   41,   58,   64,   91,   93,   13,   32,   34,   44,   47,
	   59,  123,  125,  127,    0,   31,   40,   41,   58,   64,   91,   93,
	   13,   32,   13,   32,   34,   44,   47,   59,   72,  104,  123,  125,
	  127,    0,   31,   40,   41,   58,   64,   91,   93,   13,   32,   34,
	   44,   47,   59,   85,  117,  123,  125,  127,    0,   31,   40,   41,
	   58,   64,   91,   93,   13,   32,   34,   44,   47,   59,   78,  110,
	  123,  125,  127,    0,   31,   40,   41,   58,   64,   91,   93,   13,
	   32,   34,   44,   47,   59,   75,  107,  123,  125,  127,    0,   31,
	   40,   41,   58,   64,   91,   93,   13,   32,   34,   44,   47,   59,
	   69,  101,  123,  125,  127,    0,   31,   40,   41,   58,   64,   91,
	   93,   13,   32,   34,   44,   47,   59,   68,  100,  123,  125,  127,
	    0,   31,   40,   41,   58,   64,   91,   93,   13,   32,   34,   44,
	   47,   59,  123,  125,  127,    0,   31,   40,   41,   58,   64,   91,
	   93,   32,   37,   95,  126,   33,   34,   36,   90,   97,  122,  117,
	   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,   97,  102,
	   48,   57,   65,   70,   97,  102,  117,   48,   57,   65,   70,   97,
	  102,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,   97,
	  102,   32,   35,   37,   63,   95,  126,   33,   90,   97,  122,  117,
	   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,   97,  102,
	   48,   57,   65,   70,   97,  102,   32,   35,   37,   95,  126,   33,
	   90,   97,  122,   32,   35,   37,   95,  126,   33,   90,   97,  122,
	  117,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,   97,
	  102,   48,   57,   65,   70,   97,  102,   32,   35,   37,   47,   63,
	   95,  126,   33,   90,   97,  122,   32,   34,   35,   37,   47,   60,
	   62,   63,   95,  126,   33,   90,   97,  122,  117,   48,   57,   65,
	   70,   97,  102,   48,   57,   65,   70,   97,  102,   48,   57,   65,
	   70,   97,  102,   32,   33,   35,   37,   43,   47,   58,   59,   61,
	   63,   64,   95,  126,   36,   44,   45,   57,   65,   90,   97,  122,
	   37,   47,   95,  126,   33,   34,   36,   90,   97,  122,   32,   35,
	   37,   95,  126,   33,   90,   97,  122,  117,   48,   57,   65,   70,
	   97,  102,   48,   57,   65,   70,   97,  102,   48,   57,   65,   70,
	   97,  102,   78,   80,   78,   69,   67,   84,   89,   67,   68,   71,
	   72,   76,   77,   78,   79,   80,   82,   83,   84,   85,    0
	};
}

private static final char _http_trans_keys[] = init__http_trans_keys_0();


private static byte[] init__http_single_lengths_0()
{
	return new byte [] {
	    0,   13,    2,    1,    1,    1,    1,    1,    1,    1,    8,   10,
	    1,    1,    1,    1,    1,    0,    1,    0,    1,    1,   11,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    4,    1,    1,    1,    2,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    4,    1,    1,
	    1,    1,    1,    1,    1,    2,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    2,    1,    1,    1,
	    1,    1,    1,    1,    1,    1,    1,    1,    7,    3,    3,    2,
	    9,    9,    9,    9,    9,    9,    8,    9,    9,    9,    9,    9,
	    9,    7,    1,    2,    9,    9,    9,    9,    9,    9,    9,    8,
	    9,    9,    9,    9,    9,    9,    9,    9,    7,   11,    9,    2,
	   11,   11,   11,   11,   11,   11,    9,    4,    1,    0,    0,    1,
	    0,    0,    6,    1,    0,    0,    5,    5,    1,    0,    0,    7,
	   10,    1,    0,    0,   13,    4,    5,    1,    0,    0,    2,    1,
	    1,    1,    1,    1,   13
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
	    4,    4,    4,    4,    4,    4,    4,    4,    4,    4,    4,    4,
	    4,    4,    1,    1,    4,    4,    4,    4,    4,    4,    4,    4,
	    4,    4,    4,    4,    4,    4,    4,    4,    4,    4,    4,    0,
	    4,    4,    4,    4,    4,    4,    4,    3,    3,    3,    3,    3,
	    3,    3,    2,    3,    3,    3,    2,    2,    3,    3,    3,    2,
	    2,    3,    3,    3,    4,    3,    2,    3,    3,    3,    0,    0,
	    0,    0,    0,    0,    0
	};
}

private static final byte _http_range_lengths[] = init__http_range_lengths_0();


private static short[] init__http_index_offsets_0()
{
	return new short [] {
	    0,    0,   14,   17,   19,   21,   23,   25,   27,   29,   31,   43,
	   57,   59,   61,   63,   65,   67,   69,   72,   74,   77,   79,   95,
	   97,   99,  101,  103,  105,  107,  109,  111,  113,  115,  117,  119,
	  121,  123,  128,  130,  132,  134,  137,  139,  141,  143,  145,  147,
	  149,  151,  153,  155,  157,  159,  161,  163,  165,  167,  169,  171,
	  173,  175,  177,  179,  181,  183,  185,  187,  189,  191,  196,  198,
	  200,  202,  204,  206,  208,  210,  213,  215,  217,  219,  221,  223,
	  225,  227,  229,  231,  233,  235,  237,  239,  241,  243,  245,  247,
	  249,  251,  253,  255,  257,  259,  261,  263,  265,  268,  270,  272,
	  274,  276,  278,  280,  282,  284,  286,  288,  290,  302,  307,  312,
	  315,  329,  343,  357,  371,  385,  399,  412,  426,  440,  454,  468,
	  482,  496,  508,  511,  515,  529,  543,  557,  571,  585,  599,  613,
	  626,  640,  654,  668,  682,  696,  710,  724,  738,  750,  766,  780,
	  783,  799,  815,  831,  847,  863,  879,  893,  901,  906,  910,  914,
	  919,  923,  927,  936,  941,  945,  949,  957,  965,  970,  974,  978,
	  988, 1001, 1006, 1010, 1014, 1032, 1040, 1048, 1053, 1057, 1061, 1064,
	 1066, 1068, 1070, 1072, 1074
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
	   38,    1,   39,   38,    1,   40,    1,   41,    1,    1,    1,   43,
	   44,   43,   44,    1,    1,    1,    1,    1,    1,    1,   42,   45,
	    1,   46,    1,   47,    1,   48,    1,   49,    1,   50,    1,   51,
	    1,   52,    1,   53,    1,   54,    1,   55,    1,   56,    1,   57,
	    1,   58,    1,   59,   60,   61,   62,    1,   63,    1,   64,    1,
	   65,    1,   66,   67,    1,   68,    1,   69,    1,   70,    1,   71,
	    1,   72,    1,   73,    1,   74,    1,   75,    1,   76,    1,   77,
	    1,   78,    1,   79,    1,   80,    1,   81,    1,   82,    1,   83,
	    1,   84,    1,   85,    1,   86,    1,   87,    1,   88,    1,   89,
	    1,   90,    1,   91,    1,   92,    1,   93,    1,   94,    1,   95,
	   96,   97,   98,    1,   99,    1,  100,    1,  101,    1,  102,    1,
	  103,    1,  104,    1,  105,    1,  106,  107,    1,  108,    1,  109,
	    1,  110,    1,  111,    1,  112,    1,  113,    1,  114,    1,  115,
	    1,  116,    1,  117,    1,  118,    1,  119,    1,  120,    1,  121,
	    1,  122,    1,  123,    1,  124,    1,  125,    1,  126,    1,  127,
	    1,  128,    1,  129,    1,  130,    1,  131,    1,  132,    1,  133,
	    1,  134,  135,    1,  136,    1,  137,    1,  138,    1,  139,    1,
	  140,    1,  141,    1,  142,    1,  143,    1,  144,    1,  145,    1,
	  146,    1,    1,    1,    1,  148,    1,    1,    1,    1,    1,    1,
	    1,  147,  149,  150,    1,    1,  151,  152,  153,    1,    1,  154,
	   39,  155,    1,    1,    1,    1,  148,  156,  156,    1,    1,    1,
	    1,    1,    1,    1,  147,    1,    1,    1,  148,  157,  157,    1,
	    1,    1,    1,    1,    1,    1,  147,    1,    1,    1,  148,  158,
	  158,    1,    1,    1,    1,    1,    1,    1,  147,    1,    1,    1,
	  148,  159,  159,    1,    1,    1,    1,    1,    1,    1,  147,    1,
	    1,    1,  148,  160,  160,    1,    1,    1,    1,    1,    1,    1,
	  147,    1,    1,    1,  148,  161,  161,    1,    1,    1,    1,    1,
	    1,    1,  147,    1,    1,  162,    1,  148,    1,    1,    1,    1,
	    1,    1,    1,  147,    1,    1,    1,  148,  163,  163,    1,    1,
	    1,    1,    1,    1,    1,  147,    1,    1,    1,  148,  164,  164,
	    1,    1,    1,    1,    1,    1,    1,  147,    1,    1,    1,  148,
	  165,  165,    1,    1,    1,    1,    1,    1,    1,  147,    1,    1,
	    1,  148,  166,  166,    1,    1,    1,    1,    1,    1,    1,  147,
	    1,    1,    1,  148,  167,  167,    1,    1,    1,    1,    1,    1,
	    1,  147,    1,    1,    1,  148,  168,  168,    1,    1,    1,    1,
	    1,    1,    1,  147,    1,    1,    1,  169,    1,    1,    1,    1,
	    1,    1,    1,  147,  169,  171,  170,  172,  173,  171,  170,    1,
	    1,    1,  148,  174,  174,    1,    1,    1,    1,    1,    1,    1,
	  147,    1,    1,    1,  148,  175,  175,    1,    1,    1,    1,    1,
	    1,    1,  147,    1,    1,    1,  148,  176,  176,    1,    1,    1,
	    1,    1,    1,    1,  147,    1,    1,    1,  148,  177,  177,    1,
	    1,    1,    1,    1,    1,    1,  147,    1,    1,    1,  148,  178,
	  178,    1,    1,    1,    1,    1,    1,    1,  147,    1,    1,    1,
	  148,  179,  179,    1,    1,    1,    1,    1,    1,    1,  147,    1,
	    1,    1,  148,  180,  180,    1,    1,    1,    1,    1,    1,    1,
	  147,    1,    1,  181,    1,  148,    1,    1,    1,    1,    1,    1,
	    1,  147,    1,    1,    1,  148,  182,  182,    1,    1,    1,    1,
	    1,    1,    1,  147,    1,    1,    1,  148,  183,  183,    1,    1,
	    1,    1,    1,    1,    1,  147,    1,    1,    1,  148,  184,  184,
	    1,    1,    1,    1,    1,    1,    1,  147,    1,    1,    1,  148,
	  185,  185,    1,    1,    1,    1,    1,    1,    1,  147,    1,    1,
	    1,  148,  186,  186,    1,    1,    1,    1,    1,    1,    1,  147,
	    1,    1,    1,  148,  187,  187,    1,    1,    1,    1,    1,    1,
	    1,  147,    1,    1,    1,  148,  188,  188,    1,    1,    1,    1,
	    1,    1,    1,  147,    1,    1,    1,  148,  189,  189,    1,    1,
	    1,    1,    1,    1,    1,  147,    1,    1,    1,  190,    1,    1,
	    1,    1,    1,    1,    1,  147,  192,  193,  191,  191,  191,  195,
	  196,  196,  191,  191,  191,  191,  191,  191,  191,  194,  197,  198,
	  191,  191,  191,  200,  191,  191,  191,  191,  191,  191,  191,  199,
	  197,  198,  191,  197,  198,  191,  191,  191,  200,  201,  201,  191,
	  191,  191,  191,  191,  191,  191,  199,  197,  198,  191,  191,  191,
	  200,  202,  202,  191,  191,  191,  191,  191,  191,  191,  199,  197,
	  198,  191,  191,  191,  200,  203,  203,  191,  191,  191,  191,  191,
	  191,  191,  199,  197,  198,  191,  191,  191,  200,  204,  204,  191,
	  191,  191,  191,  191,  191,  191,  199,  197,  198,  191,  191,  191,
	  200,  205,  205,  191,  191,  191,  191,  191,  191,  191,  199,  197,
	  198,  191,  191,  191,  200,  206,  206,  191,  191,  191,  191,  191,
	  191,  191,  199,  207,  208,  191,  191,  191,  200,  191,  191,  191,
	  191,  191,  191,  191,  199,   27,  209,   28,   28,   28,   28,   28,
	    1,  211,  210,  210,  210,    1,   28,   28,   28,    1,  210,  210,
	  210,    1,  213,  212,  212,  212,    1,   23,   23,   23,    1,  212,
	  212,  212,    1,  214,  216,  217,  218,  215,  215,  215,  215,    1,
	  220,  219,  219,  219,    1,  215,  215,  215,    1,  219,  219,  219,
	    1,  221,  223,  224,  222,  222,  222,  222,    1,  225,  227,  228,
	  226,  226,  226,  226,    1,  230,  229,  229,  229,    1,  226,  226,
	  226,    1,  229,  229,  229,    1,  214,  216,  217,  231,  218,  215,
	  215,  215,  215,    1,  214,  215,  216,  232,   29,  215,  215,  218,
	  231,  231,  231,  231,    1,  234,  233,  233,  233,    1,  231,  231,
	  231,    1,  233,  233,  233,    1,   27,   23,   28,   24,   26,   29,
	  235,   23,   23,   30,   23,   23,   23,   23,   26,   26,   26,    1,
	  237,   25,  236,  236,  236,  236,  236,    1,   27,   28,  237,  236,
	  236,  236,  236,    1,  239,  238,  238,  238,    1,  236,  236,  236,
	    1,  238,  238,  238,    1,  240,  241,    1,  242,    1,  243,    1,
	  244,    1,  245,    1,  246,    1,    0,    2,    3,    4,    5,    6,
	    7,    8,    9,   10,   11,   12,   13,    1,    0
	};
}

private static final short _http_indicies[] = init__http_indicies_0();


private static short[] init__http_trans_targs_0()
{
	return new short [] {
	    2,    0,   24,   29,   31,   34,   37,   58,   63,   69,   86,   91,
	   99,  103,    3,  190,    4,    5,    6,    7,    8,    9,   10,   11,
	  167,  179,  184,   12,  163,  170,  174,   13,   14,   15,   16,   17,
	   18,   19,   20,   21,   22,   23,  116,  120,  136,  196,   25,   26,
	   27,   28,    9,   30,    9,   32,   33,    9,   35,   36,    9,   38,
	   41,   51,   53,   39,   40,    9,   42,   49,   43,   44,   45,   46,
	   47,   48,    9,   50,    9,   52,    9,   54,   55,   56,   57,    9,
	   59,   60,   61,   62,    9,   64,   65,   66,   67,   68,    9,   70,
	   73,   75,   85,   71,   72,    9,   74,    9,   76,   77,   78,   81,
	   79,   80,    9,   82,   83,   84,    9,    9,   87,   88,   89,   90,
	    9,   92,   93,   94,   95,   96,   97,   98,    9,  100,  101,  102,
	    9,  104,  105,  108,  106,  107,    9,  109,  110,  111,  112,  113,
	  114,  115,    9,  116,  117,   21,  117,  118,   21,  119,  118,  119,
	  121,  122,  123,  124,  125,  126,  127,  128,  129,  130,  131,  132,
	  133,  134,    0,  135,   21,  119,  137,  138,  139,  140,  141,  142,
	  143,  144,  145,  146,  147,  148,  149,  150,  151,  152,  153,    0,
	   21,  153,  154,  155,  156,   21,  119,  154,  155,  157,  158,  159,
	  160,  161,  162,   21,  119,  164,  165,  166,  168,  169,   12,  170,
	  163,  171,  174,  172,  173,   12,  175,  163,  176,   12,  175,  163,
	  176,  177,  178,  180,  181,  182,  183,  185,  186,  187,  188,  189,
	  191,  195,  192,  193,  194,    9,    9
	};
}

private static final short _http_trans_targs[] = init__http_trans_targs_0();


private static byte[] init__http_trans_actions_0()
{
	return new byte [] {
	   79,    0,   79,   79,   79,   79,   79,   79,   79,   79,   79,   79,
	   79,   79,    0,    0,    0,    0,    0,    0,    0,   35,    0,    0,
	    0,   53,    0,    0,    0,   53,    0,    0,    0,    0,    0,    0,
	   49,    0,   51,    0,    0,    0,   61,   61,   61,   81,    0,    0,
	    0,    0,    9,    0,    3,    0,    0,    1,    0,    0,   19,    0,
	    0,    0,    0,    0,    0,   37,    0,    0,    0,    0,    0,    0,
	    0,    0,   33,    0,   21,    0,   23,    0,    0,    0,    0,   39,
	    0,    0,    0,    0,   41,    0,    0,    0,    0,    0,   13,    0,
	    0,    0,    0,    0,    0,   47,    0,    5,    0,    0,    0,    0,
	    0,    0,   25,    0,    0,    0,   27,    7,    0,    0,    0,    0,
	   31,    0,    0,    0,    0,    0,    0,    0,   43,    0,    0,    0,
	   15,    0,    0,    0,    0,    0,   29,    0,    0,    0,    0,    0,
	    0,    0,   45,    0,   63,   86,    0,   65,   67,   67,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,   71,   69,   73,   73,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,   75,
	   89,   89,   65,   65,   65,   77,   77,    0,    0,    0,    0,    0,
	    0,    0,    0,   92,   92,    0,    0,    0,    0,    0,   55,    0,
	   55,    0,   55,    0,    0,   83,   57,   83,   57,   59,    0,   59,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,   11,   17
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
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,   71,   71,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,   75,   75,   75,
	   75,   75,   75,   75,   75,   75,   75,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,    0,
	    0,    0,    0,    0,    0
	};
}

private static final byte _http_eof_actions[] = init__http_eof_actions_0();


static final int http_start = 1;
static final int http_first_final = 196;
static final int http_error = 0;

static final int http_en_main = 1;


// line 331 "src/rl/picard/http/HttpParser.rl"

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
        
// line 640 "src/java/picard/http/HttpParser.java"
	{
	cs = http_start;
	}

// line 387 "src/rl/picard/http/HttpParser.rl"
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

        if (isParsingHead()) {
            pathInfoMark    = link(buf, pathInfoMark);
            queryStringMark = link(buf, queryStringMark);
            headerNameMark  = link(buf, headerNameMark);
            headerValueMark = link(buf, headerValueMark);
        }

        
// line 466 "src/rl/picard/http/HttpParser.rl"
        
// line 727 "src/java/picard/http/HttpParser.java"
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
// line 148 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.HEAD;        }
	break;
	case 1:
// line 149 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.GET;         }
	break;
	case 2:
// line 150 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.POST;        }
	break;
	case 3:
// line 151 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PUT;         }
	break;
	case 4:
// line 152 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.DELETE;      }
	break;
	case 5:
// line 153 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.CONNECT;     }
	break;
	case 6:
// line 154 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.OPTIONS;     }
	break;
	case 7:
// line 155 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.TRACE;       }
	break;
	case 8:
// line 156 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.COPY;        }
	break;
	case 9:
// line 157 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.LOCK;        }
	break;
	case 10:
// line 158 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MKCOL;       }
	break;
	case 11:
// line 159 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MOVE;        }
	break;
	case 12:
// line 160 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PROPFIND;    }
	break;
	case 13:
// line 161 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PROPPATCH;   }
	break;
	case 14:
// line 162 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.UNLOCK;      }
	break;
	case 15:
// line 163 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.REPORT;      }
	break;
	case 16:
// line 164 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MKACTIVITY;  }
	break;
	case 17:
// line 165 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.CHECKOUT;    }
	break;
	case 18:
// line 166 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MERGE;       }
	break;
	case 19:
// line 167 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.MSEARCH;     }
	break;
	case 20:
// line 168 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.NOTIFY;      }
	break;
	case 21:
// line 169 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.SUBSCRIBE;   }
	break;
	case 22:
// line 170 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.UNSUBSCRIBE; }
	break;
	case 23:
// line 171 "src/rl/picard/http/HttpParser.rl"
	{ method = HttpMethod.PATCH;       }
	break;
	case 24:
// line 173 "src/rl/picard/http/HttpParser.rl"
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
// line 183 "src/rl/picard/http/HttpParser.rl"
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
// line 193 "src/rl/picard/http/HttpParser.rl"
	{
            pathInfoMark = new Mark(buf, p);
        }
	break;
	case 27:
// line 197 "src/rl/picard/http/HttpParser.rl"
	{
            pathInfoMark.finalize(p);

            pathInfo     = pathInfoMark.materialize();
            pathInfoMark = null;
        }
	break;
	case 28:
// line 204 "src/rl/picard/http/HttpParser.rl"
	{
            queryStringMark = new Mark(buf, p);
        }
	break;
	case 29:
// line 208 "src/rl/picard/http/HttpParser.rl"
	{
            queryStringMark.finalize(p);

            queryString     = queryStringMark.materialize();
            queryStringMark = null;
        }
	break;
	case 30:
// line 215 "src/rl/picard/http/HttpParser.rl"
	{
            headerNameMark = new Mark(buf, p);
        }
	break;
	case 31:
// line 219 "src/rl/picard/http/HttpParser.rl"
	{
            headerNameMark.finalize(p);

            headerName     = headerNameMark.materialize().toLowerCase();
            headerNameMark = null;
        }
	break;
	case 32:
// line 226 "src/rl/picard/http/HttpParser.rl"
	{
            headerValueMark = new Mark(buf, p);
        }
	break;
	case 33:
// line 230 "src/rl/picard/http/HttpParser.rl"
	{
            headerValueMark.finalize(p);

            String headerValue = headerValueMark.materialize();
            headerValueMark    = null;

            callback.header(headers, headerName, headerValue);
        }
	break;
	case 34:
// line 239 "src/rl/picard/http/HttpParser.rl"
	{
            if (contentLength >= ALMOST_MAX_LONG) {
                flags |= ERROR;
                throw new HttpParserException("The content-length is WAY too big");
            }

            contentLength *= 10;
            contentLength += ( buf.get(p)) - '0';
        }
	break;
	case 35:
// line 249 "src/rl/picard/http/HttpParser.rl"
	{
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The content-length is in an invalid format");
            }
        }
	break;
	case 36:
// line 258 "src/rl/picard/http/HttpParser.rl"
	{
            if (isChunkedBody()) {
                flags |= ERROR;
                throw new HttpParserException("The message head is invalid");
            }

            flags |= IDENTITY_BODY;

            callback.header(headers, HDR_CONTENT_LENGTH, String.valueOf(contentLength));
        }
	break;
	case 37:
// line 269 "src/rl/picard/http/HttpParser.rl"
	{
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The transfer-encoding is in an invalid format");
            }
        }
	break;
	case 38:
// line 278 "src/rl/picard/http/HttpParser.rl"
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
	case 39:
// line 290 "src/rl/picard/http/HttpParser.rl"
	{
            if (headerValueMark != null) {
                headerValueMark.finalize(p);

                String headerValue = headerValueMark.materialize().toLowerCase();
                headerValueMark    = null;

                callback.header(headers, HDR_TRANSFER_ENCODING, headerValue);
            }
        }
	break;
	case 40:
// line 301 "src/rl/picard/http/HttpParser.rl"
	{
            flags  |= PARSING_HEAD;
            headers = callback.blankHeaders();
        }
	break;
	case 41:
// line 306 "src/rl/picard/http/HttpParser.rl"
	{
            // Not parsing the HTTP message head anymore
            flags ^= PARSING_HEAD;

            callback.request(this, headers);

            // Unset references to allow the GC to reclaim the memory
            resetHeadState();
        }
	break;
// line 1082 "src/java/picard/http/HttpParser.java"
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
	case 35:
// line 249 "src/rl/picard/http/HttpParser.rl"
	{
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The content-length is in an invalid format");
            }
        }
	break;
	case 37:
// line 269 "src/rl/picard/http/HttpParser.rl"
	{
            flags |= ERROR;

            // Hack to get Java to compile
            if (isError()) {
                throw new HttpParserException("The transfer-encoding is in an invalid format");
            }
        }
	break;
// line 1125 "src/java/picard/http/HttpParser.java"
		}
	}
	}

case 5:
	}
	break; }
	}

// line 467 "src/rl/picard/http/HttpParser.rl"

        if (isParsingHead()) {
            tieOff(pathInfoMark);
            tieOff(queryStringMark);
            tieOff(headerNameMark);
            tieOff(headerValueMark);
        }

        return p;
    }

    private Mark link(ByteBuffer buf, Mark mark) {
        if (mark == null) {
            return null;
        }

        return mark.link(buf);
    }

    private void tieOff(Mark mark) {
        if (mark != null) {
            mark.finalize();
        }
    }

    private void reset() {
        flags = 0;
        contentLength = 0;
        resetHeadState();
    }

    private void resetHeadState() {
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
}
