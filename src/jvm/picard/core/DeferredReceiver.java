package picard.core;

public interface DeferredReceiver {
  /*
   * Invoked with the realized value.
   */
  void success(Object val);

  /*
   * Invoked with the aborted error
   */
  void error(Exception err);
}
