package picard.core;

public class TimeoutException extends RuntimeException {
  public TimeoutException() {
    super();
  }

  public TimeoutException(String msg) {
    super(msg);
  }

  public TimeoutException(Throwable cause) {
    super(cause);
  }

  public TimeoutException(String msg, Throwable cause) {
    super(msg, cause);
  }
}
