package picard.http;

import java.util.LinkedList;
import java.util.List;
import picard.core.Buffer;
import clojure.lang.IFn;

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

    private State        cs;
    private WsFrame      cf;
    private IFn          cb;
    private int          length;
    private int          remaining;
    private List<Buffer> chunks;
    private boolean      requireMask;
    private int          maskOffset;

    public WsFrameDecoder(boolean requireMask, IFn callback) {
        cs     = State.OP_CODE;
        cb     = callback;
        chunks = new LinkedList<Buffer>();

        this.requireMask = requireMask;
    }

    public void decode(Buffer buf) throws Exception {
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

                    boolean isMasked = WsFrame.FIN_MASK == (b & WsFrame.FIN_MASK);

                    if (requireMask && !isMasked) {
                      throw new RuntimeException("Mask is required, but none set.");
                    }

                    cf.isMasked(isMasked);

                    length = b & WsFrame.LOWER_SEVEN;

                    if (length == 127) {
                        remaining = 8;
                        length    = 0;

                        cs = State.LENGTH_EXT;
                    }
                    else if (length == 126) {
                        remaining = 2;
                        length    = 0;

                        cs = State.LENGTH_EXT;
                    }
                    else {
                        if (cf.isMasked()) {
                            remaining = 4;
                            cs = State.MASK_KEY;
                        }
                        else {
                            remaining = length;
                            cs = State.PAYLOAD;
                        }
                    }

                    break;

                case LENGTH_EXT:
                    --remaining;

                    length *= 10;
                    length += buf.get() & 0xFF;

                    if (remaining == 0) {
                        if (cf.isMasked()) {
                            remaining = 4;
                            cs = State.MASK_KEY;
                        }
                        else {
                            remaining = length;
                            cs = State.PAYLOAD;
                        }
                    }

                    break;

                case MASK_KEY:
                    --remaining;

                    cf.maskingKey = cf.maskingKey << 8;
                    cf.maskingKey |= buf.get() & 0xFF;

                    if (remaining == 0) {
                        remaining = length;
                        cs = State.PAYLOAD;
                    }

                    break;

                case PAYLOAD:
                    Buffer payload = buf.duplicate();
                    int chunkSize  = Math.min(remaining, payload.remaining());

                    buf.skip(chunkSize);
                    payload.focus(chunkSize);
                    remaining -= chunkSize;

                    chunks.add(payload);

                    // Read the payload, so invoke the callback with the frame
                    // and start everything over again
                    if (remaining == 0) {
                      payload = Buffer.wrap(chunks);

                      // Not the most efficient way to unmask, but this will do
                      // for now.
                      if (cf.isMasked) {
                        int pos  = payload.position();
                        int len  = payload.remaining();
                        int ints = pos + len - len % 4;
                        int lim  = payload.limit();
                        int mask = cf.maskingKey;

                        while (pos < ints) {
                          payload.putInt(pos, mask ^ payload.getInt(pos));
                          pos += 4;
                        }

                        if (pos < lim) {
                          payload.put(pos, (mask >>> 24 & 0xff) ^ payload.get(pos));

                          if (++pos < lim) {
                            payload.put(pos, (mask >>> 16 & 0xff) ^ payload.get(pos));

                            if (++pos < lim) {
                              payload.put(pos, (mask >>> 8 & 0xff) ^ payload.get(pos));
                            }
                          }
                        }
                      }

                      cf.payload(payload);
                      cb.invoke(cf);
                      chunks.clear();
                      cs = State.OP_CODE;
                    }

                    break;

                case ERROR:
                    throw new RuntimeException("The WS stream is invalid");
                }
            }
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
