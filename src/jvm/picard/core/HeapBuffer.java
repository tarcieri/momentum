package picard.core;

public final class HeapBuffer extends Buffer {

  final byte [] arr;

  protected HeapBuffer(byte [] arr, int pos, int lim, int cap) {
    super(pos, lim, cap);

    this.arr = arr;
  }

  public byte get(int idx) {
    return arr[idx];
  }

  public void get(int idx, byte[] dst, int offset, int len) {
    System.arraycopy(arr, idx, dst, offset, len);
  }

  public void put(int idx, byte b) {
    arr[idx] = b;
  }

  public void put(int idx, byte[] src, int offset, int len) {
    System.arraycopy(src, offset, arr, idx, len);
  }
}
