package com.github.oeuvres.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

/**
 * A poor lemmatizer
 * @author glorieux-f
 *
 */
public class HashLem extends HashMap<String,String>
{
  /** List of grammatical  */
  /**
   * 
   */
  private static final long serialVersionUID = 1L;
  public HashLem(final Path path) throws IOException {
    // super();
    List<String> lines = Files.readAllLines( path, StandardCharsets.UTF_8 ); // 60ms
    for (String line: lines) {
      int pos = line.indexOf( ',' );
      this.put( line.substring( 0, pos ), line.substring( pos+1) );
    } // 60 ms
  }
  static public void main(String[] args) throws IOException {
    long start = System.nanoTime();
    Path context = Paths.get(HashLem.class.getClassLoader().getResource("").getPath()).getParent();
    Path dicfile = Paths.get( context.toString(), "/res/fr-lemma.csv");
    HashLem lems = new HashLem(dicfile);
    System.out.println( ((System.nanoTime() - start) / 1000000) + " ms");
    System.out.println( lems.get( "est" ) );
    System.out.println( lems.get( "l" ) );
  }
  class Word {
    String lem;
    int cat;
    float orthfreq;
    
  }
}
