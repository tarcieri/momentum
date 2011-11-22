package momentum.async;

public interface Realizer {

  /*
   * Puts a value into an async object.
   */
  boolean put(Object val);

  /*
   * Aborts an async object.
   */
  boolean abort(Exception e);

}
