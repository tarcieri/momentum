package picard.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.BufferUnderflowException;

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
    return new HeapBuffer(new byte[cap], 0, cap, cap);
  }

  public static Buffer allocateDirect(int cap) {
    ByteBuffer buf = ByteBuffer.allocateDirect(cap);
    return new ByteBufferBackedBuffer(buf, 0, cap, cap);
  }

  public static Buffer wrap(byte[] arr) {
    return new HeapBuffer(arr, 0, arr.length, arr.length);
  }

  public static Buffer wrap(ByteBuffer buf) {
    buf = buf.duplicate();
    return new ByteBufferBackedBuffer(buf, buf.position(), buf.limit(), buf.limit());
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
   *  BYTE accessors
   *
   */

  public final byte get() {
    assertWalkable(1);

    byte retval = get(position);
    ++position;
    return retval;
  }

  // Single byte reading
  public abstract byte get(int idx);

  public final void get(byte[] dst, int offset, int len) {
    assertWalkable(len);
    get(position, dst, offset, len);
    position += len;
  }

  // Bulk byte reading
  public abstract void get(int idx, byte[] dst, int offset, int len);

  public final void put(byte b) {
    assertWalkable(1);

    put(position, b);
    ++position;
  }

  // Single byte writing
  public abstract void put(int idx, byte b);

  public final void put(byte[] src, int offset, int len) {
    assertWalkable(len);
    put(position, src, offset, len);
    position += len;
  }

  // Bulk byte writing
  public abstract void put(int idx, byte[] src, int offset, int len);

  /*
   *
   *  CHAR accessors
   *
   */

  private final char makeChar(byte b1, byte b0) {
    return (char) (b1 << 8 | b0 & 0xFF);
  }

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

  private final byte char1(char x) {
    return (byte) (x >> 8);
  }

  private final byte char0(char x) {
    return (byte) x;
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

  public final double getDouble() {
    return (double) 0;
  }

  public double getDouble(int index) {
    return (double) 0;
  }

  public float getFloat() {
    return (float) 0;
  }

  public float getFloat(int index) {
    return (float) 0;
  }

  public int getInt() {
    return (int) 0;
  }

  public int getInt(int index) {
    return (int) 0;
  }

  public long getLong() {
    return (long) 0;
  }

  public long getLong(int index) {
    return (long) 0;
  }

  public short getShort() {
    return (short) 0;
  }

  public short getShort(int index) {
    return (short) 0;
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
