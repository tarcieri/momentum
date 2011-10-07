package picard.utils;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import org.jboss.netty.buffer.ChannelBuffer;

public class Hex {

    public static final short[] TO_HEX = new short[] {
        12336, 12592, 12848, 13104, 13360, 13616, 13872, 14128,
        14384, 14640, 24880, 25136, 25392, 25648, 25904, 26160,
        12592, 12593, 12594, 12595, 12596, 12597, 12598, 12599,
        12600, 12601, 12641, 12642, 12643, 12644, 12645, 12646,
        12848, 12849, 12850, 12851, 12852, 12853, 12854, 12855,
        12856, 12857, 12897, 12898, 12899, 12900, 12901, 12902,
        13104, 13105, 13106, 13107, 13108, 13109, 13110, 13111,
        13112, 13113, 13153, 13154, 13155, 13156, 13157, 13158,
        13360, 13361, 13362, 13363, 13364, 13365, 13366, 13367,
        13368, 13369, 13409, 13410, 13411, 13412, 13413, 13414,
        13616, 13617, 13618, 13619, 13620, 13621, 13622, 13623,
        13624, 13625, 13665, 13666, 13667, 13668, 13669, 13670,
        13872, 13873, 13874, 13875, 13876, 13877, 13878, 13879,
        13880, 13881, 13921, 13922, 13923, 13924, 13925, 13926,
        14128, 14129, 14130, 14131, 14132, 14133, 14134, 14135,
        14136, 14137, 14177, 14178, 14179, 14180, 14181, 14182,
        14384, 14385, 14386, 14387, 14388, 14389, 14390, 14391,
        14392, 14393, 14433, 14434, 14435, 14436, 14437, 14438,
        14640, 14641, 14642, 14643, 14644, 14645, 14646, 14647,
        14648, 14649, 14689, 14690, 14691, 14692, 14693, 14694,
        24880, 24881, 24882, 24883, 24884, 24885, 24886, 24887,
        24888, 24889, 24929, 24930, 24931, 24932, 24933, 24934,
        25136, 25137, 25138, 25139, 25140, 25141, 25142, 25143,
        25144, 25145, 25185, 25186, 25187, 25188, 25189, 25190,
        25392, 25393, 25394, 25395, 25396, 25397, 25398, 25399,
        25400, 25401, 25441, 25442, 25443, 25444, 25445, 25446,
        25648, 25649, 25650, 25651, 25652, 25653, 25654, 25655,
        25656, 25657, 25697, 25698, 25699, 25700, 25701, 25702,
        25904, 25905, 25906, 25907, 25908, 25909, 25910, 25911,
        25912, 25913, 25953, 25954, 25955, 25956, 25957, 25958,
        26160, 26161, 26162, 26163, 26164, 26165, 26166, 26167,
        26168, 26169, 26209, 26210, 26211, 26212, 26213, 26214
    };

    public static final byte[] FROM_HEX = new byte [] {
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
         0,  1,  2,  3,  4,  5,  6,  7,  8,  9, -1, -1, -1, -1, -1, -1,
        -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1
    };


    public static ByteBuffer hexEncode(ChannelBuffer src) {
        return hexEncode(src.toByteBuffer());
    }

    public static ByteBuffer hexEncode(ByteBuffer src) {
        int remaining  = src.remaining();
        ByteBuffer dst = ByteBuffer.allocate(remaining * 2);

        for (int i = src.position(); i < remaining; ++i) {
            dst.putShort(TO_HEX[src.get(i) & 0xFF]);
        }

        dst.flip();

        return dst;
    }

    public static ByteBuffer hexDecode(String src) throws UnsupportedEncodingException {
        return hexEncode(ByteBuffer.wrap(src.getBytes("UTF-8")));
    }

    public static ByteBuffer hexDecode(ChannelBuffer src) {
        return hexEncode(src.toByteBuffer());
    }

    public static ByteBuffer hexDecode(ByteBuffer src) {
        int remaining = src.remaining();

        if (remaining % 2 != 0) {
            throw new IllegalArgumentException("The src buffer must have an even number of bytes.");
        }

        ByteBuffer dst = ByteBuffer.allocate(remaining / 2);

        while (src.hasRemaining()) {
            int  b = 0;
            byte c = FROM_HEX[src.get()];

            if (c < 0) {
                throw new IllegalArgumentException("Src buffer not valid hex");
            }

            b = c * 16;

            c = FROM_HEX[src.get()];

            if (c < 0) {
                throw new IllegalArgumentException("Src buffer not valid hex");
            }

            dst.put((byte) (b + c));
        }

        dst.flip();

        return dst;
    }

}
