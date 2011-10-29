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
    STATUS_CODE,
    PAYLOAD,
    ERROR
  }

  /*
   * The current parser state
   */
  State cs;

  /*
   * The current websocket frame that is being populated with parsed data
   */
  WsFrame cf;

  /*
   * The callback to invoke with decoded frames
   */
  final IFn cb;

  /*
   * The frame payload length in bytes
   */
  int length;

  /*
   * The remaining number of bytes to read before the next state transition.
   */
  int remaining;

  /*
   * A list of received payload chunks
   */
  final List<Buffer> chunks;

  /*
   * What byte offset in the masking key should be used as the first byte when
   * masking the current payload chunk.
   */
  int maskOffset;

  /*
   * Whether the decoded frames require a mask. All frames originating from the
   * client require a mask
   */
  final boolean requireMask;

  public WsFrameDecoder(boolean mask, IFn callback) {
    cs          = State.OP_CODE;
    cb          = callback;
    chunks      = new LinkedList<Buffer>();
    requireMask = mask;
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

            // Reset the masking key byte offset to 0
            maskOffset = 0;

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
            else if (cf.isMasked()) {
              remaining = 4;
              cs = State.MASK_KEY;
            }
            else if (length > 0) {
              if (cf.isClose()) {
                if (length == 1) {
                  throw new RuntimeException("Invalid payload size for close frame");
                }

                remaining = 2;
                cs = State.STATUS_CODE;
              }
              else {
                remaining = length;
                cs = State.PAYLOAD;
              }
            }
            else {
              finalizeFrame();
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
              else if (cf.isClose()) {
                remaining = 2;
                cs = State.STATUS_CODE;
              }
              else {
                remaining = length;
                cs = State.PAYLOAD;
              }
            }

            break;

          case MASK_KEY:
            --remaining;

            cf.maskingKey  = cf.maskingKey << 8;
            cf.maskingKey |= buf.getUnsigned();

            if (remaining == 0) {
              if (length > 0 && cf.isClose()) {
                if (length == 1) {
                  throw new RuntimeException("Invalid payload size for close frame");
                }

                remaining = 2;
                cs = State.STATUS_CODE;
              }
              else if (length > 0) {
                remaining = length;
                cs = State.PAYLOAD;
              }
              else {
                finalizeFrame();
              }
            }

            break;

          case STATUS_CODE:
            --remaining;

            cf.statusCode  = cf.statusCode << 8;
            cf.statusCode |= buf.getUnsigned();

            if (remaining == 0) {
              if (cf.isMasked()) {
                cf.statusCode = cf.statusCode ^ (cf.maskingKey >>> 16 & 0xffff);
              }

              remaining = length - 2;

              if (remaining > 0) {
                cs = State.PAYLOAD;
              }
              else {
                finalizeFrame();
              }
            }

            break;

          // TODO: Split this state into two states, one for reading the
          // payload and one for finalizing the frame
          case PAYLOAD:
            int chunkSize = Math.min(remaining, buf.remaining());

            // Add the current chunk to the list
            chunks.add(mask(buf.slice(buf.position(), chunkSize)));

            // Skip the number of bytes
            buf.skip(chunkSize);

            // Track read bytes
            remaining -= chunkSize;

            // If there is nothing remaining, then the current frame can be sent upstream
            if (remaining == 0) {
              finalizeFrame();
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

  private void finalizeFrame() throws Exception {
    if (!chunks.isEmpty()) {
      cf.payload(Buffer.wrap(chunks));
      chunks.clear();
    }

    cb.invoke(cf);
    cs = State.OP_CODE;
  }

  private Buffer mask(Buffer buf) {
    if (!cf.isMasked) {
      return buf;
    }

    int mask = Integer.rotateLeft(cf.maskingKey, 8 * maskOffset);
    int pos  = buf.position();
    int len  = buf.remaining();

    while (len >= 4) {
      buf.putInt(pos, mask ^ buf.getInt(pos));
      pos += 4;
      len -= 4;
    }

    maskOffset = (maskOffset + len) % 4;

    if (len-- > 0) {
      buf.put(pos, (mask >>> 24 & 0xff) ^ buf.get(pos));
      ++pos;

      if (len-- > 0) {
        buf.put(pos, (mask >>> 16 & 0xff) ^ buf.get(pos));
        ++pos;

        if (len > 0) {
          buf.put(pos, (mask >>> 8 & 0xff) ^ buf.get(pos));
        }
      }
    }

    return buf;
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
