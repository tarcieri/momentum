package picard.core;

import java.nio.ByteBuffer;
import org.jboss.netty.buffer.ChannelBuffer;

public final class BufferBackedBuffer extends Buffer {

  final int offset;
  final Buffer buf;

  protected BufferBackedBuffer(Buffer buf, int offset, int pos, int lim, int cap) {
    super(pos, lim, cap);

    this.offset = offset;
    this.buf    = buf;
  }

  protected ByteBuffer _toByteBuffer() {
    return buf._toByteBuffer();
  }

  protected ChannelBuffer _toChannelBuffer() {
    return buf._toChannelBuffer();
  }

  protected byte[] _toByteArray() {
    return buf._toByteArray();
  }

  protected byte _get(int idx) {
    return buf._get(offset + idx);
  }

  protected void _get(int idx, byte[] dst, int off, int len) {
    buf._get(offset + idx, dst, off, len);
  }

  protected void _put(int idx, byte b) {
    buf._put(offset + idx, b);
  }

  protected void _put(int idx, byte[] src, int off, int len) {
    buf._put(offset + idx, src, off, len);
  }

  public Buffer duplicate() {
    return new BufferBackedBuffer(buf, offset, position, limit, capacity);
  }
}
