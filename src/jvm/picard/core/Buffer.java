package picard.core;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

import clojure.lang.ISeq;
import clojure.lang.PersistentList;
import clojure.lang.Seqable;

/*
 * TODO: Add unsigned accessors
 */
public abstract class Buffer implements Seqable {

  static final int MIN_DYNAMIC_BUFFER_SIZE = 64;

  // The buffer's current position
  int position;

  // The buffer's current limit.
  int limit;

  // The buffer's current capacity. Subclasses that can be dynamically resized
  // might modify this propery.
  int capacity;

  // The byte order that any multibyte reads will use.
  boolean bigEndian;

  public final static Buffer allocate(int cap) {
    return wrapArray(new byte[cap], 0, cap);
  }

  public final static Buffer allocateDirect(int cap) {
    return wrap(ByteBuffer.allocateDirect(cap));
  }

  public final static Buffer wrapDynamic(Buffer buf, int max) {
    return CompositeBuffer.build(new Buffer[] { buf.slice() }, max);
  }

  public final static Buffer wrapDynamic(Buffer[] bufs, int max) {
    // Slice all the buffers
    for (int i = 0; i < bufs.length; ++i) {
      bufs[i] = bufs[i].slice();
    }

    return CompositeBuffer.build(bufs, max);
  }

  public final static Buffer wrapDynamic(Collection<Object> objs, int max)
      throws UnsupportedEncodingException {
    Buffer[] bufs     = new Buffer[objs.size()];
    Iterator iterator = objs.iterator();

    int i = 0;
    while (iterator.hasNext()) {
      bufs[i] = Buffer.wrap(iterator.next());
      ++i;
    }

    return CompositeBuffer.build(bufs, max);
  }

  public final static Buffer dynamic() {
    return dynamic(1024, Integer.MAX_VALUE);
  }

  public final static Buffer dynamic(int est) {
    return dynamic(est, Integer.MAX_VALUE);
  }

  public final static Buffer dynamic(int est, int max) {
    Buffer[] arr;

    if (est == 0) {
      arr = new Buffer[0];
    }
    else if (est < MIN_DYNAMIC_BUFFER_SIZE && max >= MIN_DYNAMIC_BUFFER_SIZE) {
      arr = new Buffer[] { allocate(MIN_DYNAMIC_BUFFER_SIZE) };
    }
    else {
      arr = new Buffer[] { allocate(est) };
    }

    return CompositeBuffer.build(arr, max);
  }

  public final static Buffer wrap(byte[] arr) {
    return wrapArray(arr, 0, arr.length);
  }

  public final static Buffer wrapArray(byte[] arr, int offset, int len) {
    return new HeapBuffer(arr, offset, 0, len, len);
  }

