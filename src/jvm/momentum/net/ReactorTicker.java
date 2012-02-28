package momentum.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;

class ReactorTicker implements Runnable {

  /*
   * This will be written to the pipe. One byte represents one tick
   */
  final static ByteBuffer msg = ByteBuffer.wrap(new byte[] { 0 });

  /*
   * The interval in milliseconds between each tick
   */
  final long interval;

  final Pipe.SinkChannel[] sinks;

  final Pipe.SourceChannel[] sources;

  ReactorTicker(int count, long ms) throws IOException {
    interval = ms;

    sinks   = new Pipe.SinkChannel[count];
    sources = new Pipe.SourceChannel[count];

    for (int i = 0; i < count; ++i) {
      Pipe p = Pipe.open();

      sinks[i]   = p.sink();
      sources[i] = p.source();

      // Just to be sure... if this blocks you're doing it wrong.
      sinks[i].configureBlocking(true);
    }
  }

  public void run() {
    try {
      while (true) {
        // Sleep for the interval
        Thread.sleep(interval);

        for (int i = 0; i < sinks.length; ++i) {
          msg.clear();

          try {
            sinks[i].write(msg);
          }
          catch (IOException e) {
            // Seems bad bro...
          }
        }
      }
    }
    catch (InterruptedException e) {
      // Nothing, we be exiting
    }
  }
}
