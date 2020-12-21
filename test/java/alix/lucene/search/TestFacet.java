package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;
import alix.util.Dir;

public class TestFacet
{
  static HashSet<String> FIELDS = new HashSet<String>();
  static {
    for (String w : new String[] {Alix.BOOKID, "byline", "year", "title"}) {
      FIELDS.add(w);
    }
  }
  public static void test() throws IOException
  {
    String field = "boo";
    // test base
    Path path = Paths.get("work/test");
    Dir.rm(path);
    Alix alix = Alix.instance(path, new FrAnalyzer());
    IndexWriter writer = alix.writer();
    for (String w : new String[] {"A", "B", "C"}) {
      Document doc = new Document();
      doc.add(new SortedDocValuesField(field, new BytesRef(w)));
      writer.addDocument(doc);
    }
    writer.commit();
    writer.close();
    IndexSearcher searcher = alix.searcher();
    // A SortedDocValuesField is not visible as a normal query
    Query query = new TermQuery(new Term(field, "A"));
    TopDocs top = searcher.search(query, 100);
    System.out.println(top.totalHits);
    for (ScoreDoc hit: top.scoreDocs) {
      System.out.println(hit.doc);
    }
  }
  
  public static void main(String args[]) throws Exception
  {
    Path path = Paths.get("web/WEB-INF/lucene");
    Alix alix = Alix.instance(path, new FrAnalyzer());
    FieldFacet facet = new FieldFacet(alix, "author", "text", null);
    // System.out.println(facet);
    // TopTerms terms = facet.topTerms(null, alix.qTerms("prière", "text"), null);
    TopTerms terms = facet.topTerms();
    terms.sort();
    while (terms.hasNext()) {
      terms.next();
      System.out.println(terms.label());
    }
  }
}
