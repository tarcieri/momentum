package picard.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.BufferUnderflowException;
import java.util.Arrays;
import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;

public abstract class Buffer {

  // The buffer's current position
  int position;

  // The buffer's current limit.
  int limit;

  // The buffer's current capacity. Subclasses that can be dynamically resized
  // might modify this propery.
  int capacity;

  // The byte order that any multibyte reads will use.
  boolean bigEndian = true;

  public static Buffer allocate(int cap) {
    return wrapArray(new byte[cap], 0, cap);
  }

  public static Buffer allocateDirect(int cap) {
    ByteBuffer buf = ByteBuffer.allocateDirect(cap);
    return new ByteBufferBackedBuffer(buf, 0, cap, cap);
  }

  public static Buffer wrap(byte[] arr) {
    return wrapArray(arr, 0, arr.length);
  }

  public static Buffer wrapArray(byte[] arr, int offset, int len) {
    return new HeapBuffer(arr, offset, 0, len, len);
  }

  public static Buffer wrap(ByteBuffer buf) {
    buf = buf.duplicate();
    return new ByteBufferBackedBuffer(buf, buf.position(), buf.limit(), buf.limit());
  }

  public static Buffer wrap(ChannelBuffer buf) {
    int cap = buf.capacity();
    return new ChannelBufferBackedBuffer(buf, buf.readerIndex(), cap, cap);
  }

  public static Buffer wrap(Buffer buf) {
    return buf;
  }

  public static Buffer wrap(Buffer[] bufs) {
    return new CompositeBuffer(bufs);
  }

  public static Buffer wrap(List<Object> objs) {
    Buffer[] bufs = new Buffer[objs.size()];

    for (int i = 0; i < bufs.length; ++i) {
      bufs[i] = Buffer.wrap(objs.get(i));
    }

    return new CompositeBuffer(bufs);
  }

  public static Buffer wrap(Object obj) {
    if (obj instanceof Buffer) {
      return (Buffer) obj;
    }
    else if (obj instanceof ByteBuffer) {
      return wrap((ByteBuffer) obj);
    }
    else {
      String msg = "Object " + obj + " not bufferable";
      throw new IllegalArgumentException();
    }
  }

  public static Buffer wrap(Object o1, Object o2) {
    return wrap(Arrays.asList(o1, o2));
  }

  public static Buffer wrap(Object o1, Object o2, Object o3) {
    return wrap(Arrays.asList(o1, o2, o3));
  }

  public static Buffer wrap(Object o1, Object o2, Object o3, Object o4) {
    return wrap(Arrays.asList(o1, o2, o3, o4));
  }

  public static Buffer wrap(Object o1, Object o2, Object o3, Object o4, Object o5) {
    return wrap(Arrays.asList(o1, o2, o3, o4, o5));
  }

  public static Buffer wrap(Object o1, Object o2, Object o3, Object o4, Object o5, Object o6) {
    return wrap(Arrays.asList(o1, o2, o3, o4, o5, o6));
  }

  protected Buffer(int pos, int lim, int cap) {
    if (cap < 0) {
      throw new IllegalArgumentException("Negative capacity: " + cap);
    }

    capacity = cap;

    limit(lim);
    position(pos);
  }

  /**
   * Returns this buffer's capacity.
   *
   * @return The capacity of this buffer
   */
  public final int capacity() {
    return capacity;
  }

  /**
   * Returns this buffer's position.
   *
   * @return The position of this buffer
   */
  public final int position() {
    return position;
  }

  /**
   * Sets this buffer's position. If the mark is defined and largern than the
   * new position then it is discarded.
   *
   * @param newPosition
   *        The new position value; must be non-negative
   *        and no larger than the current limit
   *
   * @return This buffer
   *
   * @throws IllegalArgumentException
   *         If the preconditions on newPosition do not hold
   */
  public final Buffer position(int newPosition) {
    if (newPosition > limit || newPosition < 0) {
      throw new IllegalArgumentException();
    }

    position = newPosition;

    return this;
  }

