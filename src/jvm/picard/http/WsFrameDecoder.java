package picard.http;

import picard.core.Buffer;

// TODO: Maybe abstract away the framing logic
public class WsFrameDecoder {
    private enum State {
        OP_CODE,
        LENGTH,
        LENGTH_EXT,
        MASK_KEY,
        PAYLOAD,
        ERROR
    }

    private State   cs;
    private WsFrame cf;
    private long    remaining;

    public WsFrameDecoder() {
        cs = State.OP_CODE;
    }

    public Object decode(Buffer buf) {
        byte b;
        try {
            while (buf.hasRemaining()) {
                switch (cs) {
                case OP_CODE:
                    b = buf.get();

                    // Create a new frame object
                    cf = new WsFrame();

                    cf.isFinal(WsFrame.FIN_MASK == (b & WsFrame.FIN_MASK));
                    // Ignore rsv1-3 for now
                    cf.type(getType(b & 0x0F));

                    cs = State.LENGTH;

                    break;

                case LENGTH:
                    b = buf.get();

                    cf.isMasked(WsFrame.FIN_MASK == (b & WsFrame.FIN_MASK));

                    int length = b & WsFrame.LOWER_SEVEN;

                    if (length == 127) {
                        remaining = 8;
                        cs = State.LENGTH_EXT;
                    }
                    else if (length == 126) {
                        remaining = 2;
                        cs = State.LENGTH_EXT;
                    }
                    else {
                        cf.length = length;

                        if (cf.isMasked()) {
                            remaining = 4;
                            cs = State.MASK_KEY;
                        }
                        else {
                            remaining = cf.length;
                            cs = State.PAYLOAD;
                        }
                    }

                    break;

                case LENGTH_EXT:
                    --remaining;

                    cf.length *= 10;
                    cf.length += buf.get() & 0xFF;

                    if (remaining == 0) {
                        if (cf.isMasked()) {
                            remaining = 4;
                            cs = State.MASK_KEY;
                        }
                        else {
                            remaining = cf.length;
                            cs = State.PAYLOAD;
                        }
                    }

                    break;

                case MASK_KEY:
                    --remaining;

                    cf.maskingKey = cf.maskingKey << 8;
                    cf.maskingKey |= buf.get() & 0xFF;

                    if (remaining == 0) {
                        remaining = cf.length;
                        cs = State.PAYLOAD;
                    }

                    break;

                case PAYLOAD:
                    break;

                case ERROR:
                    throw new RuntimeException("The WS stream is invalid");
                }
            }

            return null;
        }
        catch (RuntimeException err) {
            this.cs = State.ERROR;
            throw err;
        }
    }

    private WsFrameType getType(int opcode) {
        switch (opcode) {
        case 0x00:
            return WsFrameType.CONTINUATION;

        case 0x01:
            return WsFrameType.TEXT;

        case 0x02:
            return WsFrameType.BINARY;

        case 0x08:
            return WsFrameType.CLOSE;

        case 0x09:
            return WsFrameType.PING;

        case 0x0A:
            return WsFrameType.PONG;

        default:
            throw new RuntimeException("Unknown type: " + opcode);

        }
    }
}
