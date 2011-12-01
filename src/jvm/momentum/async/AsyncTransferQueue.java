package momentum.async;

import clojure.lang.*;

final public class AsyncTransferQueue implements Counted {

  static final class Node {

    /*
     * Reference to the next node in the linked list.
     */
    Node next;

    /*
     * Reference to the node's object.
     */
    final Object val;

    /*
     * AsyncVal reprsenting the transfer request.
     * initially.
     */
    final AsyncVal request;

    Node(Object o, AsyncVal req) {
      next    = null;
      val     = o;
      request = req;
    }

    boolean isData() {
      return request == null;
    }

    Node next() {
      return next;
    }

    Node next(Node val) {
      next = val;
      return val;
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
   * Represents the transfer queue being in the closed state. The tail reference
   * is set to this when the transfer queue is closed.
   */
  static final Node CLOSED = new Node(null, null);

  /*
   * The number of data items in the queue. This can be a negative number.
   */
  int count = 0;

  /*
   * Reference to the head of the queue
   */
  Node head;

  /*
   * Reference to the tail of the queue
   */
  Node tail;

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
    this.defaultVal = defaultVal;
  }

  public boolean put(Object o) {
    return transfer(o, null);
  }

  public boolean abort(Exception e) {
    if (e == null) {
      throw new NullPointerException();
    }

    return doClose(e);
  }

  public boolean isClosed() {
    synchronized (this) {
      return tail == CLOSED;
    }
  }

  public boolean close() {
    return doClose(null);
  }

  public AsyncVal take() {
    AsyncVal request = new AsyncVal();

    if (!transfer(null, request)) {
      if (err == null) {
        request.put(defaultVal);
      }
      else {
        request.abort(err);
      }
    }

    return request;
  }

  public synchronized int count() {
    return count;
  }

  private boolean transfer(Object o, AsyncVal request) {
    boolean haveData = request == null;

    Node matched;

    synchronized (this) {
      if (tail == CLOSED && (haveData || head == null)) {
        return false;
      }

      if ((matched = tryMatch(haveData)) == null) {
        append(new Node(o, request));
      }

      updateCount(haveData);
    }

    if (matched != null) {
      matched.realize(o, request);
    }

    return true;
  }

  private Node tryMatch(boolean haveData) {

    // If the queue is empty or the node types are the same, then cannot find a
    // node to match.
    if (head == null || haveData == head.isData()) {
      return null;
    }

    Node match = head;

    // If the tail is equal to the head, then there is only one item in the
    // queue.
    if (tail == match) {
      tail = head = null;
    }
    // Otherwise, just move the head forward
    else {
      head = match.next();
    }

    // Kill the node's foward pointer to make the GC's life easier.
    match.next(null);

    return match;
  }

  private void append(Node s) {
    if (tail != null) {
      tail.next(s);
      tail = s;
    }
    else {
      tail = head = s;
    }
  }

  boolean doClose(Exception e) {
    synchronized (this) {
      if (tail == CLOSED) {
        return false;
      }

      err  = e;
      tail = CLOSED;
    }

    if (head == null || head.isData()) {
      return true;
    }

    Node curr;

    while ((curr = head) != null) {
      curr.realize(defaultVal, err);
      head = curr.next();
      curr.next(null);
    }

    synchronized (this) {
      count = 0;
    }

    return true;
  }

  private void updateCount(boolean haveData) {
    if (haveData) {
      ++count;
    }
    else {
      --count;
    }
  }
}
