package picard.core;

import java.nio.ByteBuffer;
import org.jboss.netty.buffer.ChannelBuffer;

public final class BufferBackedBuffer extends Buffer {
  final Buffer buf;

  protected BufferBackedBuffer(Buffer buf, int pos, int lim, int cap, boolean frz) {
    super(pos, lim, cap, frz);
    this.buf = buf;
  }

  public ByteBuffer toByteBuffer() {
    return buf.toByteBuffer();
  }

  public ChannelBuffer toChannelBuffer() {
    return buf.toChannelBuffer();
  }

  public byte[] toByteArray() {
    return buf.toByteArray();
  }

  protected byte _get(int idx) {
    return buf._get(idx);
  }

  protected void _get(int idx, byte[] dst, int off, int len) {
    buf._get(idx, dst, off, len);
  }

  protected void _put(int idx, byte b) {
    buf._put(idx, b);
  }

  protected void _put(int idx, byte[] src, int off, int len) {
    buf._put(idx, src, off, len);
  }

  public Buffer duplicate() {
    return new BufferBackedBuffer(buf, position, limit, capacity, isFrozen);
  }
}
