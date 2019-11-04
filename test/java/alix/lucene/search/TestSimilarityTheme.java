package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;

public class TestSimilarityTheme
{
  public static void main(String args[]) throws IOException
  {
    Path path = Paths.get("web/WEB-INF/lucene");
    Alix alix = Alix.instance(path, new FrAnalyzer());
    IndexSearcher searcher = alix.searcher();
    searcher.setSimilarity(new SimilarityTheme());
    Query query = alix.qParse("rencontre", "text");
    TopDocs topDocs = searcher.search(query, 100);

  }
}
