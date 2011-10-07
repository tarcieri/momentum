package picard.http;

import java.nio.ByteBuffer;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

public class WsFrameEncoder {

    // The type of the WS Frame
    private final WsFrame frame;

    public WsFrameEncoder(WsFrame frame) {
        if (frame == null) {
            throw new NullPointerException("frame is null");
        }

        this.frame = frame;
    }

    public ChannelBuffer encode(ChannelBuffer data) {
        return encode(data.toByteBuffer());
    }

    public ChannelBuffer encode(ByteBuffer data) {
        int length     = data.remaining();
        ByteBuffer buf = ByteBuffer.allocate(16);

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

        return ChannelBuffers.wrappedBuffer(buf, data);
    }

    private int opCode() {
        switch (frame.type()) {
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
