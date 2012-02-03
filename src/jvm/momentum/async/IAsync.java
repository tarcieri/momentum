package momentum.async;

import clojure.lang.*;

public interface IAsync extends IPending, IDeref, IBlockingDeref {

  public boolean isSuccessful();

  public boolean isAborted();

  public boolean put(Object val);

  public boolean abort(Exception e);

  public void receive(Receiver r);

  boolean observe();

  Object val();

  Exception err();

}
