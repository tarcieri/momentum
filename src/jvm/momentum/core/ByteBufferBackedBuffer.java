package momentum.core;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

public final class ByteBufferBackedBuffer extends Buffer {

  final ByteBuffer buf;

  protected ByteBufferBackedBuffer(ByteBuffer buf) {
    this(buf, buf.position(), buf.limit(), buf.capacity());
  }

  protected ByteBufferBackedBuffer(ByteBuffer buf, int pos, int lim, int cap) {
    this(buf, pos, lim, cap, true);
  }

  protected ByteBufferBackedBuffer(ByteBuffer buf, int pos, int lim, int cap, boolean be) {
    super(pos, lim, cap, be);
    this.buf = buf;
  }

  protected Buffer _slice(int idx, int len) {
    buf.position(idx);
    buf.limit(idx + len);

    ByteBuffer newBuf = buf.slice();

    buf.limit(capacity);

    return new ByteBufferBackedBuffer(newBuf, 0, len, len, bigEndian);
  }

  protected HashMap<String,String> toStringAttrs() {
    HashMap<String,String> ret = super.toStringAttrs();

    ret.put("buffer", buf.toString());

    return ret;
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
}
