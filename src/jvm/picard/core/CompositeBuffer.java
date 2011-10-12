package picard.core;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

public final class CompositeBuffer extends Buffer {

  private Buffer[] bufs;
  private int   [] indices;

  private int currentCapacity;
  private int lastBufferIdx;
  private int bufCount;

  protected CompositeBuffer(Buffer[] bufArr, int capacity) {
    // Call the super class initializer w/ BS values
    super(0, 0, 0);

    // Create the buffer array and the index lookup array. These are created bigger
    // than needed to accomodate for any buffer growth.
    int size = Math.max(10, bufArr.length * 2);

    bufs     = new Buffer[size];
    indices  = new int[size];
    bufCount = bufArr.length;

    // Calculate the capacity of the buffer and make an array with the indexes.
    for (int i = 0; i < bufArr.length; ++i) {
      // Add the buffer to the array
      Buffer buf = bufArr[i];
      bufs[i]    = buf;

      // Update the index array
      currentCapacity = indices[i + 1] = indices[i] + buf.limit();
    }

    this.capacity = Math.max(capacity, currentCapacity);
    limit         = currentCapacity;
  }

  public ByteBuffer toByteBuffer() {
    if (bufCount == 1) {
      ByteBuffer buf = bufs[0].toByteBuffer();

      buf.position(position());
      buf.limit(limit());
      buf.order(order());

      return buf;
    }
    else {
      return super.toByteBuffer();
    }
  }

  public ChannelBuffer toChannelBuffer() {
    ByteBuffer[] arr = new ByteBuffer[bufCount];
    ByteBuffer curr;

    for (int i = 0; i < bufCount; ++i) {
      curr = bufs[i].toByteBuffer();
      curr.order(order());
      arr[i] = curr;
    }

    return ChannelBuffers.wrappedBuffer(arr);
  }

  public byte[] toByteArray() {
    if (bufCount == 1) {
      return bufs[0].toByteArray();
    }
    else {
      byte[] arr = new byte[currentCapacity];

      _get(0, arr, 0, currentCapacity);

      return arr;
    }
  }

  protected byte _get(int idx) {
    if (idx >= currentCapacity) {
      return 0;
    }

    int bufIdx = bufferIndex(idx);
    return bufs[bufIdx].get(idx - indices[bufIdx]);
  }

  // public void get(int idx, byte [] dst, int offset, int len) {
  // }

  protected void _put(int idx, byte b) {
    int bufIdx;

    if (idx >= currentCapacity) {
      bufIdx = growTo(idx);
    }
    else {
      bufIdx = bufferIndex(idx);
    }

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

  private int growTo(int idx) {
    // Calculate the new buffer capacity. This will be at least twice the size
    // of the current buffer. However, we should ensure that the current write
    // can fit after the buffer grows, so if the required index is larger, just
    // use that.
    int newCapacity = Math.max(idx + 1, currentCapacity * 2);

    // Make sure that we haven't filled up our current buffer & index lookup
    // arrays. If we have, create new ones.
    if (bufCount == bufs.length) {
      bufs    = Arrays.copyOf(bufs,    bufs.length    + 10);
      indices = Arrays.copyOf(indices, indices.length + 10 + 1);
    }

    // Update the buffer information
    bufs[bufCount] = Buffer.allocate(newCapacity - currentCapacity);
    indices[bufCount + 1] = newCapacity;

    currentCapacity = newCapacity;
    lastBufferIdx   = bufCount;

    return bufCount++;
  }
}
