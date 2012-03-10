package momentum.reactor;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/*
 * A Timer optimized for approximate I/O timeout scheduling.
 *
 * This timer does not precisely time the execution the scheduled TimerTask.
 * Timeouts are rounded into ticks. By default, a tick is 100ms.
 */
class ReactorTimer {

  /*
   * The reactor that owns this timer
   */
  final Reactor reactor;

  /*
   * The length of each tick in milliseconds.
   */
  final long tickDuration;

  /*
   * Wheel representing the ticks
   */
  final Timeout[] wheel;

  /*
   * Masks the currentTick to get the wheel index for the tick
   */
  final int mask;

  /*
   * The current tick count. This will be incremented each tick.
   */
  int currentTick;

  /*
   * Tick duration is in milliseconds
   */
  ReactorTimer(Reactor r, int numTicks) {
    if (numTicks <= 0)
      throw new IllegalArgumentException(
          "numTicks must be greater than 0 : " + numTicks);

    if (numTicks > 1073741824)
      throw new IllegalArgumentException(
          "numTicks must not be greater than 2^30 : " + numTicks);

    numTicks = ceilingNextPowerOfTwo(numTicks);

    wheel = new Timeout[numTicks];

    reactor      = r;
    mask         = wheel.length - 1;
    tickDuration = reactor.cluster.ticker.interval;
  }

  static final int ceilingNextPowerOfTwo(int x) {
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
  }

  void schedule(Timeout timeout, long ms) {
    if (!timeout.schedule(reactor))
      return;

    // The delay must be equal to or greater than tickDuration.
    if (ms < tickDuration)
      ms = tickDuration;

    timeout.targetTick = currentTick + (int) (ms / tickDuration);

    pushNode(timeout.targetTick & mask, timeout);
  }

  void cancel(Timeout timeout) {
    removeNode(timeout.targetTick & mask, timeout);
  }

  void tick() {
    // Increment the current tick
    int slot = ++currentTick & mask;

    Timeout curr = wheel[slot];

    while (curr != null) {
      if (curr.targetTick <= currentTick) {
        curr.expire();

        removeNode(slot, curr);
        curr = curr.next;
      }
    }
  }

  /*
   * ===== Linked list methods =====
   */
  void pushNode(int slot, Timeout node) {
    Timeout curr = wheel[slot];

    node.prev = null;
    node.next = curr;

    if (curr != null)
      curr.prev = node;

    wheel[slot] = node;
  }

  void removeNode(int slot, Timeout node) {
    if (wheel[slot] == node) {
      wheel[slot] = node.next;
    }
    else if (node.prev != null) {
      node.prev.next = node.next;
    }

    if (node.next != null) {
      node.next.prev = node.prev;
    }
  }
}
