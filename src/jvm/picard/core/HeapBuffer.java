package picard.core;

public final class HeapBuffer extends Buffer {

  final int offset;
  final byte [] arr;

  protected HeapBuffer(byte [] arr, int offset, int pos, int lim, int cap) {
    super(pos, lim, cap);

    this.offset = offset;
    this.arr    = arr;
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
}
