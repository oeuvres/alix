package alix.util;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.util.Scanner;


public class TestChar
{
  
  static String zola() throws URISyntaxException, UnsupportedEncodingException, IOException 
  {
    String res = "/res/zola.txt";
    Scanner scanner = new Scanner(TestChar.class.getResourceAsStream(res), "UTF-8");
    String text = scanner.useDelimiter("\\A").next();
    scanner.close();
    return text;
  }

  static interface Tester {
    public boolean test(char c);
  }
  
  static class Character_isWhiteSpace implements Tester {
    @Override
    public boolean test(char c) {
      return Character.isWhitespace(c);
    }
  }

  static class Character_isSpaceChar implements Tester {
    @Override
    public boolean test(char c) {
      return Character.isSpaceChar(c);
    }
  }

  static class Character_isWhiteOrSpaceChar implements Tester {
    @Override
    public boolean test(char c) {
      return (Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
  }

  static class Character_isTypePunctuationIf implements Tester {
    @Override
    public boolean test(char c) {
      final int type = Character.getType(c);
      return (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
      || type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
      || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION
      || type == Character.START_PUNCTUATION);
    }
  }

  static class Character_isTypePunctuationSwitch implements Tester {
    @Override
    public boolean test(char c) {
      final int type = Character.getType(c);
      switch(type) {
        case Character.CONNECTOR_PUNCTUATION:
        case Character.DASH_PUNCTUATION:
        case Character.END_PUNCTUATION:
        case Character.FINAL_QUOTE_PUNCTUATION:
        case Character.INITIAL_QUOTE_PUNCTUATION:
        case Character.OTHER_PUNCTUATION:
        case Character.START_PUNCTUATION:
          return true;
      }
      return false;
    }
  }

  static class Character_isPunOrSpace implements Tester {
    @Override
    public boolean test(char c) {
      if (Character.isISOControl(c)) return true;
      final int type = Character.getType(c);
      switch(type) {
        case Character.CONNECTOR_PUNCTUATION:
        case Character.DASH_PUNCTUATION:
        case Character.END_PUNCTUATION:
        case Character.FINAL_QUOTE_PUNCTUATION:
        case Character.INITIAL_QUOTE_PUNCTUATION:
        case Character.OTHER_PUNCTUATION:
        case Character.START_PUNCTUATION:
        case Character.SPACE_SEPARATOR:
          return true;
        default:
          return false;
      }
    }
  }

  
  static class Char_isPunctuationOrSpace implements Tester {
    @Override
    public boolean test(char c) {
      return Char.isPunctuationOrSpace(c);
    }
  }

  static Tester char_isSpace = new Char_isSpace();
  static class Char_isSpace implements Tester {
    static {
      Char.isSpace(' ');
    }
    @Override
    public boolean test(char c) {
      return Char.isSpace(c);
    }
  }

  
  static void looping(char[] chars, double baseDur, Tester tester)
  {
    DecimalFormat df2 = new DecimalFormat( "0.00" );
    long time = 0;
    final int loops = 100;
    final int delay = 5;
    final int len = chars.length;
    int found = 0;
    for (int t = 0; t < loops + delay ; t++) {
      if (t == delay) {
        time = System.nanoTime();
        found = 0;
      }
      for (int i = 0; i < len; i++) {
        if (tester.test(chars[i])) found++;
      }
    }
    double duration = (System.nanoTime() - time) / loops;
    System.out.println(tester.getClass().getSimpleName() + " " + df2.format( duration / 1000000.0) + " ms. +"+Math.round(100* (duration-baseDur)/baseDur)+"% trues="+found/ loops);
  }
  
  
  static void charIs() throws UnsupportedEncodingException, IOException, URISyntaxException
  {
    long time = 0;
    int found = 0;
    DecimalFormat df2 = new DecimalFormat( "0.00" );
    
    String zola = zola();
    final int len = zola.length();
    System.out.println("zola.txt "+ len / 10000 / 100.0+" millions de caractères.");
    char[] chars = zola.toCharArray();
    final int loops = 100;
    final int delay = 5;
    
    
    for (int t = 0; t < loops + delay ; t++) {
      if (t == delay) time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        // do something with the char or the virtual machine will skip the code
        found += chars[i];
      }
    }
    double baseDur = (System.nanoTime() -time) / loops;
    System.out.println("Boucler sur tous les caractères " + df2.format(baseDur / 1000000.0) + " ms.");

    looping(chars, baseDur, new Char_isPunctuationOrSpace());
    looping(chars, baseDur, new Character_isWhiteSpace());
    looping(chars, baseDur, new Character_isSpaceChar());
    looping(chars, baseDur, new Character_isWhiteOrSpaceChar());
    looping(chars, baseDur, new Character_isTypePunctuationIf());
    looping(chars, baseDur, new Character_isTypePunctuationSwitch());
    looping(chars, baseDur, new Character_isPunOrSpace());

    found = 0;
    for (int t = 0; t < loops + delay ; t++) {
      if (t == delay) {
        time = System.nanoTime();
        found = 0;
      }
      for (int i = 0; i < len; i++) {
        if (Char.isPunctuationOrSpace(chars[i])) found++;
      }
    }
    double duration = (System.nanoTime() - time ) / loops;
    System.out.println("Char.isPunctuationOrSpace() [inline code] " + df2.format( duration / 1000000.0) + " ms. "
        + "+"+Math.round(100* (duration-baseDur) /baseDur)+"% trues="+found / loops) ;
  }
  
  public static String tokenize(String text, Tester tester)
  {
    StringBuffer out = new StringBuffer();
    final char[] chars = text.trim().toCharArray();
    boolean inword = true;
    out.append('<');
    for(int i = 0, len = chars.length; i < len; i++) {
      final char c = chars[i];
      boolean test = tester.test(c);
      // if char is a word separator
      if (test) {
        // a word has been started, close it
        if (inword) {
          out.append("> ");
          inword = false;
        }
        // do not append more space, normalize
      }
      // char should be word 
      else {
        // no word yet started
        if (!inword) {
          inword = true;
          out.append('<');
        }
        // always append word char
        out.append(c);
      }
    }
    out.append('>');
    return out.toString();
  }
  
  public static void props()
  {
    String test = ",°⁂^1Aa  😀();-,_.;!? ■A\n°^�&-.6<Œ" + (char) 0xAD;
    for (int i = 0, n = test.length(); i < n; i++) {
      char c = test.charAt(i);
      System.out.println(Char.toString(c));
    }
  }
  /**
   * Testing
   */
  public static void main(String args[]) throws Exception
  {
    /*
    String sent = "Pour découper\nles mots, rapidement : rappelez-vous votre binaire";
    System.out.println("Character_isWhiteSpace "+tokenize(sent, new Character_isWhiteSpace()));
    System.out.println("Character_isSpaceChar "+tokenize(sent, new Character_isSpaceChar()));
    System.out.println("Character_isPunOrSpace "+tokenize(sent, new Character_isPunOrSpace()));
    System.out.println("Char_isPunctuationOrSpace "+tokenize(sent, new Char_isPunctuationOrSpace()));
    charIs();
    */
    props();
  }

}