  public final int limit() {
    return limit;
  }

  public final Buffer limit(int newLimit) {
    if (newLimit > capacity || newLimit < 0) {
      throw new IllegalArgumentException();
    }

    limit = newLimit;

    if (position > limit) {
      position = limit;
    }

    return this;
  }

  public final Buffer flip() {
    limit = position;
    position = 0;

    return this;
  }

  public final Buffer clear() {
    position = 0;
    limit = capacity;

    return this;
  }

  public final Buffer rewind() {
    position = 0;

    return this;
  }

  public final int remaining() {
    return limit - position;
  }

  public final boolean hasRemaining() {
    return position < limit;
  }

  public final ByteOrder order() {
    return bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
  }

  public final Buffer order(ByteOrder order) {
    bigEndian = order == ByteOrder.BIG_ENDIAN;

    return this;
  }

  /*
   *
   *  Unchecked internal accessors
   *
   */

  protected abstract byte _get(int idx);
  protected abstract void _put(int idx, byte b);

  protected void _get(int idx, byte[] dst, int off, int len) {
    for (int i = 0; i < len; ++i) {
      dst[off + i] = get(idx + i);
    }
  }

  protected void _put(int idx, byte[] src, int off, int len) {
    for (int i = 0; i < len; ++i) {
      put(idx + i, src[off + i]);
    }
  }


  /*
   *
   *  BUFFER based accessors
   *
   */

  public final void get(Buffer buf) {
    // Not implemented yet
  }

  public void get(int idx, Buffer buf) {
    // Not implemented yet
  }

  public final void put(Buffer buf) {
    // Not implemented yet
  }

  public void put(int idx, Buffer buf) {
    // Not implemented yet
  }

  /*
   *
   *  BYTE accessors
   *
   */

  public final byte get() {
    assertWalkable(1);

    byte retval = get(position);
    ++position;
    return retval;
  }

  public final byte get(int idx) {
    if (idx < 0 || idx >= capacity) {
      throw new IndexOutOfBoundsException();
    }

    return _get(idx);
  }

  public final Buffer get(byte[] dst, int offset, int len) {
    assertWalkable(len);
    _get(position, dst, offset, len);
    position += len;

    return this;
  }

  public final Buffer get(int idx, byte[] dst, int offset, int len) {
    if (idx + len > capacity) {
      throw new BufferUnderflowException();
    }
    else if (idx < 0 || offset + len > dst.length) {
      throw new IndexOutOfBoundsException();
    }

    _get(idx, dst, offset, len);

    return this;
  }

  public final Buffer get(int idx, byte[] dst) {
    return get(idx, dst, 0, dst.length);
  }

  public final Buffer put(byte b) {
    assertWalkable(1);

    _put(position, b);
    ++position;

    return this;
  }

  public final Buffer put(int idx, byte b) {
    if (idx < 0 || idx >= capacity) {
      throw new IndexOutOfBoundsException();
    }

    _put(idx, b);

    return this;
  }

  public final Buffer put(byte[] src, int offset, int len) {
    assertWalkable(len);
    _put(position, src, offset, len);
    position += len;

    return this;
  }

  public final Buffer put(int idx, byte[] src, int offset, int len) {
    if (idx + len > capacity) {
      throw new BufferUnderflowException();
    }
    else if (idx < 0 || offset + len > src.length) {
      throw new IndexOutOfBoundsException();
    }

    _put(idx, src, offset, len);

    return this;
  }

  public final Buffer put(int idx, byte[] src) {
    return put(idx, src, 0, src.length);
  }

  /*
   *
   *  CHAR accessors
   *
   */

  private final char makeChar(byte b1, byte b0) {
    return (char) (b1 << 8 | b0 & 0xFF);
  }

  private final byte char1(char x) { return (byte) (x >> 8); }
  private final byte char0(char x) { return (byte)  x;       }

  public final char getChar() {
    return bigEndian ? getCharBigEndian() : getCharLittleEndian();
  }

