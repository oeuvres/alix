package site.oeuvres.util;

/**
 * Efficient character categorizer, about 500x faster than
 * Character.is*(), optimized for tokenizer in latin scripts.
 * Taken from http://www.tomshut.de/java/index.html
 * 
 * Idea is to populate a big array of properties for the code points.
 * Memory optimization is possible for alphabetic scripts (… or ’ are in
 * 2000-206F block) Chinese is not relevant for such classes but will need a
 * test
 * 
 * For latin script language, apos and dashes are considered as word characters
 * Separation on these chars is language specific
 * 
 * @author glorieux-f
 */
public class Char
{
  static final int SIZE = 65535;
  static final short[] CHARS = new short[SIZE + 1];
  private static final short LETTER = 0x0001;
  private static final short SPACE = 0x0002;
  private static final short WORD = 0x0004;
  private static final short PUNCTUATION = 0x0008;
  private static final short PUNCTUATION_OR_SPACE = SPACE | PUNCTUATION;
  private static final short LOWERCASE = 0x0010;
  private static final short UPPERCASE = 0x0020;
  private static final short VOWEL = 0x0040;
  private static final short CONSONNANT = 0x0080;

  static {
    int type;
    // infinite loop when size = 65536
    for (char c = 0; c < SIZE; c++) {      
      short properties = 0x0;
      // hacky, hyphen maybe part of compound word, or start of a separator like ---
      if ( c == '-' ) {
        properties |= WORD;
        // properties |= PUNCTUATION;
      }
      else if (c == '\'' || c == '’' || c == '_') {
        properties |= WORD;
      }
      else if (Character.isLetter( c )) {
        properties |= WORD;
        properties |= LETTER;
        if (Character.isUpperCase( c )) {
          properties |= UPPERCASE;
        }
        if (Character.isLowerCase( c )) {
          properties |= LOWERCASE;
        }
        
      }
      else if (Character.isSpaceChar( c )) {
        properties |= SPACE; // Unicode classes, with unbreakble
      }
      else if (Character.isWhitespace( c )) {
        properties |= SPACE; // \n, \r, \t…
      }
      else {
        type = Character.getType( c );
        if (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
            || type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
            || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION
            || type == Character.START_PUNCTUATION)
          properties |= PUNCTUATION;
      }
      CHARS[c] = properties;
    }

  }

  /**
   * Is a word character (letter, but also, '’-_)
   * 
   * @see Character#isLetter(char)
   */
  public static boolean isWord( char c )
  {
    return (CHARS[c] & WORD) > 0;
  }

  /**
   * Is a letter
   * 
   * @see Character#isLetter(char)
   */
  public static boolean isLetter( char c )
  {
    return (CHARS[c] & LETTER) > 0;
  }



  /**
   * Is a lower case letter
   * 
   * @see Character#isLowerCase(char)
   */
  public static boolean isLowerCase( char c )
  {
    return (CHARS[c] & LOWERCASE) > 0;
  }

  /**
   * Is an upper case letter
   * 
   * @see Character#isUpperCase(char)
   */
  public static boolean isUpperCase( char c )
  {
    return (CHARS[c] & UPPERCASE) > 0;
  }

  /**
   * Is a punctuation mark between words
   * 
   * @param ch
   * @return
   */
  public static boolean isPunctuation( char c )
  {
    return (CHARS[c] & PUNCTUATION) > 0;
  }

  /**
   * Is a "whitespace" according to ISO (space, tabs, new lines) and also for
   * Unicode (non breakable spoaces)
   * 
   * @see Character#isSpaceChar(char)
   * @see Character#isWhiteSpace(char)
   */
  public static boolean isSpace( char c )
  {
    return (CHARS[c] & SPACE) > 0;
  }

  /**
   * Convenient method
   * 
   * @param ch
   * @return
   */
  public static boolean isPunctuationOrSpace( char c )
  {
    return (CHARS[c] & PUNCTUATION_OR_SPACE) > 0;
  }

  private Char()
  {
    // Don't
  }

  /**
   * Testing
   */
  public static void main( String args[] )
  {
    System.out.println( "_ isWord: " + Char.isWord( '_' ) );
    System.out.println( "- isWord: " + Char.isWord( '-' ) );
    System.out.println( "’ isWord: " + Char.isWord( '’' ) );
    System.out.println( "_ isPunctuation: " + Char.isPunctuation( '_' ) );
    System.out.println( "- isPunctuation: " + Char.isPunctuation( '-' ) );
    System.out.println( "Œ isUpperCase: " + Char.isUpperCase( 'Œ' ) );
    System.out.println( "à isLowerCase: " + Char.isLowerCase( 'à' ) );
    System.out.println( "&nbsp; isSpace: " + Char.isSpace( ' ' ) );
    System.out.println( "\\n isSpace: " + Char.isSpace( '\n' ) );
    System.out.println( "  isSpace: " + Char.isSpace( ' ' ) );
    System.out.println( ", isPunctuation: " + Char.isPunctuation( ',' ) );
    System.out.println( "+ isPunctuation: " + Char.isPunctuation( '+' ) );
    System.out.println( "= isPunctuation: " + Char.isPunctuation( '=' ) );
    System.out.println( "6 isPunctuationOrSpace: " + Char.isPunctuationOrSpace( '6' ) );
  }
}
