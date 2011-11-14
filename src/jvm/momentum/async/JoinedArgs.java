package momentum.async;

import clojure.lang.*;
import java.util.*;

final public class JoinedArgs extends ArrayList<Object> implements Seqable {

  public JoinedArgs(Collection<Object> c) {
    super(c);
  }

  public ISeq seq() {
    return IteratorSeq.create(iterator());
  }

}