  public final char getChar(int idx) {
    return bigEndian ? getCharBigEndian(idx) : getCharLittleEndian(idx);
  }

  public final char getCharBigEndian() {
    return getCharBigEndian(walking(2));
  }

  public final char getCharBigEndian(int idx) {
    return makeChar(get(idx), get(idx + 1));
  }

  public final char getCharLittleEndian() {
    return getCharLittleEndian(walking(2));
  }

  public final char getCharLittleEndian(int idx) {
    return makeChar(get(idx + 1), get(idx));
  }

  public final Buffer putChar(char val) {
    return bigEndian ? putCharBigEndian(val) : putCharLittleEndian(val);
  }

  public final Buffer putChar(int idx, char val) {
    return bigEndian ? putCharBigEndian(idx, val) : putCharLittleEndian(idx, val);
  }

  public final Buffer putCharBigEndian(char val) {
    return putCharBigEndian(walking(2), val);
  }

  public final Buffer putCharBigEndian(int idx, char val) {
    put(idx,     char1(val));
    put(idx + 1, char0(val));

    return this;
  }

  public final Buffer putCharLittleEndian(char val) {
    return putCharLittleEndian(walking(2), val);
  }

  public final Buffer putCharLittleEndian(int idx, char val) {
    put(idx,     char0(val));
    put(idx + 1, char1(val));

    return this;
  }

  /*
   *
   *  DOUBLE accessors
   *
   */

  public final double getDouble() {
    return Double.longBitsToDouble(getLong());
  }

  public final double getDouble(int idx) {
    return Double.longBitsToDouble(getLong(idx));
  }

  public final double getDoubleBigEndian() {
    return Double.longBitsToDouble(getLongBigEndian());
  }

  public final double getDoubleBigEndian(int idx) {
    return Double.longBitsToDouble(getLongBigEndian(idx));
  }

  public final double getDoubleLittleEndian() {
    return Double.longBitsToDouble(getLongLittleEndian());
  }

  public final double getDoubleLittleEndian(int idx) {
    return Double.longBitsToDouble(getLongLittleEndian(idx));
  }

  public final Buffer putDouble(double val) {
    return putLong(Double.doubleToRawLongBits(val));
  }

  public final Buffer putDouble(int idx, double val) {
    return putLong(idx, Double.doubleToRawLongBits(val));
  }

  public final Buffer putDoubleBigEndian(double val) {
    return putLongBigEndian(Double.doubleToRawLongBits(val));
  }

  public final Buffer putDoubleBigEndian(int idx, double val) {
    return putLongBigEndian(idx, Double.doubleToRawLongBits(val));
  }

  public final Buffer putDoubleLittleEndian(double val) {
    return putLongLittleEndian(Double.doubleToRawLongBits(val));
  }

  public final Buffer putDoubleLittleEndian(int idx, double val) {
    return putLongLittleEndian(idx, Double.doubleToRawLongBits(val));
  }

  /*
   *
   *  FLOAT accessors
   *
   */

  public final float getFloat() {
    return Float.intBitsToFloat(getInt());
  }

  public final float getFloat(int idx) {
    return Float.intBitsToFloat(getInt(idx));
  }

  public final float getFloatBigEndian() {
    return Float.intBitsToFloat(getIntBigEndian());
  }

  public final float getFloatBigEndian(int idx) {
    return Float.intBitsToFloat(getIntBigEndian(idx));
  }

  public final float getFloatLittleEndian() {
    return Float.intBitsToFloat(getIntLittleEndian());
  }

  public final float getFloatLittleEndian(int idx) {
    return Float.intBitsToFloat(getIntLittleEndian(idx));
  }

  public final Buffer putFloat(float val) {
    return putInt(Float.floatToRawIntBits(val));
  }

  public final Buffer putFloat(int idx, float val) {
    return putInt(idx, Float.floatToRawIntBits(val));
  }

  public final Buffer putFloatBigEndian(float val) {
    return putIntBigEndian(Float.floatToRawIntBits(val));
  }

