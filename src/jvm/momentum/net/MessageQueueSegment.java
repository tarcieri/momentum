package momentum.net;

import momentum.buffer.Buffer;

class MessageQueueSegment {

  /*
   * The buffers in the current segment
   */
  final Buffer[] buffers = new Buffer[1024];

  /*
   * The current head offset. Starts at -1.
   */
  int headOffset = -1;

  /*
   * The current tail offset. -1 if empty.
   */
  int tailOffset = -1;

  /*
   * Pointer to the next segment.
   */
  MessageQueueSegment next;

  /*
   * Returns the first element without removing it
   */
  Buffer peek() {
    if (headOffset == tailOffset)
      return null;

    return buffers[headOffset+1];
  }

  /*
   * Pops an element
   */
  Buffer pop() {
    if (headOffset == tailOffset)
      return null;

    return buffers[++headOffset];
  }

  /*
   * Pushes an element, returns false if the segment is full
   */
  boolean push(Buffer b) {
    if (tailOffset == buffers.length - 1)
      return false;

    buffers[++tailOffset] = b;

    return true;
  }

  /*
   * Resets the segment to a clean state
   */
  void reset() {
    // Null out the array
    while (headOffset < tailOffset)
      buffers[++headOffset] = null;

    headOffset = -1;
    tailOffset = -1;
    next = null;
  }

}
