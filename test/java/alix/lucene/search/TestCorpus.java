package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;

public class TestCorpus
{
  public static void main(String args[]) throws IOException
  {
    Path path = Paths.get("web/WEB-INF/lucene");
    Alix alix = Alix.instance(path, new FrAnalyzer());
    Corpus corpus = new Corpus(alix, Alix.BOOKID, "test", null);
    corpus.add("geoffroy_cours");
    corpus.add("ghil_en-methode");
    corpus.add("UNFINDABLE");
    corpus.add("geoffroy_cours-litterature-dramatique-02");
    System.out.println(corpus.json());
  }
}
