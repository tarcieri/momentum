package picard.core;

import java.util.List;

public final class CompositeBuffer extends Buffer {

  private final Buffer[] bufs;

  private int [] indices;
  private int lastBufferIdx;

  protected CompositeBuffer(Buffer[] bufArr) {
    // Call the super class initializer w/ BS values
    super(0, 0, 0);

    // Set the buffer array
    bufs    = new Buffer[bufArr.length];
    indices = new int[bufArr.length + 1];

    // Calculate the capacity of the buffer and make an array with the indexes.
    for (int i = 0; i < bufs.length; ++i) {
      // Add the buffer to the array
      Buffer buf = bufArr[i];
      bufs[i]    = buf;

      // Update the index array
      indices[i + 1] = indices[i] + buf.limit();

      // Update the capacity
      capacity += buf.limit();
    }

    limit = capacity;
  }

  public byte get(int idx) {
    int bufIdx = bufferIndex(idx);
    return bufs[bufIdx].get(idx - indices[bufIdx]);
  }

  // public void get(int idx, byte [] dst, int offset, int len) {
  // }

  public void put(int idx, byte b) {
    int bufIdx = bufferIndex(idx);
    bufs[bufIdx].put(idx - indices[bufIdx], b);
  }

  // public void put(int idx, byte [] src, int offset, int len) {
  // }

  private int bufferIndex(int idx) {
    // First, make sure that the index is within the bounds fo the buffer.
    if (idx < 0 || idx >= capacity) {
      throw new IndexOutOfBoundsException();
    }

    int bufferIdx = lastBufferIdx;

    if (idx >= indices[bufferIdx]) {
      // The hot path
      if (idx < indices[bufferIdx + 1]) {
        return bufferIdx;
      }

      // Search right
      do {
        ++bufferIdx;
      } while (indices[bufferIdx + 1] < idx);
  }
    else {
      // Search left
      do {
        --bufferIdx;
      } while (indices[bufferIdx] > idx);
    }

    lastBufferIdx = bufferIdx;
    return bufferIdx;
  }
}
