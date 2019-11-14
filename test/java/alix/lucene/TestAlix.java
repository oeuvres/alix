package alix.lucene;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.Query;

import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.TermList;

public class TestAlix
{
  public static void qparse() throws IOException
  {
    Analyzer analyzer = new FrAnalyzer();
    String q =  "+maintenant +demain -hier";
    String field = "text";
    q = "Littré";
    Query query = Alix.qParse(field, q, analyzer);
    System.out.println(query);
    TermList terms = Alix.qTermList(field, q, analyzer);
    System.out.println(terms);
  }
  public static void main(String args[]) throws Exception
  {
    qparse();
  }

}
