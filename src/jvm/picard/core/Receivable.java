package picard.core;

public interface Receivable {

  /*
   * Register a callback.
   */
  void receive(Receiver r);
}
