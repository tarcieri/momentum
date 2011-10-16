package picard.core;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

public final class ByteBufferBackedBuffer extends Buffer {

  final ByteBuffer buf;

  protected ByteBufferBackedBuffer(ByteBuffer buf, int pos, int lim, int cap) {
    super(pos, lim, cap);
    this.buf = buf;
  }

  protected ByteBuffer _toByteBuffer() {
    return buf.duplicate();
  }

  protected ChannelBuffer _toChannelBuffer() {
    ByteBuffer b = buf.duplicate();

    b.position(0);
    b.limit(capacity);
    b.order(order());

    return ChannelBuffers.wrappedBuffer(b);
  }

  protected byte[] _toByteArray() {
    if (buf.hasArray() && buf.arrayOffset() == 0) {
      byte[] ret = buf.array();

      if (ret.length == capacity) {
        return ret;
      }
      else {
        return Arrays.copyOf(ret, capacity);
      }
    }

    return super._toByteArray();
  }

  protected byte _get(int idx) {
    return buf.get(idx);
  }

  protected void _get(int idx, byte[] dst, int off, int len) {
    buf.position(idx);
    buf.get(dst, off, len);
  }

  protected void _put(int idx, byte b) {
    buf.put(idx, b);
  }

  protected void _put(int idx, byte[] src, int off, int len) {
    buf.position(idx);
    buf.put(src, off, len);
  }

  public Buffer duplicate() {
    return new ByteBufferBackedBuffer(buf, position, limit, capacity);
  }
}
