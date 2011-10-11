package picard.core;

import java.nio.ByteBuffer;

public final class ByteBufferBackedBuffer extends Buffer {

  final ByteBuffer buf;

  protected ByteBufferBackedBuffer(ByteBuffer buf, int pos, int lim, int cap) {
    super(pos, lim, cap);

    this.buf = buf;
  }

  protected byte _get(int idx) {
    return buf.get(idx);
  }

  protected void _get(int idx, byte[] dst, int offset, int len) {
    buf.position(idx);
    buf.get(dst, offset, len);
  }

  protected void _put(int idx, byte b) {
    buf.put(idx, b);
  }

  protected void _put(int idx, byte[] src, int offset, int len) {
    buf.position(idx);
    buf.put(src, offset, len);
  }
}