  public final Buffer putFloatBigEndian(int idx, float val) {
    return putIntBigEndian(idx, Float.floatToRawIntBits(val));
  }

  public final Buffer putFloatLittleEndian(float val) {
    return putIntLittleEndian(Float.floatToRawIntBits(val));
  }

  public final Buffer putFloatLittleEndian(int idx, float val) {
    return putIntLittleEndian(idx, Float.floatToRawIntBits(val));
  }

  /*
   *
   *  INT accessors
   *
   */

  private final int makeInt(byte b3, byte b2, byte b1, byte b0) {
    return (b3       ) << 24 |
           (b2 & 0xFF) << 16 |
           (b1 & 0xFF) <<  8 |
           (b0 & 0xFF);
  }

  private final byte int3(int x) { return (byte) (x >> 24); }
  private final byte int2(int x) { return (byte) (x >> 16); }
  private final byte int1(int x) { return (byte) (x >>  8); }
  private final byte int0(int x) { return (byte)  x;        }

  public final int getInt() {
    return bigEndian ? getIntBigEndian() : getIntLittleEndian();
  }

  public final int getInt(int idx) {
    return bigEndian ? getIntBigEndian(idx) : getIntLittleEndian(idx);
  }

  public final int getIntBigEndian() {
    return getIntBigEndian(walking(4));
  }

  public final int getIntBigEndian(int idx) {
    return makeInt(get(idx), get(idx + 1), get(idx + 2), get(idx + 3));
  }

  public final int getIntLittleEndian() {
    return getIntLittleEndian(walking(4));
  }

  public final int getIntLittleEndian(int idx) {
    return makeInt(get(idx + 3), get(idx + 2), get(idx + 1), get(idx));
  }

  public final Buffer putInt(int val) {
    return bigEndian ? putIntBigEndian(val) : putIntLittleEndian(val);
  }

  public final Buffer putInt(int idx, int val) {
    return bigEndian ? putIntBigEndian(idx, val) : putIntLittleEndian(idx, val);
  }

  public final Buffer putIntBigEndian(int val) {
    return putIntBigEndian(walking(4), val);
  }

  public final Buffer putIntBigEndian(int idx, int val) {
    put(idx,     int3(val));
    put(idx + 1, int2(val));
    put(idx + 2, int1(val));
    put(idx + 3, int0(val));

    return this;
  }

  public final Buffer putIntLittleEndian(int val) {
    return putIntLittleEndian(walking(4), val);
  }

  public final Buffer putIntLittleEndian(int idx, int val) {
    put(idx,     int0(val));
    put(idx + 1, int1(val));
    put(idx + 2, int2(val));
    put(idx + 3, int3(val));

    return this;
  }

  /*
   *
   *  LONG accessors
   *
   */

  private final long makeLong(byte b7, byte b6, byte b5, byte b4,
                              byte b3, byte b2, byte b1, byte b0) {
    return ((long) b7       ) << 56 |
           ((long) b6 & 0xff) << 48 |
           ((long) b5 & 0xff) << 40 |
           ((long) b4 & 0xff) << 32 |
           ((long) b3 & 0xff) << 24 |
           ((long) b2 & 0xff) << 16 |
           ((long) b1 & 0xff) <<  8 |
           ((long) b0 & 0xff);
  }

  private final byte long7(long x) { return (byte) (x >> 56); }
  private final byte long6(long x) { return (byte) (x >> 48); }
  private final byte long5(long x) { return (byte) (x >> 40); }
  private final byte long4(long x) { return (byte) (x >> 32); }
  private final byte long3(long x) { return (byte) (x >> 24); }
  private final byte long2(long x) { return (byte) (x >> 16); }
  private final byte long1(long x) { return (byte) (x >>  8); }
  private final byte long0(long x) { return (byte)  x;        }

  public final long getLong() {
    return bigEndian ? getLongBigEndian() : getLongLittleEndian();
  }

  public final long getLong(int idx) {
    return bigEndian ? getLongBigEndian(idx) : getLongLittleEndian(idx);
  }

