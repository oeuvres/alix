package alix.util;

import java.io.IOException;
import java.sql.SQLException;
import java.text.ParseException;

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
    String xml = " class=\"bibl\"><span class=\"byline\"><span class=\"persName\"><span class=\"surname\">Lamartine</span>, "
        + "Alphonse de</span>.</span>\n<span class=\"year\">(1864)</span>.</p><p><span class=\"title\">Cours familier de littérature. XVII</span>. "
        + "\n<span class=\"pages\">pp. 153-232</span>.  « <span class=\"analytic\">XCIX<sup>e</sup> entretien.   Benvenuto Cellini (1<sup>re</sup>partie)</span> »\n" + 
        "</a>";
    System.out.println(ML.detag(xml));
  }

  public static void words()
  {
    String from = "<a FROM=\"blah blah\">";
    String xml =  "<a>01<a> 23\n  45<five> 67        89"+from+"\n\t\n1234 <b>5</b> 67        89";
    int pos =xml.indexOf(from) + from.length()/2;
    Chain chain = new Chain();
    ML.prependWords(xml, pos, chain, 3);
    System.out.println(chain);
    chain.append('|');
    ML.appendWords(xml, pos, chain, 3);
    System.out.println(chain);
  }
  public static void conc()
  {
    String xml = "12 3\n  45<five>6789<CENTER>\n\t\n1234<b>5</b>67        89";
    Chain chain = new Chain();
    ML.appendChars(xml, 22, chain, 11);
    System.out.println(chain);
    chain.prepend('|');
    ML.prependChars(xml, 22, chain, 11);
    System.out.println(chain);
  }

  public static void main(String args[]) throws IOException, SQLException, ParseException
  {
    detag();
  }

}
