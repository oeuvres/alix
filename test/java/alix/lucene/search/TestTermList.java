package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;

public class TestTermList
{
  public static void array() throws IOException
  {
    Path path = Paths.get("web/WEB-INF/obvil/critique");
    Alix alix = Alix.instance(path, new FrAnalyzer());
    String q = "monde science";
    TermList terms = alix.qTermList("text", q);
    System.out.println(terms);
    System.out.println(Arrays.toString(terms.toArray()));
  }
  public static void main(String args[]) throws Exception
  {
    array();
  }

}
