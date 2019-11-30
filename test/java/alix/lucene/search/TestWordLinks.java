package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.util.BitSet;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;

public class TestWordLinks
{
  public static void main(String args[]) throws IOException
  {
    String field = "text_rail";
    Path path = Paths.get("../obvie/WEB-INF/bases/critique");
    Alix alix = Alix.instance(path, new FrAnalyzer());
    WordLinks coocs = new WordLinks(alix, field, null, 50);
    
  }
}
