package momentum.net;

import momentum.buffer.Buffer;

public interface ReactorUpstream {

  void sendMessage(Buffer message);

}
