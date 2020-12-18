package alix.lucene.analysis;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;

import alix.fr.Tag;
import alix.lucene.analysis.FrDics.LexEntry;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.util.WordsAutomatonBuilder;
import alix.util.Chain;
import alix.util.CsvReader;

public class TestFrDics
{
  /**
   * find the fastest way to test stop words
   * @throws IOException 
   */
  public static void stopping() throws IOException
  {
    // find the fastest way to test stop words
    ArrayList<String> words = new ArrayList<String>();
    words.add("ZOB");
    HashSet<CharsAtt> javaHash = new HashSet<CharsAtt>((int) (1000 / 0.75));

    long time;
    CsvReader csv = null;
    Reader reader;
    String res = "stop.csv";
    reader = new InputStreamReader(Tag.class.getResourceAsStream(res), StandardCharsets.UTF_8);
    csv = new CsvReader(reader, 1);
    csv.readRow(); // pass first line
    while (csv.readRow()) {
      Chain cell0 = csv.row().get(0);
      if (cell0.isEmpty() || cell0.charAt(0) == '#') continue;
      javaHash.add(new CharsAtt(cell0));
      words.add(cell0.toString());
    }
    
    CharArraySet luceneSet = new CharArraySet(words.size(), false);
    luceneSet.addAll(words);
    Automaton automaton = WordsAutomatonBuilder.buildFronStrings(words);
    CharacterRunAutomaton tester = new CharacterRunAutomaton(automaton);
    
    words.add("BLUES");
    int i, bad;
    CharsAtt att = new CharsAtt();
    int repeat = 100;
    
    for (int more=0; more < 10; more++) {
      time = System.nanoTime();
      i = bad = 0;
      for (int loop = 0; loop < repeat; loop++) {
        for (String w: words) {
          att.setEmpty().append(w);
          if (!javaHash.contains(att)) bad++;
          i++;
        }
      }
      System.out.println("JavaHash "+(System.nanoTime() - time) / 1000000.0 + "ms "+bad+"/"+i);
  
      time = System.nanoTime();
      i = bad = 0;
      for (int loop = 0; loop < repeat; loop++) {
        for (String w: words) {
          att.setEmpty().append(w);
          if (!luceneSet.contains(att)) bad++;
          i++;
        }
      }
      System.out.println("LuceneSet "+(System.nanoTime() - time) / 1000000.0 + "ms "+bad+"/"+i);
  
  
      time = System.nanoTime();
      i = bad = 0;
      for (int loop = 0; loop < repeat; loop++) {
        for (String w: words) {
          att.setEmpty().append(w);
          if (!tester.run(att.buffer(), 0, att.length())) bad++;
          i++;
        }
      }
      System.out.println("Automaton "+(System.nanoTime() - time) / 1000000.0 + "ms "+bad+"/"+i);
    }
  }

  /**
   * Java HasMap.contains(Object) use Object.equals(key)
   */
  public static void hashing()
  {
    // 
    String[] stops = {"le", "de", "maison"};
    final HashSet<CharsAtt> dic = new HashSet<CharsAtt>(10);
    for (String w: stops) {
      final CharsAtt att = new CharsAtt(w);
      dic.add(att);
      System.out.println(w+" equals="+att.equals(w));
    }
    // test Chars map with strings
    CharsAtt att = new CharsAtt();
    for (String word: stops) {
      att.setEmpty().append(word);
      System.out.print(word);
      System.out.print(" stopAtt="+dic.contains(att));
      System.out.print(" stopString="+dic.contains(word));
      System.out.println(" equals="+att.equals(word)+" hashAtt="+att.hashCode()+" hashString="+word.hashCode());
    }
  }

  public static void automat() throws UnsupportedEncodingException
  {
    String[] words = {"le", "de", "maison"};
    for (String w: words) {
      byte[] b = w.getBytes("UTF-8");
      boolean test = FrDics.STOP_BYTES.run(b, 0, b.length);
      System.out.print(w);
      System.out.println(" test="+test);
    }
  }
  
  public static void compounds()
  {
    
    CharsAtt key = new CharsAtt();
    for (String word: new String[] {"chemin", "douzième", "douzième siècle", "chemin de", "rien", "d'abord", "d'ailleurs", "chemin de fer", "chemin de fer d'intérêt local", "moustérien"}) {
      System.out.print(word);
      key.setEmpty().append(word);
      LexEntry entry = FrDics.COMPOUND.get(key);
      if (entry != null) {
        System.out.print(" "+Tag.label(entry.tag));
        if ( entry.isBranch() ) System.out.print(" BRANCH");
        if ( entry.isLeaf() ) System.out.print(" LEAF");
      }
      System.out.println();
    }
  }
  
  public static void compoundsDic()
  {
    HashMap<CharsAtt, LexEntry> compounds = new HashMap<CharsAtt, LexEntry>();
    String entries = "GRAPH;CAT\n"
     + "chemin de fer;SUB\n"
     + "douzième siècle;SUB;12e siècle\n"
     + "chemin de fer d'intérêt local;SUB\n"
     + "chemin vicinal;SUB\n"
    ;
    FrDics.tree(new StringReader(entries), compounds);
    System.out.println(compounds);
  }
  
  public static void main(String[] args) throws IOException
  {
    // hashing();
    compoundsDic();
    compounds();
  }

}
