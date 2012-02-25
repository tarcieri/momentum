package momentum.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/*
 * A lock free queue structure backed by an AtomicReferenceArray.
 *
 * WARNING: This structure only supports single consumer (multiple producer);
 * however, there are no guards to prevent concurrent consumption.
 */
public class ArrayAtomicQueue<T> {

  final int mask;

  /*
   * The backing atomic array.
   */
  final AtomicReferenceArray<T> nodes;

  /*
   * The array offset that contains the head of the queue. It is only used the
   * poll method.
   */
  int head;

  /*
   * The array offset that contains the tail of the queue.
   */
  final AtomicInteger tail = new AtomicInteger();

  static final int ceilingNextPowerOfTwo(int x) {
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
  }

  public ArrayAtomicQueue(int capacity) {
    nodes = new AtomicReferenceArray<T>(ceilingNextPowerOfTwo(capacity));
    mask  = nodes.length() - 1;
  }

  public boolean offer(T val) {
    if (val == null) {
      throw new NullPointerException("Can't insert null values");
    }

    int idx = tail.getAndIncrement() & mask;

    if (!nodes.compareAndSet(idx, null, val)) {
      // Something is going very wrong
      return false;
    }

    return true;
  }

  public T poll() {
    int idx = head & mask;
    T ret   = nodes.get(idx);

    if (ret == null) {
      return null;
    }

    nodes.set(idx, null);
    ++head;

    return ret;
  }
}