  public final static Buffer wrap(ByteBuffer buf) {
    if (buf.hasArray()) {
      return wrapArray(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
    }
    else {
      return new ByteBufferBackedBuffer(buf.slice());
    }
  }

  public final static Buffer wrap(ChannelBuffer buf) {
    // Slice first, axe questions later...
    buf = buf.slice();

    if (buf.hasArray()) {
      return wrapArray(buf.array(), buf.arrayOffset() + buf.readerIndex(), buf.readableBytes());
    }
    else {
      return new ChannelBufferBackedBuffer(buf);
    }
  }

  public final static Buffer wrap(Buffer buf) {
    return buf.slice();
  }

  public final static Buffer wrap(String str) throws UnsupportedEncodingException {
    return wrap(str.getBytes("UTF-8"));
  }

  public final static Buffer wrap(Buffer[] bufs) {
    if (bufs.length == 0) {
      return allocate(0);
    }
    else if (bufs.length == 1) {
      return wrap(bufs[0]);
    }
    else {
      return wrapDynamic(bufs, 0);
    }
  }

  public final static Buffer wrap(Collection<Object> objs) throws UnsupportedEncodingException {
    if (objs.size() == 0) {
      return allocate(0);
    }
    else if (objs.size() == 1) {
      Iterator i = objs.iterator();
      return wrap(i.next());
    }
    else {
      return wrapDynamic(objs, 0);
    }
  }

  // To prevent the compiler from complaining about the cast
  @SuppressWarnings("unchecked")
  public final static Buffer wrap(Object obj) throws UnsupportedEncodingException {
    if (obj instanceof Buffer) {
      return wrap((Buffer) obj);
    }
    else if (obj instanceof ByteBuffer) {
      return wrap((ByteBuffer) obj);
    }
    else if (obj instanceof ChannelBuffer) {
      return wrap((ChannelBuffer) obj);
    }
    else if (obj instanceof Collection) {
      return wrap((Collection<Object>) obj);
    }
    else if (obj instanceof byte[]) {
      return wrap((byte[]) obj);
    }
    else if (obj instanceof String) {
      return wrap((String) obj);
    }
    else if (obj instanceof Integer) {
      Integer n = (Integer) obj;
      return wrap(n.intValue());
    }
    else if (obj instanceof Long) {
      Long n = (Long) obj;
      return wrap(n.longValue());
    }
    else {
      String msg = "Object " + obj + "(" + obj.getClass() + ") not bufferable";
      throw new IllegalArgumentException(msg);
    }
  }

  public final static Buffer wrap(int n) {
    return Buffer.allocate(4).putIntBigEndian(0, n);
  }

  public final static Buffer wrap(long n) {
    if (n > Long.MIN_VALUE && n < Long.MAX_VALUE) {
      return Buffer.allocate(4).putIntBigEndian(0, (int) n);
    }
    else {
      return Buffer.allocate(8).putLongBigEndian(0, n);
    }
  }

  /**
   * Returns a Buffer wrapping the arguments.
   *
   * The new buffer will be backed by the given objects. Any modification to
   * the underlying objects will cause the buffer to be modified and vice
   * versa.
   *
   * @return The new buffer
   *
   * @throws IllegalArgumentException
   *         If any of the arguments cannot be wrapped by a Buffer.
   */
  public final static Buffer wrap(Object o1, Object o2)
      throws UnsupportedEncodingException {
    return wrap(Arrays.asList(o1, o2));
  }

  /**
   * Returns a Buffer wrapping the arguments.
   *
   * The new buffer will be backed by the given objects. Any modification to
   * the underlying objects will cause the buffer to be modified and vice
   * versa.
   *
   * @return The new buffer
   *
   * @throws IllegalArgumentException
   *         If any of the arguments cannot be wrapped by a Buffer.
   */
  public final static Buffer wrap(Object o1, Object o2, Object o3)
      throws UnsupportedEncodingException {
    return wrap(Arrays.asList(o1, o2, o3));
  }

  /**
   * Returns a Buffer wrapping the arguments.
   *
   * The new buffer will be backed by the given objects. Any modification to
   * the underlying objects will cause the buffer to be modified and vice
   * versa.
   *
   * @return The new buffer
   *
   * @throws IllegalArgumentException
   *         If any of the arguments cannot be wrapped by a Buffer.
   */
  public final static Buffer wrap(Object o1, Object o2, Object o3, Object o4)
      throws UnsupportedEncodingException {
    return wrap(Arrays.asList(o1, o2, o3, o4));
  }

  /**
   * Returns a Buffer wrapping the arguments.
   *
   * The new buffer will be backed by the given objects. Any modification to
   * the underlying objects will cause the buffer to be modified and vice
   * versa.
   *
   * @return The new buffer
   *
   * @throws IllegalArgumentException
   *         If any of the arguments cannot be wrapped by a Buffer.
   */
  public final static Buffer wrap(Object o1, Object o2, Object o3, Object o4, Object o5)
      throws UnsupportedEncodingException {
    return wrap(Arrays.asList(o1, o2, o3, o4, o5));
  }

  /**
   * Returns a Buffer wrapping the arguments.
   *
   * The new buffer will be backed by the given objects. Any modification to
   * the underlying objects will cause the buffer to be modified and vice
   * versa.
   *
   * @return The new buffer
   *
   * @throws IllegalArgumentException
   *         If any of the arguments cannot be wrapped by a Buffer.
   */
  public final static Buffer wrap(Object o1, Object o2, Object o3, Object o4, Object o5, Object o6)
      throws UnsupportedEncodingException {
    return wrap(Arrays.asList(o1, o2, o3, o4, o5, o6));
  }

  protected Buffer(int pos, int lim, int cap, boolean be) {
    if (cap < 0) {
      throw new IllegalArgumentException("Negative capacity: " + cap);
    }

    capacity  = cap;
    bigEndian = be;

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

  public final Buffer clear() {
    position = 0;
    limit = capacity;

    return this;
  }

  public final Buffer duplicate() {
    return _slice(0, capacity);
  }

  public final Buffer flip() {
    limit = position;
    position = 0;

    return this;
  }

  public final boolean equals(Buffer o) {
    int remaining = remaining();

    if (o.remaining() != remaining) {
      return false;
    }

    int indexA = position;
    int indexB = o.position;

    while (remaining-- > 0) {
      if (_get(indexA) != o._get(indexB)) {
        return false;
      }

      ++indexA;
      ++indexB;
    }

    return true;
  }

  public final boolean equals(Object o) {
    if (o instanceof Buffer) {
      return equals((Buffer) o);
    }

    return false;
  }

  public final Buffer focus(int len) {
    if (len < 0) {
      throw new IllegalArgumentException("length must be positive");
    }

    limit(position + len);

    return this;
  }

  public final boolean hasRemaining() {
    return position < limit;
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

  public final ByteOrder order() {
    return bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
  }

  public final Buffer order(ByteOrder order) {
    bigEndian = order == ByteOrder.BIG_ENDIAN;

    return this;
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

  public final ISeq seq() {
    return new PersistentList(this);
  }

  public final Buffer skip(int len) {
    position(position + len);

    return this;
  }

  protected Buffer _slice(int idx, int len) {
    return new BufferBackedBuffer(this, idx, 0, len, len, bigEndian);
  }

  public final Buffer slice() {
    return _slice(position, remaining());
  }

  public final Buffer slice(int idx, int len) {
    if (idx < 0 || idx > capacity) {
      throw new IndexOutOfBoundsException();
    }

    if (len < 0 || idx + len > capacity) {
      throw new IndexOutOfBoundsException();
    }

    return _slice(idx, len);
  }

  public final int remaining() {
    return limit - position;
  }

  public final Buffer rewind() {
    position = 0;

    return this;
  }

  protected HashMap<String,String> toStringAttrs() {
    HashMap<String,String> ret = new HashMap<String,String>();

    ret.put("pos", Integer.toString(position));
    ret.put("lim", Integer.toString(limit));
    ret.put("cap", Integer.toString(capacity));

    return ret;
  }

  public final String toString() {
    String str = getClass().getSimpleName();

    str += "#" + Integer.toHexString(System.identityHashCode(this));
    str += toStringAttrs().toString();

    return str;
  }

  public final Buffer window(int pos, int len) {
    return position(pos).limit(pos + len);
  }

  /*
   *
   *  Conversions
   *
   */

  protected byte[] _toByteArray() {
    byte[] arr = new byte[capacity];
    _get(0, arr, 0, capacity);
    return arr;
  }

  protected ByteBuffer _toByteBuffer() {
    return ByteBuffer.wrap(toByteArray());
  }

  protected ChannelBuffer _toChannelBuffer() {
    return ChannelBuffers.wrappedBuffer(_toByteBuffer());
  }

  public final ByteBuffer toByteBuffer() {
    ByteBuffer ret = _toByteBuffer();

    ret.position(position);
    ret.limit(limit);
    ret.order(order());

    return ret;
  }

  public final ChannelBuffer toChannelBuffer() {
    ChannelBuffer ret = _toChannelBuffer();
    ret.setIndex(position, limit);
    return ret;
  }

  public final byte[] toByteArray() {
    return _toByteArray();
  }

  public final String toString(String charsetName)
      throws UnsupportedEncodingException {

    byte[] arr = new byte[remaining()];

    _get(position, arr, 0, arr.length);

    return new String(arr, charsetName);
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
      dst[off + i] = _get(idx + i);
    }
  }

  protected void _put(int idx, byte[] src, int off, int len) {
    for (int i = 0; i < len; ++i) {
      _put(idx + i, src[off + i]);
    }
  }

  protected void _put(int idx, Buffer src, int off, int len) {
    for (int i = 0; i < len; ++i) {
      _put(idx + i, src._get(off + i));
    }
  }


  /*
   *
   *  BUFFER based accessors
   *
   */

  /**
   * Relative bulk get method
   *
   * Transfers bytes from this buffer into the given destination buffer. This
   * method advances the position of both this buffer and the destination
   * buffer.
   *
   * @return The new buffer
   *
   * @throws BufferUnderflowException
   *         If there are fewer than dst.remaining() bytes in this buffer
   * @throws BufferOverflowException
   *         If there is insufficient space in the destination buffer for the
   *         remaining bytes in the current buffer.
   * @throws ReadOnlyBufferException
   *         If the destination buffer is read-only.
   */
  public final Buffer get(Buffer dst) {
    int remaining = remaining();

    if (remaining == 0) {
      return this;
    }
    else if (dst.remaining() < remaining) {
      throw new BufferOverflowException();
    }

    dst._put(dst.position, this, position, remaining);

    position     += remaining;
    dst.position += remaining;

    return this;
  }

  public final Buffer get(Buffer dst, int off) {
    return get(dst, off, remaining());
  }

  public final Buffer get(Buffer dst, int off, int len) {
    int remaining = remaining();

    if (remaining < len) {
      throw new BufferUnderflowException();
    }
    else if (dst.capacity < off + len) {
      throw new IndexOutOfBoundsException();
    }

    dst._put(off, this, position, len);
    position += len;

    return this;
  }

  public final Buffer get(int idx, Buffer dst, int off, int len) {
    if (capacity < idx + len) {
      throw new BufferUnderflowException();
    }

    if (dst.capacity < off + len) {
      throw new BufferOverflowException();
    }

    dst._put(off, this, idx, len);

    return this;
  }

  public final Buffer put(Buffer src) {
    int len = src.remaining();

    put(position, src, src.position, len);
    position     += len;
    src.position += len;

    return this;
  }

  public final Buffer put(Buffer src, int off) {
    return put(src, off, remaining());
  }

  public final Buffer put(Buffer src, int off, int len) {
    put(position, src, off, len);
    position += len;

    return this;
  }

  public final Buffer put(int idx, Buffer src, int off, int len) {
    if (capacity < idx + len) {
      throw new BufferUnderflowException();
    }
    else if (src.capacity < off + len) {
      throw new BufferOverflowException();
    }

    _put(idx, src, off, len);

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

  public final int getUnsigned() {
    return get() & 0xFF;
  }

  public final byte get(int idx) {
    if (idx < 0 || idx >= capacity) {
      throw new IndexOutOfBoundsException();
    }

    return _get(idx);
  }

  public final int getUnsigned(int idx) {
    return get(idx) & 0xFF;
  }

  public final Buffer get(byte[] dst) {
    return get(dst, 0, dst.length);
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

  public final Buffer put(int b) {
    return put((byte) b);
  }

  public final Buffer putUnsigned(int b) {
    return put((byte) (b & 0xff));
  }

  public final Buffer put(int idx, byte b) {
    if (idx < 0 || idx >= capacity) {
      throw new IndexOutOfBoundsException();
    }

    _put(idx, b);

    return this;
  }

  // Random helper to avoid a couple casts
  public final Buffer put(int idx, int b) {
    return put(idx, (byte) b);
  }

  public final Buffer putUnsigned(int idx, int b) {
    return put(idx, (byte) (b & 0xff));
  }

  public final Buffer put(byte[] src) {
    return put(src, 0, src.length);
  }

  public final Buffer put(byte[] src, int offset, int len) {
    assertWalkable(len);
    put(position, src, offset, len);
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

  public final long getIntUnsigned() {
    return ((long) getInt()) & 0xFFFFFFFFL;
  }

  public final int getInt(int idx) {
    return bigEndian ? getIntBigEndian(idx) : getIntLittleEndian(idx);
  }

  public final long getIntUnsigned(int idx) {
    return ((long) getInt(idx)) & 0xFFFFFFFFL;
  }

  public final int getIntBigEndian() {
    return getIntBigEndian(walking(4));
  }

  public final long getIntUnsignedBigEndian() {
    return ((long) getIntBigEndian()) & 0xFFFFFFFFL;
  }

  public final int getIntBigEndian(int idx) {
    return makeInt(get(idx), get(idx + 1), get(idx + 2), get(idx + 3));
  }

  public final long getIntUnsignedBigEndian(int idx) {
    return ((long) getIntBigEndian(idx)) & 0xFFFFFFFFL;
  }

  public final int getIntLittleEndian() {
    return getIntLittleEndian(walking(4));
  }

  public final long getIntUnsignedLittleEndian() {
    return ((long) getIntLittleEndian()) & 0xFFFFFFFFL;
  }

  public final int getIntLittleEndian(int idx) {
    return makeInt(get(idx + 3), get(idx + 2), get(idx + 1), get(idx));
  }

  public final long getIntUnsignedLittleEndian(int idx) {
    return ((long) getIntLittleEndian(idx)) & 0xFFFFFFFFL;
  }

  public final Buffer putInt(int val) {
    return bigEndian ? putIntBigEndian(val) : putIntLittleEndian(val);
  }

  public final Buffer putIntUnsigned(long val) {
    return putInt((int) val);
  }

  public final Buffer putInt(int idx, int val) {
    return bigEndian ? putIntBigEndian(idx, val) : putIntLittleEndian(idx, val);
  }

  public final Buffer putIntUnsigned(int idx, long val) {
    return putInt(idx, (int) val);
  }

  public final Buffer putIntBigEndian(int val) {
    return putIntBigEndian(walking(4), val);
  }

  public final Buffer putIntUnsignedBigEndian(long val) {
    return putIntBigEndian((int) val);
  }

  public final Buffer putIntBigEndian(int idx, int val) {
    put(idx,     int3(val));
    put(idx + 1, int2(val));
    put(idx + 2, int1(val));
    put(idx + 3, int0(val));

    return this;
  }

  public final Buffer putIntUnsignedBigEndian(int idx, long val) {
    return putIntBigEndian(idx, (int) val);
  }

  public final Buffer putIntLittleEndian(int val) {
    return putIntLittleEndian(walking(4), val);
  }

  public final Buffer putIntUnsignedLittleEndian(long val) {
    return putIntLittleEndian((int) val);
  }

  public final Buffer putIntLittleEndian(int idx, int val) {
    put(idx,     int0(val));
    put(idx + 1, int1(val));
    put(idx + 2, int2(val));
    put(idx + 3, int3(val));

    return this;
  }

  public final Buffer putIntUnsignedLittleEndian(int idx, long val) {
    return putIntLittleEndian(idx, (int) val);
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

  public final int getShortUnsigned() {
    return getShort() & 0xFFFF;
  }

  public final short getShort(int idx) {
    return bigEndian ? getShortBigEndian(idx) : getShortLittleEndian(idx);
  }

  public final int getShortUnsigned(int idx) {
    return getShort(idx) & 0xFFFF;
  }

  public final short getShortBigEndian() {
    return getShortBigEndian(walking(2));
  }

  public final int getShortUnsignedBigEndian() {
    return getShortBigEndian() & 0xFFFF;
  }

  public final short getShortBigEndian(int idx) {
    return makeShort(get(idx), get(idx + 1));
  }

  public final int getShortUnsignedBigEndian(int idx) {
    return getShortBigEndian(idx) & 0xFFFF;
  }

  public final short getShortLittleEndian() {
    return getShortLittleEndian(walking(2));
  }

  public final int getShortUnsignedLittleEndian() {
    return getShortLittleEndian() & 0xFFFF;
  }

  public final short getShortLittleEndian(int idx) {
    return makeShort(get(idx + 1), get(idx));
  }

  public final int getShortUnsignedLittleEndian(int idx) {
    return getShortLittleEndian(idx) & 0xFFFF;
  }

  public final Buffer putShort(short val) {
    return bigEndian ? putShortBigEndian(val) : putShortLittleEndian(val);
  }

  public final Buffer putShortUnsigned(int val) {
    return putShort((short) val);
  }

  public final Buffer putShort(int idx, short val) {
    return bigEndian ? putShortBigEndian(idx, val) : putShortLittleEndian(idx, val);
  }

  public final Buffer putShortUnsigned(int idx, int val) {
    return putShort(idx, (short) val);
  }

  public final Buffer putShortBigEndian(short val) {
    return putShortBigEndian(walking(2), val);
  }

  public final Buffer putShortUnsignedBigEndian(int val) {
    return putShortBigEndian((short) val);
  }

  public final Buffer putShortBigEndian(int idx, short val) {
    put(idx,     short1(val));
    put(idx + 1, short0(val));

    return this;
  }

  public final Buffer putShortUnsignedBigEndian(int idx, int val) {
    return putShortBigEndian(idx, (short) val);
  }

  public final Buffer putShortLittleEndian(short val) {
    return putShortLittleEndian(walking(2), val);
  }

  public final Buffer putShortUnsignedLittleEndian(int val) {
    return putShortLittleEndian((short) val);
  }

  public final Buffer putShortLittleEndian(int idx, short val) {
    put(idx,     short0(val));
    put(idx + 1, short1(val));

    return this;
  }

  public final Buffer putShortUnsignedLittleEndian(int idx, int val) {
    return putShortLittleEndian(idx, (short) val);
  }

  private final void assertHolds(Buffer buf, int count) {
    if (buf.remaining() < count) {
      throw new BufferOverflowException();
    }
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
