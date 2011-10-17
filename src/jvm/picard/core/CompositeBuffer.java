package picard.core;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

/**
 * TODO:
 *   The "rope" functionality should be extracted out into a separate class and
 *   then duplicate() should be imlemented to create a new CompositeBuffer sharing
 *   the same rope.
 */
public final class CompositeBuffer extends Buffer {

  private Buffer[] bufs;
  private int   [] indices;

  private int currentCapacity;
  private int lastBufferIdx;
  private int bufCount;

  protected CompositeBuffer(Buffer[] bufArr, int capacity) {
    // Call the super class initializer w/ BS values
    super(0, 0, 0, true);

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
      currentCapacity = indices[i + 1] = indices[i] + buf.remaining();
    }

    this.capacity = Math.max(capacity, currentCapacity);
    this.limit    = this.capacity;
  }

  protected HashMap<String,String> toStringAttrs() {
    HashMap<String,String> ret = super.toStringAttrs();

    ret.put("parts",     Integer.toString(bufCount));
    ret.put("allocated", Integer.toString(currentCapacity));

    return ret;
  }

  protected ByteBuffer _toByteBuffer() {
    if (bufCount == 1) {
      return bufs[0]._toByteBuffer();
    }
    else {
      return super._toByteBuffer();
    }
  }

  protected ChannelBuffer _toChannelBuffer() {
    ByteBuffer[] arr = new ByteBuffer[bufCount];
    ByteBuffer curr;

    for (int i = 0; i < bufCount; ++i) {
      curr = bufs[i]._toByteBuffer();
      curr.order(order());
      arr[i] = curr;
    }

    ChannelBuffer ret = ChannelBuffers.wrappedBuffer(arr);

    if (ret.capacity() > capacity) {
      ret.writerIndex(capacity);
      ret = ret.slice();
    }

    return ret;
  }

  protected byte[] _toByteArray() {
    if (bufCount == 1 && capacity == bufs[0].capacity) {
      return bufs[0]._toByteArray();
    }
    else {
      int size   = Math.min(currentCapacity, capacity);
      byte[] arr = new byte[size];

      _get(0, arr, 0, size);

      return arr;
    }
  }

  protected byte _get(int idx) {
    if (idx >= currentCapacity) {
      return 0;
    }

    int bufIdx = bufferIndex(idx);

    Buffer curr = bufs[bufIdx];

    return curr.get(idx - indices[bufIdx]);
  }

  public void _get(int idx, byte [] dst, int off, int len) {
    if (idx >= currentCapacity) {
      Arrays.fill(dst, off, off + len, (byte) 0);
      return;
    }

    int bufIdx = bufferIndex(idx);
    Buffer curr;

    while (len > 0) {
      int nextIdx = indices[bufIdx + 1];
      int chunk   = Math.min(nextIdx - idx, len);

      curr = bufs[bufIdx];
      curr._get(idx - indices[bufIdx], dst, off, chunk);

      idx  = nextIdx;
      off += chunk;
      len -= chunk;

      if (idx >= currentCapacity) {
        Arrays.fill(dst, off, off + len, (byte) 0);
        return;
      }

      ++bufIdx;
    }
  }

  protected void _put(int idx, byte b) {
    int bufIdx;

    if (idx >= currentCapacity) {
      bufIdx = growTo(idx, 1);
    }
    else {
      bufIdx = bufferIndex(idx);
    }

    Buffer curr = bufs[bufIdx];
    curr.put(idx - indices[bufIdx], b);
  }

  public void _put(int idx, byte [] src, int off, int len) {
    int bufIdx;
    Buffer curr;

    if (idx >= currentCapacity) {
      bufIdx = growTo(idx, len);
    }
    else {
      if (idx + len > currentCapacity) {
        growTo(idx, len);
      }

      bufIdx = bufferIndex(idx);
    }

    while (len > 0) {
      int nextIdx = indices[bufIdx + 1];
      int chunk   = Math.min(nextIdx - idx, len);

      curr = bufs[bufIdx];
      curr._put(idx - indices[bufIdx], src, off, chunk);

      idx  = nextIdx;
      off += chunk;
      len -= chunk;

      ++bufIdx;
    }
  }

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

  private int growTo(int idx, int padding) {
    // Calculate the new buffer capacity. This will be at least twice the size
    // of the current buffer. However, we should ensure that the current write
    // can fit after the buffer grows, so if the required index is larger, just
    // use that.
    int newCapacity = Math.max(idx + padding, currentCapacity * 2);

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
