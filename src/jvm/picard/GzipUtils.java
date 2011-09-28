package picard ;

import java.util.zip.CRC32 ;
import java.util.zip.Deflater ;
import java.io.IOException ;

public class GzipUtils {
     /*
      * GZIP header magic number.
      */
    public final static int GZIP_MAGIC = 0x8b1f;

    /*
     * Trailer size in bytes.
     *
     */
    public final static int TRAILER_SIZE = 8;

    public final static byte[] header = {
        (byte) GZIP_MAGIC, // Magic number (short)
        (byte) (GZIP_MAGIC >> 8), // Magic number (short)
        Deflater.DEFLATED, // Compression method (CM)
        0, // Flags (FLG)
        0, // Modification time MTIME (int)
        0, // Modification time MTIME (int)
        0, // Modification time MTIME (int)
        0, // Modification time MTIME (int)
        0, // Extra flags (XFLG)
        0 // Operating system (OS)
    };



    public static void writeTrailer(Deflater def, CRC32 crc, byte[] buf, int offset) throws IOException   {
         writeInt((int)crc.getValue(), buf, offset); // CRC-32 of uncompr. data
         writeInt(def.getTotalIn(), buf, offset + 4); // Number of uncompr. bytes
     }

     /*
     * Writes integer in Intel byte order to a byte array, starting at a
      * given offset.
      */
     private static void writeInt(int i, byte[] buf, int offset) throws IOException   {
         writeShort(i & 0xffff, buf, offset);
         writeShort((i >> 16) & 0xffff, buf, offset + 2);
     }

     /*
      * Writes short integer in Intel byte order to a byte array, starting
      * at a given offset
      */
     private static void writeShort(int s, byte[] buf, int offset) throws IOException   {
         buf[offset] = (byte)(s & 0xff);
         buf[offset + 1] = (byte)((s >> 8) & 0xff);
     }
}