  public final long getLongBigEndian() {
    return getLongBigEndian(walking(8));
  }

  public final long getLongBigEndian(int idx) {
    return makeLong(get(idx),
                    get(idx + 1),
                    get(idx + 2),
                    get(idx + 3),
                    get(idx + 4),
                    get(idx + 5),
                    get(idx + 6),
                    get(idx + 7));
  }

  public final long getLongLittleEndian() {
    return getLongLittleEndian(walking(8));
  }

  public final long getLongLittleEndian(int idx) {
    return makeLong(get(idx + 7),
                    get(idx + 6),
                    get(idx + 5),
                    get(idx + 4),
                    get(idx + 3),
                    get(idx + 2),
                    get(idx + 1),
                    get(idx));
  }

  public final Buffer putLong(long val) {
    return bigEndian ? putLongBigEndian(val) : putLongLittleEndian(val);
  }

  public final Buffer putLong(int idx, long val) {
    return bigEndian ? putLongBigEndian(idx, val) : putLongLittleEndian(idx, val);
  }

  public final Buffer putLongBigEndian(long val) {
    return putLongBigEndian(walking(8), val);
  }

  public final Buffer putLongBigEndian(int idx, long val) {
    put(idx,     long7(val));
    put(idx + 1, long6(val));
    put(idx + 2, long5(val));
    put(idx + 3, long4(val));
    put(idx + 4, long3(val));
    put(idx + 5, long2(val));
    put(idx + 6, long1(val));
    put(idx + 7, long0(val));

    return this;
  }

  public final Buffer putLongLittleEndian(long val) {
    return putLongLittleEndian(walking(8), val);
  }

  public final Buffer putLongLittleEndian(int idx, long val) {
    put(idx,     long0(val));
    put(idx + 1, long1(val));
    put(idx + 2, long2(val));
    put(idx + 3, long3(val));
    put(idx + 4, long4(val));
    put(idx + 5, long5(val));
    put(idx + 6, long6(val));
    put(idx + 7, long7(val));

    return this;
  }

  /*
   *
   *  SHORT accessors
   *
   */

  private final short makeShort(byte b1, byte b0) {
    return (short) (b1 << 8 | b0 & 0xFF);
  }

  private final byte short1(short x) { return (byte) (x >> 8); }
  private final byte short0(short x) { return (byte)  x;       }

  public final short getShort() {
    return bigEndian ? getShortBigEndian() : getShortLittleEndian();
  }

  public final short getShort(int idx) {
    return bigEndian ? getShortBigEndian(idx) : getShortLittleEndian(idx);
  }

  public final short getShortBigEndian() {
    return getShortBigEndian(walking(2));
  }

  public final short getShortBigEndian(int idx) {
    return makeShort(get(idx), get(idx + 1));
  }

  public final short getShortLittleEndian() {
    return getShortLittleEndian(walking(2));
  }

  public final short getShortLittleEndian(int idx) {
    return makeShort(get(idx + 1), get(idx));
  }

  public final Buffer putShort(short val) {
    return bigEndian ? putShortBigEndian(val) : putShortLittleEndian(val);
  }

  public final Buffer putShort(int idx, short val) {
    return bigEndian ? putShortBigEndian(idx, val) : putShortLittleEndian(idx, val);
  }

  public final Buffer putShortBigEndian(short val) {
    return putShortBigEndian(walking(2), val);
  }

  public final Buffer putShortBigEndian(int idx, short val) {
    put(idx,     short1(val));
    put(idx + 1, short0(val));

    return this;
  }

  public final Buffer putShortLittleEndian(short val) {
    return putShortLittleEndian(walking(2), val);
  }

  public final Buffer putShortLittleEndian(int idx, short val) {
    put(idx,     short0(val));
    put(idx + 1, short1(val));

    return this;
  }

  private final void assertWalkable(int count) {
    if (limit - position < count) {
      throw new BufferUnderflowException();
    }
  }

  private final int walking(int count) {
    assertWalkable(count);

    int retval = position;
    position += count;
    return retval;
  }
}
