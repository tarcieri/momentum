package picard.http;

import org.jboss.netty.buffer.ChannelBuffer;

public class WsFrame {

    public static int FIN_MASK    = 0x80;
    public static int LOWER_SEVEN = 0x7F;

    protected WsFrameType type;
    protected boolean isMasked;
    protected boolean isFinal;
    protected int maskingKey;
    protected long length;
    protected ChannelBuffer payload;

    public WsFrame() {
        this(null, false, true);
    }

    public WsFrame(WsFrameType type) {
        this(type, false, true);
    }

    public WsFrame(WsFrameType type, boolean isMasked, boolean isFinal) {
        this.type     = type;
        this.isMasked = isMasked;
        this.isFinal  = isFinal;
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

    public long length() {
        return length;
    }

    public long length(long val) {
        length = val;
        return val;
    }
}
