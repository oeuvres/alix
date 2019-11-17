package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.lucene.analysis.Analyzer;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;

public class TestTermList
{
  static Analyzer analyzer = new FrAnalyzer();
  public static void array() throws IOException
  {
    
    TermList terms = Alix.qTermList("text", null, analyzer);
    System.out.println("Empty list"+terms);
    String q = "monde science";
    terms = Alix.qTermList("text", q, analyzer);
    System.out.println(q+"\n"+terms);
    System.out.println(Arrays.toString(terms.toArray()));
  }
  public static void main(String args[]) throws Exception
  {
    array();
  }

}
