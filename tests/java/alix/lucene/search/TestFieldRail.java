package alix.lucene.search;

import java.io.IOException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;

import alix.lucene.Alix;
import alix.lucene.TestAlix;
import alix.lucene.TestIndex;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.FieldInt.IntEnum;

public class TestFieldRail
{
  static Logger LOGGER = Logger.getLogger(TestFieldRail.class.getName());
  
  static public void mini() throws IOException
  {
    String fieldName = TestAlix.fieldName;
    Alix alix = TestAlix.miniBase(new FrAnalyzer());
    TestAlix.write(alix, new String[] {
      "Un petit, coup alors ? Bon.", 
    });
    FieldRail rail = alix.fieldRail(fieldName); // get the tool for cooccurrences
  }

  public static void main(String[] args) throws IOException, InterruptedException 
  {
    Logger logger = Logger.getLogger("");
    Level level = Level.FINEST;
    logger.setLevel(level);
    for (Handler handler : logger.getHandlers()) {
      handler.setLevel(level);
    }

    mini();
  }
}
