package momentum.util;

public class LinkedArrayStack<T> {

  static class Segment {

    final Object[] objs;

    int off;

    Segment next;

    Segment prev;

    Segment(int capacity) {
      objs = new Object[capacity];
      off  = -1;
    }

    Segment next() {
      if (next == null) {
        next = new Segment(objs.length);
      }

      return next;
    }
  }

  Segment current;

  public LinkedArrayStack() {
    this(1024);
  }

  public LinkedArrayStack(int ss) {
    current = new Segment(ss);
  }

  public void push(T o) {
    Segment c = current;

    while (true) {
      if (c.off < c.objs.length - 1) {
        ++c.off;
        c.objs[c.off] = o;
        return;
      }

      c = current = c.next();
    }
  }

  public T pop() {
    Segment c = current;

    while (true) {
      if (c.off >= 0) {
        @SuppressWarnings("unchecked")
        T ret = (T) c.objs[c.off];
        --c.off;
        return ret;
      }

      c = c.prev;

      if (c == null)
        return null;

      current = c;
    }
  }
}
