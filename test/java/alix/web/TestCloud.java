package alix.web;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

import javax.xml.stream.XMLStreamException;

import alix.fr.Tag;
import alix.lucene.Alix;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.FieldText;
import alix.lucene.search.FormEnum;
import alix.util.DicFreq;
import alix.util.Occ;
import alix.util.DicFreq.Entry;
import alix.web.Cloud.Word;
import alix.web.Cloud.Wordclass;

public class TestCloud
{
  /**
   * Alix specific, linguistic logic to filter interesting words. TODO refactor to
   * be less language specific
   * 
   * @param words
   * @param limit
   * @param filter
   * @return
   */
  public static Cloud cloud(FormEnum results)
  {
    Cloud cloud = new Cloud();
    Wordclass sub = new Wordclass("sub", "Arial", new Color(32, 32, 128, 144), null);
    Wordclass name = new Wordclass("name", "Arial", new Color(0, 0, 0, 255), null);
    Wordclass verb = new Wordclass("verb", "Arial", new Color(255, 0, 0, 255), null);
    Wordclass adj = new Wordclass("adj", "Arial", new Color(64, 128, 64, 200), null);
    Wordclass adv = new Wordclass("adv", "Arial", new Color(32, 32, 32, 128), null);
    Wordclass word = new Wordclass("word", "Arial", new Color(32, 32, 32, 128), null);
    Wordclass wclass;
    // loop on dictionary
    results.reset();
    while (results.hasNext()) {
      results.next();
      int tag = results.tag();
      wclass = word;
      if (Tag.isSub(tag))
        wclass = sub;
      else if (Tag.isVerb(tag))
        wclass = verb;
      else if (Tag.isName(tag))
        wclass = name;
      else if (Tag.isAdj(tag))
        wclass = adj;
      else if (Tag.isAdv(tag))
        wclass = adv;
      cloud.add(new Word(results.form(), results.score(), wclass));
    }
    return cloud;
  }

  
  public static void cloud() throws IOException, XMLStreamException
  {
    String name = "test";
    Path path = Paths.get("/home/fred/code/ddrlab/WEB-INF/bases/rougemont");
    Alix alix = Alix.instance(name, path, new FrAnalyzer(), null);
    long time = System.nanoTime();
    time = System.nanoTime();
    FieldText ftext = alix.fieldText("text");
    FormEnum results = ftext.results(500, OptionCat.ALL.tags(), Ranking.bm25.specif(), null, false);
    Cloud cloud = cloud(results);
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms. pour remplir le nuage");
    time = System.nanoTime();
    cloud.doLayout();
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms. pour le positionner");
    time = System.nanoTime();
    cloud.html(new File("test.html"), "");
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms. pour html");
    time = System.nanoTime();
    cloud.png(new File("test.png"));
    System.out.println(((System.nanoTime() - time) / 1000000) + " ms. pour png");

  }
  
  /**
   * For testing
   * 
   * @throws IOException
   * @throws XMLStreamException
   */
  public static void main(String[] args) throws IOException, XMLStreamException
  {
    cloud();
  }


}
