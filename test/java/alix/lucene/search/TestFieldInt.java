package alix.lucene.search;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import alix.lucene.Alix;
import alix.lucene.TestIndex;
import alix.lucene.search.FieldInt.IntEnum;

public class TestFieldInt
{
  static Logger LOGGER = Logger.getLogger(TestFieldInt.class.getName());

  public static void main(String[] args) throws IOException, InterruptedException 
  {
    long time = System.nanoTime(); 
    Alix alix = TestIndex.index();
    LOGGER.log(Level.INFO, "time: " + (System.nanoTime() - time) / 1000000.0 + "ms");
    time = System.nanoTime(); 
    FieldInt fint = new FieldInt(alix, TestIndex.INT, TestIndex.TEXT);
    LOGGER.log(Level.INFO, "FieldInt duration: " + (System.nanoTime() - time) / 1000000.0 + "ms");
    LOGGER.log(Level.INFO, "card="+fint.card() +" min="+fint.min()+" max="+fint.max());
    IntEnum it = fint.iterator();
    while (it.hasNext()) {
      it.next();
      LOGGER.log(Level.INFO, "value=" + it.value() + " docs=" + it.docs() + " occs=" + it.occs());
    }
  }
}
