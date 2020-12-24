package alix.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.Query;

import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.TermList;
import alix.lucene.util.Cooc;
import alix.util.Dir;

public class TestAlix
{
  static Path path = Paths.get("/tmp/alix/test");
  static public final String fieldName = "text";
  public static Alix miniBase(Analyzer analyzer) throws IOException
  {
    if (analyzer == null) analyzer = new WhitespaceAnalyzer();
    Dir.rm(path);
    Alix alix = Alix.instance(path, analyzer);
    return alix;
  }
  
  static public void write(Alix alix, String[] corpus) throws IOException
  {
    IndexWriter writer = alix.writer();
    Field field = new Field(fieldName, "", Alix.ftypeText);
    Document doc = new Document();
    doc.add(field);
    int docId = 0;
    for (String text: corpus) {
      field.setStringValue(text);
      writer.addDocument(doc);
      System.out.println("add(docId=" + docId + ")   {" + text + "}");
      docId++;
    }
    writer.commit();
    writer.close();
  }

  public static void qparse() throws IOException
  {
    Analyzer analyzer = new FrAnalyzer();
    final String field = "text";
    String q =  "+maintenant -loin Littré, +demain; -hier";
    // q = "Littré";
    Query query = Alix.qParse(field, q, analyzer);
    System.out.println(query);
    // TermList terms = Alix.qTermList(field, q, analyzer);
    // System.out.println(terms);
  }
  public static void main(String args[]) throws Exception
  {
    qparse();
  }

}
