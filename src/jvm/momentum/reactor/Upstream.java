package momentum.reactor;

import java.nio.channels.*;
import momentum.buffer.Buffer;

public interface Upstream {

  void sendOpen(SocketChannel ch);

  void sendMessage(Buffer message);

  void sendClose();

  void sendPause();

  void sendResume();

  void sendAbort(Exception e);

}
