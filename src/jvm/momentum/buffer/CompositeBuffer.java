package momentum.buffer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

public final class CompositeBuffer extends Buffer {

  private static int MIN_IDX_ARR_LEN = 10;

  Buffer[] bufs;
  int   [] indices;

  int currentCapacity;
  int lastBufferIdx;
  int bufCount;

  // TODO: This function should probably extract the components of any
  // CompositeBuffer that it is wrapping
  protected static Buffer build(Buffer[] bufArr, int capacity) {
    // Create the buffer array and the index lookup array. These are created bigger
    // than needed to accomodate for any buffer growth.
    int size = Math.max(MIN_IDX_ARR_LEN, bufArr.length * 2);

    Buffer[] bufs = new Buffer[size];
    int[] indices = new int[size];

    // Calculate the capacity of the buffer and make an array with the indexes.
    for (int i = 0; i < bufArr.length; ++i) {
      // Add the buffer to the array
      Buffer buf = bufArr[i];
      bufs[i]    = buf;

      // Update the index array
      indices[i + 1] = indices[i] + buf.capacity;
    }

    capacity = Math.max(capacity, indices[bufArr.length]);

    return new CompositeBuffer(bufs, indices, bufArr.length, 0, capacity, capacity, true);
  }

  protected CompositeBuffer(Buffer[] bs, int[] idxs, int cnt, int pos, int lim, int cap, boolean be) {
    super(pos, lim, cap, be);

    bufs     = bs;
    indices  = idxs;
    bufCount = cnt;

    currentCapacity = idxs[cnt];
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

  protected Buffer _slice(int idx, int len) {
    // Quick check, is the range equal to the current buffer size?
    if (idx == 0 && len == capacity) {
      new CompositeBuffer(dupBufs(), dupIndices(), bufCount, 0, len, len, bigEndian);
    }
    else if (idx >= currentCapacity) {
      // If the slice range is out of the currently allocated range,
      // then just return a new empty dynamic buffer
      return Buffer.dynamic(0, len).order(order());
    }

    int start  = bufferIndex(idx);
    int offset = idx - indices[start];

    // Is the range contained in a single buffer?
    if (len <= indices[start + 1] - indices[start] - offset) {
      return bufs[start]._slice(offset, len).order(order());
    }
    // Is the range small enough?
    else if (len <= 256) {
      byte[] arr = new byte[len];
      _get(idx, arr, 0, len);
      return Buffer.wrap(arr).order(order());
    }
    // Otherwise, just gonna slice it
    else {
      // Allocate the new lookup arrs
      Buffer[] newBufs = new Buffer[bufs.length];
      int[] newIndices = new int[indices.length];

      Buffer curr = bufs[start];
      curr = curr._slice(offset, curr.capacity - offset);

      newBufs[0]    = curr;
      newIndices[1] = curr.capacity;

      int cap;
      int cnt = 1;

      while (cnt != bufCount && newIndices[cnt] < len) {
        curr = bufs[start + cnt];
        cap  = newIndices[cnt] + curr.capacity;

        if (cap > len) {
          curr = curr._slice(0, len - newIndices[cnt]);
          cap  = len;
        }

        newBufs[cnt]      = curr;
        newIndices[++cnt] = cap;
      }

      return new CompositeBuffer(newBufs, newIndices, cnt, 0, len, len, bigEndian);
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
    int bufIdx = peekBufferIndex(idx);
    lastBufferIdx = bufIdx;
    return bufIdx;
  }

  private int peekBufferIndex(int idx) {
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
      } while (indices[bufferIdx + 1] <= idx);
    }
    else {
      // Search left
      do {
        --bufferIdx;
      } while (indices[bufferIdx] > idx);
    }

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

  private Buffer[] dupBufs() {
    return Arrays.copyOf(bufs, bufs.length);
  }

  private int[] dupIndices() {
    return Arrays.copyOf(indices, indices.length);
  }
}
