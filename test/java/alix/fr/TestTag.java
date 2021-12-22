package alix.fr;

import java.io.IOException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.FrDics;

public class TestTag
{
  /**
   * For testing
   * 
   * @throws IOException
   */
  public static void main(String[] args)
  {
    Level level = Level.FINEST;
    Logger rootLogger = LogManager.getLogManager().getLogger("");
    rootLogger.setLevel(level);
    for (Handler h : rootLogger.getHandlers()) {
      System.out.println(h);
      h.setLevel(level);
    }

    boolean[] tags = new boolean[256];
    // test if there are no collisions in code
    for (Tag tag : Tag.values()) {
      System.out.println(tag.name()+"\t"+String.format("%02x", tag.flag)+"\t"+tag.label+"\t\t"+tag.desc);
      final int flag = tag.flag;
      if (tags[flag]) System.out.println(" ——— DOUBLON !!");
      tags[flag] = true;
    }
    System.out.println("\nChargement dicos");
    System.out.println(FrDics.isStop("Con"));
    
    System.out.println("UNKNOW tag " + Tag.code("TEST"));
    TagFilter filter = new TagFilter();
    filter.setGroup(Tag.VERBppass);
    filter.setGroup(Tag.NAMEauthor);
    System.out.println(filter);
  }

}
