package picard.http;

import java.io.UnsupportedEncodingException;
import picard.core.Buffer;

public final class WsFrame {

  public static final int FIN_MASK    = 0x80;
  public static final int LOWER_SEVEN = 0x7F;

  WsFrameType type;
  boolean     isMasked;
  boolean     isFinal;
  int         maskingKey;
  Buffer      payload;

  public WsFrame() {
    this(null, false, true, null);
  }

  public WsFrame(WsFrameType type) {
    this(type, false, true, null);
  }

  public WsFrame(WsFrameType type, Buffer payload) {
    this(type, false, true, payload);
  }

  public WsFrame(WsFrameType type, boolean isFinal, Buffer payload) {
    this(type, false, isFinal, payload);
  }

  public WsFrame(WsFrameType type, boolean isMasked, boolean isFinal, Buffer payload) {
    this.type     = type;
    this.isMasked = isMasked;
    this.isFinal  = isFinal;
    this.payload  = payload;
  }

  public WsFrameType type() {
    return type;
  }

  public WsFrameType type(WsFrameType val) {
    type = val;
    return val;
  }

  public boolean isMasked() {
    return isMasked;
  }

  public boolean isMasked(boolean val) {
    isMasked = val;
    return val;
  }

  public boolean isFinal() {
    return isFinal;
  }

  public boolean isFinal(boolean val) {
    isFinal = val;
    return val;
  }

  public int maskingKey() {
    return maskingKey;
  }

  public int maskingKey(int val) {
    maskingKey = val;
    return val;
  }

  public Buffer payload() {
    return payload;
  }

  public Buffer payload(Buffer val) {
    payload = val;
    return val;
  }

  public String text() throws UnsupportedEncodingException {
    return payload.toString("UTF-8");
  }

  public Buffer encode() throws UnsupportedEncodingException {
    int length = payload.remaining();
    Buffer buf = Buffer.allocate(16);

    // Write the OPCODE and a mark the frame as final
    buf.put((byte) (opCode() | WsFrame.FIN_MASK));

    // Write the size
    if (length <= 125) {
      buf.put((byte) length);
    }
    else if (length <= 65535) {
      buf.put((byte) 126);
      buf.putShort((short) length);
    }
    else {
      buf.put((byte) 127);
      buf.putLong(length);
    }

    buf.flip();

    return Buffer.wrap(buf, payload);
  }

  private int opCode() {
    switch (type) {
    case CONTINUATION:
      return 0x00;

    case TEXT:
      return 0x01;

    case BINARY:
      return 0x02;

    case CLOSE:
      return 0x08;

    case PING:
      return 0x09;

    case PONG:
      return 0x0A;

    default:
      throw new RuntimeException("Unknown frame type");
    }
  }
}
