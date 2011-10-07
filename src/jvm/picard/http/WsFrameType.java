package picard.http;

public enum WsFrameType {
    CONTINUATION,
    TEXT,
    BINARY,
    CLOSE,
    PING,
    PONG
}
