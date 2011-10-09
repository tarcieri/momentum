package picard.core;

import java.nio.ByteBuffer;

public final class ByteBufferBackedBuffer extends Buffer {

  final ByteBuffer buf;

  protected ByteBufferBackedBuffer(ByteBuffer buf, int pos, int lim, int cap) {
    super(pos, lim, cap);

    this.buf = buf;
  }

  public byte get(int idx) {
    return buf.get(idx);
  }

  public void get(int idx, byte[] dst, int offset, int len) {
    buf.position(idx);
    buf.get(dst, offset, len);
  }

  public void put(int idx, byte b) {
    buf.put(idx, b);
  }

  public void put(int idx, byte[] src, int offset, int len) {
    buf.position(idx);
    buf.put(src, offset, len);
  }
}
