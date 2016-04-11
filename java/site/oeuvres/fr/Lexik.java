package site.oeuvres.fr;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Preloaded list of words
 * @author user
 *
 */
public class Lexik
{

  /** French names on which keep Capitalization */
  public static final HashSet<String> NAMES;
  /** French stopwords */
  public static final HashSet<String> STOPLIST;
  /** French common words at start of sentences, render to lower case */
  public static final HashSet<String> LC;
  /** 130 000 types French lexicon seems not too bad for memory */
  public static final HashSet<String> WORDS;
  
  static {
    List<String> lines = null;
    InputStream res;
    res = Tokenizer.class.getResourceAsStream( "stoplist.txt" );
    lines = new BufferedReader( 
      new InputStreamReader(res, StandardCharsets.UTF_8)
    ).lines().collect(Collectors.toList());
    STOPLIST = new HashSet<String>(lines);
    res = Tokenizer.class.getResourceAsStream( "names.txt" );
    lines = new BufferedReader( 
      new InputStreamReader(res, StandardCharsets.UTF_8)
    ).lines().collect(Collectors.toList());
    NAMES = new HashSet<String>(lines);
    res = Tokenizer.class.getResourceAsStream( "words.txt" );
    lines = new BufferedReader( 
      new InputStreamReader(res, StandardCharsets.UTF_8)
    ).lines().collect(Collectors.toList());
    WORDS = new HashSet<String>(lines); 
    res = Tokenizer.class.getResourceAsStream( "lc.txt" );
    lines = new BufferedReader( 
      new InputStreamReader(res, StandardCharsets.UTF_8)
    ).lines().collect(Collectors.toList());
    LC = new HashSet<String>(lines); 

  }

}
