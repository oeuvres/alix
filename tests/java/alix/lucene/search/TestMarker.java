package alix.lucene.search;

import java.io.IOException;

import alix.lucene.Alix;
import alix.lucene.analysis.MetaAnalyzer;

public class TestMarker
{
  public static void marker() throws IOException
  {
    Marker hi = new Marker(new MetaAnalyzer(), "comp* de, *chr*");
    System.out.println(hi.mark("La complémentarité des complotistes est chromoxydique de ouf."));
    System.out.println(hi.mark("La <i>compagnie</i> du Christ est de fond de court."));
  }

  public static void query() throws IOException
  {
    System.out.println(Alix.query("q", "ant* ", new MetaAnalyzer()));
  }
  public static void main(String args[]) throws Exception
  {
    query();
  }


}
