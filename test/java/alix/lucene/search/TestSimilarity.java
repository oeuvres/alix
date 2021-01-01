package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import alix.lucene.Alix;
import alix.lucene.TestAlix;
import alix.lucene.analysis.FrAnalyzer;

public class TestSimilarity
{
  public static void main(String args[]) throws IOException
  {
    Alix alix = TestAlix.miniBase(new WhitespaceAnalyzer());
    TestAlix.write(alix, new String[] {
      "C C A", 
      "A A A B", 
      "B A A",
      "C B B", 
      "A B A A",
      "B B B",
    });
    IndexSearcher searcher = alix.searcher();
    searcher.setSimilarity(new SimilarityChi2());
    Query query = alix.query("text", "B");
    TopDocs topDocs = searcher.search(query, 100);
    ScoreDoc[] hits = topDocs.scoreDocs;
    for (ScoreDoc doc: hits) {
      System.out.println(doc.score +" — " + alix.reader().document(doc.doc));
    }
  }
}
