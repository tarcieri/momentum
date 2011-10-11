package picard.core;

import org.jboss.netty.buffer.ChannelBuffer;

public final class ChannelBufferBackedBuffer extends Buffer {

  final ChannelBuffer buf;

  protected ChannelBufferBackedBuffer(ChannelBuffer buf, int pos, int lim, int cap) {
    super(pos, lim, cap);

    this.buf = buf;
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
}
