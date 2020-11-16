package alix.lucene;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.index.IndexWriter;

import alix.lucene.Alix;
import alix.lucene.XMLIndexer;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.util.Cooc;
import alix.util.Dir;

public class TestXMLIndexer
{
  public static void main(String[] args) throws Exception
  {
    String[][] corpora = {
      // {"critique", "/var/www/html/critique/.*\\.xml"},
      // {"haine-theatre", "/var/www/html/haine-theatre/xml/.*\\.xml", "/var/www/html/haine-theatre/xml-diplo/.*\\.xml" },
      // {"mercure-galant", "/var/www/html/mercure-galant/xml/MG-.*\\.xml"},
      {"test", "/var/www/html/critique/bergson/.*\\.xml"},
    };
    long time;
    // no gain but no loss observed when more threads than processors
    int threads = Runtime.getRuntime().availableProcessors() - 1;
    // where to write indexes
    String indexes = "../obvie/WEB-INF/bases/";
    for (String[] corpus : corpora) {
      time = System.nanoTime();
      String name = corpus[0];
      Path path = Paths.get(indexes + "/" + name);
      // delete index, faster to recreate
      Dir.rm(path);
      Alix alix = Alix.instance(path, new FrAnalyzer());
      // Alix alix = Alix.instance(path, "org.apache.lucene.analysis.core.WhitespaceAnalyzer");
      IndexWriter writer = alix.writer();
      String[] globs = corpus[1].split("[ ,]+");
      XMLIndexer.index(writer, globs, SrcFormat.tei);
      // index here will be committed and merged but need to be closed to prepare
      writer.close();
      // XMLIndexer.index(writer, threads, "work/xml/.*\\.xml",
      // "/var/www/html/Teinte/xsl/alix.xsl");
      System.out.println("INDEXED in " + ((System.nanoTime() - time) / 1000000) + " ms.");
      time = System.nanoTime();
      Cooc cooc = new Cooc(alix, "text");
      cooc.write();
      System.out.println("Cooc in " + ((System.nanoTime() - time) / 1000000) + " ms.");
      System.out.println("THE END");
    }
  }
}
