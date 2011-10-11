package picard.core;

import org.jboss.netty.buffer.ChannelBuffer;

public final class ChannelBufferBackedBuffer extends Buffer {

  final ChannelBuffer buf;

  protected ChannelBufferBackedBuffer(ChannelBuffer buf, int pos, int lim, int cap) {
    super(pos, lim, cap);

    this.buf = buf;
  }

  public byte get(int idx) {
    return buf.getByte(idx);
  }

  public void get(int idx, byte[] dst, int offset, int len) {
    buf.getBytes(idx, dst, offset, len);
  }

  public void put(int idx, byte b) {
    buf.setByte(idx, b);
  }

  public void put(int idx, byte[] src, int offset, int len) {
    buf.setBytes(idx, src, offset, len);
  }
}
