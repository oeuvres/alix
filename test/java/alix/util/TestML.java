package alix.util;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import alix.lucene.analysis.tokenattributes.CharsAtt;

public class TestML
{
  public static void showSome()
  {
    for (String ent: new String[] {"&AElig;", "&amp;", "&x1d56b;", "BUG"}) {
      char c = ML.forChar(ent);
      if (c == 0) {
        System.out.println(ent+" introuvable");
        continue;
      }
      System.out.println(ent+" "+c);
      System.out.println(ent+" asCharsAtt "+ML.forChar(new CharsAtt(ent)));
    }
  }
  
  public static void detag()
  {
    System.out.println(ML.detag("<a href=\"#\" class=\"bibl\"><span class=\"byline\"><span class=\"persName\"><span class=\"surname\">Lamartine</span>, "
        + "Alphonse de</span>.</span> <span class=\"year\">(1864)</span> <span class=\"title\">Cours familier de littérature. XVII</span>. "
        + "\n<span class=\"pages\">pp. 153-232</span>.  « <span class=\"analytic\">XCIX<sup>e</sup> entretien.   Benvenuto Cellini (1<sup>re</sup>partie)</span> »\n" + 
        "</a>"
    ));
  }
  public static void main(String args[]) throws IOException, SQLException, ParseException
  {
    // ML.load("/home/fred/code/Alix/java/alix/util/ent.json");
    detag();
  }

}
