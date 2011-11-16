package momentum.async;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/*
 * Unbounded async transfer queue based on Doug Lea's code. Some of the details
 * have been tweaked to optimize for Momentum's use case.
 */
final public class AsyncTransferQueue {

  static final class Node {

    /*
     * Reference to the next node in the linked list.
     */
    final AtomicReference<Node> next;

    /*
     * Whether or not the node is matched or not
     */
    final AtomicBoolean isMatched;

    /*
     * Reference to the node's object.
     */
    final Object val;

    /*
     * AsyncVal reprsenting the transfer request.
     * initially.
     */
    final AsyncVal request;

    /*
     * Constructor for the dummy CLOSED node
     */
    Node() {
      isMatched = new AtomicBoolean(true);
      next      = new AtomicReference<Node>();
      val       = null;
      request   = null;
    }

    Node(Object o, AsyncVal req) {
      isMatched = new AtomicBoolean(false);
      next      = new AtomicReference<Node>();
      val       = o;
      request   = req;
    }

    boolean isMatched() {
      return isMatched.get();
    }

    boolean tryMatch() {
      return isMatched.compareAndSet(false, true);
    }

    boolean isData() {
      return request == null;
    }

    Node next() {
      return next.get();
    }

    boolean casNext(Node cmp, Node nxt) {
      return next.compareAndSet(cmp, nxt);
    }

    Node gasNext(Node nxt) {
      return next.getAndSet(nxt);
    }

    void realize(Object o, AsyncVal req) {
      if (request == null) {
        req.put(val);
      }
      else {
        request.put(o);
      }
    }

    void realize(Object o, Exception err) {
      if (request != null) {
        if (err != null) {
          request.abort(err);
        }
        else {
          request.put(o);
        }
      }
    }
  }

  /*
   * Represents the transfer queue being in the closed state. The head reference
   * is set to this when the transfer queue is starting to close. When the tail
   * reference is set to this, the queue is fully closed.
   */
  static final Node CLOSED = new Node();

  /*
   * Reference to the head of the transfer queue
   */
  final AtomicReference<Node> head;

  /*
   * Reference to the tail of the transfer queue
   */
  final AtomicReference<Node> tail;

  /*
   * The object to fulfill all the requests with if the transfer queue is
   * closed before all requests are fulfilled.
   */
  final Object defaultVal;

  /*
   * The exception that the transfer queue has been aborted with
   */
  Exception err;

  public AsyncTransferQueue(Object defaultVal) {
    this.head = new AtomicReference<Node>();
    this.tail = new AtomicReference<Node>();

    this.defaultVal = defaultVal;
  }

  public boolean put(Object o) {
    return transfer(o, null);
  }

  /*
   * Strategy for safely shutting stuff down is to walk the queue from the
   * head, unlink a node, then mark it as matched.
   */
  public boolean abort(Exception e) {
    if (e == null) {
      throw new NullPointerException();
    }

    return doClose(e);
  }

  public boolean close() {
    return doClose(null);
  }

  public AsyncVal take() {
    AsyncVal request = new AsyncVal();

    transfer(null, request);

    return request;
  }

  private boolean transfer(Object o, AsyncVal request) {
    boolean haveData = request == null;

    Node s = null, h, curr, n;

    retry:
    while (true) {

      curr = h = head.get();

      // Find the first unmatched node
      while (curr != null) {

        if (!curr.isMatched()) {
          // If the node types are the same, then we can't match a transfer, so
          // break out of the loop and start attempting to append to the queue.
          if (haveData == curr.isData()) {
            break;
          }

          // Try to match the node
          if (curr.tryMatch()) {

            // If the head node and the current node are not the same, then
            // head slack has passed its threshold and an attempt to move the
            // head ref forward can be made.
            if (curr != h && head.compareAndSet(h, (curr = curr.next()))) {
              while (h != curr) {
                h = h.gasNext(h);
              }
            }

            // The node has been obtained and can now can be realized.
            curr.realize(o, request);

            return true;
          }
        }

        curr = curr.next();
      }

      if (s == null) {
        s = new Node(o, request);
      }

      if (tryAppend(s, haveData)) {
        return true;
      }
      // If the append fails, either the append lost a race condition with an
      // append of a differen type or the queue was closed. In the case of a
      // race condition, restart the entire process. In the case of the queue
      // being closed, just return.
      else if (head.get() == CLOSED) {
        return false;
      }
    }
  }

  private boolean tryAppend(Node s, boolean haveData) {
    Node t = tail.get(), curr = t, next;

    while (true) {

      // If the current node is null and the queue head is null, attempt to CAS
      // our node in at the head position.
      if (curr == null && (curr = head.get()) == null) {

        // Only the head ref is updated. The tail ref will get updates as the
        // initial slack sets in. The head ref is only null when the queue is
        // first created.
        if (head.compareAndSet(null, s)) {
          return true;
        }
      }
      // If the node types do not match, then this thread lost the race, gotta
      // restart from scratch.
      else if (curr.isData() != haveData) {
        return false;
      }
      // If the current node is not last, keep traversing.
      else if ((next = curr.next()) != null) {
        // If the next node is the same as the current node, then we hit a node
        // that has been removed from the queue. This can only happen if the
        // head has passed our original read of the tail, so instead of
        // restarting from the latest version of the tail node, which could
        // very possibly be fully orphaned, we start from the latest head node.
        if (curr == next) {
          curr = null;
        }
        // Otherwise, we just keep on walkin'
        else {
          curr = next;
        }
      }
      // If the node is the CLOSED placeholder, then the queue has been closed.
      else if (curr == CLOSED) {
        // If the head ref is closed as well, then the queue is fully closed.
        curr = head.get();

        if (curr == CLOSED) {
          s.realize(defaultVal, err);
          return false;
        }
      }
      // The current node is the end of the queue, so the new node can be CASed
      // in.
      else if(!curr.casNext(null, s)) {
        // In the event of a failed CAS, just read the value and keep on walking.
        curr = curr.next();
      }
      // The CAS is successful.
      else {
        // If the current node is not the same as the original tail that was
        // read at the start of this function, then, with the new node, the
        // slack has passed 2. So, an attempt to update the tail ref is made.
        // Failed CASs are ignored since it means that the tail ref has already
        // been updated to a closer point.
        if (t != curr) {
          tail.compareAndSet(t, s);
        }

        return true;
      }
    }
  }

  boolean doClose(Exception e) {
    Node curr, n;

    // Spin until the tail ref can be CASed to closed, if the existing value of
    // tail is already closed, then just fail.
    do {
      if (CLOSED == (curr = tail.get())) {
        return false;
      }
    } while (!tail.compareAndSet(curr, CLOSED));

    err = e;

    // Hard set head to be closed, this creates a happens-before relation with
    // the err ref above. No other threads access err until head is updated.
    curr = head.getAndSet(CLOSED);

    while (curr != null) {
      n = curr.gasNext(curr);

      if (curr.tryMatch()) {
        curr.realize(defaultVal, err);
      }

      curr = n;
    }

    return true;
  }
}
