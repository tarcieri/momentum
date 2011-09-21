package picard.net;

class StaleConnectionException extends RuntimeException {
    public StaleConnectionException() {
        super();
    }

    public StaleConnectionException(String msg) {
        super(msg);
    }
}
