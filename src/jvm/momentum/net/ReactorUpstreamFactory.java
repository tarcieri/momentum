package momentum.net;

public interface ReactorUpstreamFactory {

  ReactorUpstream getUpstream(ReactorChannelHandler downstream);

}
