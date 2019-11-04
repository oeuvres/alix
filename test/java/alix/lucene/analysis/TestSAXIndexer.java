package alix.lucene.analysis;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.xml.sax.SAXException;

import alix.lucene.Alix;
import alix.lucene.SAXIndexer;
import alix.util.Dir;

public class TestSAXIndexer
{

  public static void main(String[] args) throws Exception
  {
    Path path = Paths.get("work/test/");
    Dir.rm(path);
    Alix alix = Alix.instance(path, new FrAnalyzer());
    IndexWriter writer = alix.writer();
    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setNamespaceAware(true);
    SAXParser parser = factory.newSAXParser();
    System.out.println(parser.isNamespaceAware());
    SAXIndexer handler = new SAXIndexer(writer);
    handler.setFileName("test");
    parser.parse(new File("work/docs.xml"), handler);
    writer.commit();
    // writer.forceMerge(1);
    writer.close();
    IndexReader reader = alix.reader();
    int max = reader.maxDoc();
    for (int i = 0; i < max; i++) {
      System.out.println(reader.document(i));
    }
  }
}
