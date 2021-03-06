package momentum.buffer;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

public final class HeapBuffer extends Buffer {

  final int offset;
  final byte [] arr;

  protected HeapBuffer(byte [] arr, int off, int pos, int lim, int cap) {
    this(arr, off, pos, lim, cap, true);
  }

  protected HeapBuffer(byte [] arr, int off, int pos, int lim, int cap, boolean be) {
    super(pos, lim, cap, be);

    this.offset = off;
    this.arr    = arr;
  }

  protected Buffer _slice(int idx, int len) {
    return new HeapBuffer(arr, offset + idx, 0, len, len, bigEndian);
  }

  protected ByteBuffer _toByteBuffer() {
    return ByteBuffer.wrap(arr, offset, capacity).slice();
  }

  protected ChannelBuffer _toChannelBuffer() {
    return ChannelBuffers.wrappedBuffer(arr, offset, capacity);
  }

  protected byte[] _toByteArray() {
    if (offset == 0 && capacity == arr.length) {
      return arr;
    }

    return Arrays.copyOfRange(arr, offset, offset + capacity);
  }

  public byte _get(int idx) {
    return arr[offset + idx];
  }

  protected void _get(int idx, byte[] dst, int off, int len) {
    System.arraycopy(arr, offset + idx, dst, off, len);
  }

  protected void _put(int idx, byte b) {
    arr[offset + idx] = b;
  }

  protected void _put(int idx, byte[] src, int off, int len) {
    System.arraycopy(src, off, arr, offset + idx, len);
  }

  protected void _put(int idx, Buffer src, int off, int len) {
    if (src instanceof HeapBuffer) {
      HeapBuffer s = (HeapBuffer) src;
      System.arraycopy(s.arr, s.offset + off, arr, offset + idx, len);
    }
    else {
      src._get(off, arr, offset + idx, len);
    }
  }
}
