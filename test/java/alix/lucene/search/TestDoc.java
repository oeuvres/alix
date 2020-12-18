package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.ByteRunAutomaton;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.util.WordsAutomatonBuilder;
import alix.util.Dir;

public class TestDoc
{
  static String fieldName = "boo";
  public static Alix index() throws IOException
  {
    // test base
    Path path = Paths.get("work/test");
    Dir.rm(path);
    Alix alix = Alix.instance(path, new WhitespaceAnalyzer());
    IndexWriter writer = alix.writer();
    final FieldType fieldType = new FieldType();
    {
      // inverted index
      fieldType.setTokenized(true);
      fieldType.setIndexOptions(IndexOptions.DOCS);
      fieldType.setStoreTermVectors(true);
      // fieldType.setStoreTermVectorOffsets(true);
      // fieldType.setStoreTermVectorPositions(true);
      // do not store in this field
      fieldType.setStored(true);
      fieldType.freeze();
    }
    Document doc = new Document();
    final Field field = new Field(fieldName, "", fieldType);
    doc.add(field);
    for (String text : new String[] {
      "A B A", "B C B"
    }) {
      field.setStringValue(text);
      writer.addDocument(doc);
    }
    writer.commit();
    writer.close();
    return alix;
  }
  
  public static void kwic() throws IOException, NoSuchFieldException
  {
    final Alix alix = Alix.instance("/home/fred/code/ddrlab/WEB-INF/bases/rougemont", new FrAnalyzer());
    Doc doc = new Doc(alix, 228);
    System.out.println(doc.id);
    Automaton automaton = WordsAutomatonBuilder.buildFronStrings(new String[] {"état"});
    ByteRunAutomaton include = new ByteRunAutomaton(automaton);

    String[] lines = doc.kwic("text", include, "", 200, 50, 50, 1, false);
    if (lines == null) return;
    for (String l:lines) { // null is OK if noting to group
      System.out.println(l);
    }
  }
  
  public static void contrast() throws IOException, NoSuchFieldException
  {
    Alix alix = index();
    Doc doc0 = new Doc(alix , 0);
    Doc doc1 = new Doc(alix , 1);
    System.out.println(doc0.contrast(fieldName, 1));
    System.out.println(doc1.contrast(fieldName, 0));
  }

  public static void main(String args[]) throws Exception
  {
    kwic();
  }
}
