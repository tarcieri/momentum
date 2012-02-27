package momentum.buffer;

import java.nio.ByteBuffer;
import org.jboss.netty.buffer.ChannelBuffer;
import java.util.Arrays;
import java.util.HashMap;

public final class BufferBackedBuffer extends Buffer {

  final int offset;
  final Buffer buf;

  protected BufferBackedBuffer(Buffer buf, int offset, int pos, int lim, int cap) {
    this(buf, offset, pos, lim, cap, true);
  }

  protected BufferBackedBuffer(Buffer buf, int offset, int pos, int lim, int cap, boolean be) {
    super(pos, lim, cap, be);

    this.offset = offset;
    this.buf    = buf;
  }

  public boolean isTransient() {
    return buf.isTransient();
  }

  public Buffer makeTransient() {
    return buf.makeTransient();
  }

  protected Buffer _slice(int idx, int len) {
    return new BufferBackedBuffer(buf, offset + idx, 0, len, len, bigEndian);
  }

  protected HashMap<String,String> toStringAttrs() {
    HashMap<String,String> ret = super.toStringAttrs();

    ret.put("offset", Integer.toString(offset));
    ret.put("buffer", buf.toString());

    return ret;
  }

  protected ByteBuffer _toByteBuffer() {
    ByteBuffer ret = buf._toByteBuffer();

    if (offset > 0 || capacity < ret.capacity()) {
      ret.position(offset);
      ret.limit(offset + capacity);
      ret = ret.slice();
    }

    return ret;
  }

  protected ChannelBuffer _toChannelBuffer() {
    ChannelBuffer ret = buf._toChannelBuffer();

    if (offset > 0 || capacity < ret.capacity()) {
      ret = ret.slice(offset, capacity);
    }

    return ret;
  }

  protected byte[] _toByteArray() {
    if (offset == 0 && capacity == buf.capacity) {
      return buf._toByteArray();
    }

    byte[] ret = new byte[capacity];

    _get(0, ret, 0, capacity);

    return ret;
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
}
