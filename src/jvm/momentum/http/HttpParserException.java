package momentum.http;

public class HttpParserException extends RuntimeException {
    public HttpParserException() {
        super();
    }

    public HttpParserException(String msg) {
        super(msg);
    }

    public HttpParserException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public HttpParserException(Throwable cause) {
        super(cause);
    }
}
