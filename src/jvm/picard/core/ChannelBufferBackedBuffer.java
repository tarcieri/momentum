package picard.core;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

public final class ChannelBufferBackedBuffer extends Buffer {

  final ChannelBuffer buf;

  protected ChannelBufferBackedBuffer(ChannelBuffer buf, int pos, int lim, int cap) {
    super(pos, lim, cap);

    this.buf = buf;
  }

  protected ByteBuffer _toByteBuffer() {
    return buf.toByteBuffer();
  }

  protected ChannelBuffer _toChannelBuffer() {
    if (order() == buf.order()) {
      return buf;
    }
    else {
      ByteBuffer bb = _toByteBuffer();
      bb.order(order());
      return ChannelBuffers.wrappedBuffer(bb);
    }
  }

  protected byte[] _toByteArray() {
    if (buf.hasArray()) {
      byte[] ret = buf.array();

      if (buf.arrayOffset() == 0 && ret.length == capacity) {
        return ret;
      }

      return Arrays.copyOfRange(ret, buf.arrayOffset(), capacity);
    }

    return super._toByteArray();
  }

  protected byte _get(int idx) {
    return buf.getByte(idx);
  }

  protected void _get(int idx, byte[] dst, int offset, int len) {
    buf.getBytes(idx, dst, offset, len);
  }

  protected void _put(int idx, byte b) {
    buf.setByte(idx, b);
  }

  protected void _put(int idx, byte[] src, int offset, int len) {
    buf.setBytes(idx, src, offset, len);
  }

  public Buffer duplicate() {
    return new ChannelBufferBackedBuffer(buf, position, limit, capacity);
  }
}
