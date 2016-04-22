package site.oeuvres.fr;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

/**
 * A poor lemmatizer for french
 * @author glorieux-f
 * 
 * TODO, unknown
 *
 */
public class PoorLem extends HashMap<String,String>
{
  private static final long serialVersionUID = 1L;
  /**
   * Constructor with no Path, will use an internal resource
   * @throws IOException
   */
  public PoorLem() throws IOException 
  {
    BufferedReader buf = new BufferedReader( 
      new InputStreamReader(
        Tokenizer.class.getResourceAsStream( "lemma.csv" ), 
        StandardCharsets.UTF_8
      )
    );
    String l;
    int pos;
    while ((l = buf.readLine()) != null) {
      pos = l.indexOf( ',' );
      this.put( l.substring( 0, pos ), l.substring( pos+1) );
    }
  }

  public PoorLem(final Path path) throws IOException 
  {
    // super();
    List<String> lines = Files.readAllLines( path, StandardCharsets.UTF_8 ); // 60ms    
    for (String line: lines) {
      int pos = line.indexOf( ',' );
      this.put( line.substring( 0, pos ), line.substring( pos+1) );
    } // 60 ms
  }
  
  /**
   * A get with no null return.
   * If something better known, give it, if none, give back the same.
   */
  @Override
  public String get( Object key )
  {
    String value = super.get( key );
    if ( value == null ) return (String)key;
    else return value;
  }

}
