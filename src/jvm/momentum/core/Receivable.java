package momentum.core;

import clojure.lang.IPending;

public interface Receivable extends IPending {

  /*
   * Register a callback.
   */
  void receive(Receiver r);
}
