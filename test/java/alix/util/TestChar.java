package alix.util;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;

public class TestChar
{
  public static void props()
  {
    String test = "^1Aa  😀();-,_.;!? ■A\n°^�&-.6<Œ" + (char) 0xAD;
    for (int i = 0, n = test.length(); i < n; i++) {
      char c = test.charAt(i);
      System.out.println(Char.toString(c));
    }
  }
  
  static void zola() throws URISyntaxException, UnsupportedEncodingException, IOException
  {
    long time;
    int found;
    String projectRoot = new File(".").getAbsolutePath();
    Path path = Paths.get(projectRoot+"/test/res/zola.txt");
    String zola = new String( Files.readAllBytes(path), "UTF-8");
    final int len = zola.length();
    System.out.println("zola.txt "+ len / 10000 / 100.0+" millions de caractères.");
    char[] chars = zola.toCharArray();
    for (int loop = 10; loop > 0 ; loop--) {
      time = System.nanoTime();
      char print = 0;
      for (int i = 0; i < len; i++) {
        print += chars[i];
      }
      System.out.println("Boucler sur tous les caractères " + ((System.nanoTime() - time) / 1000000) + " ms.\n");
      
      time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        final char c = chars[i];
        if (Character.isWhitespace(c)) found++;
      }
      System.out.println("java.lang.Character.isWhitespace() " + ((System.nanoTime() - time) / 1000000) + " ms. "+found);

      time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        final char c = chars[i];
        if (Character.isSpaceChar(c)) found++;
      }
      System.out.println("java.lang.Character.isSpaceChar() " + ((System.nanoTime() - time) / 1000000) + " ms. "+found);

      time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        final char c = chars[i];
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) found++;
      }
      System.out.println("java.lang.Character.isWhitespace() || isSpaceChar() " + ((System.nanoTime() - time) / 1000000) + " ms. "+found);

      time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        final char c = chars[i];
        if (Char.isSpace(c)) found++;
      }
      System.out.println("alix.util.Char.isSpace() " + ((System.nanoTime() - time) / 1000000) + " ms. "+found);

      System.out.println("");
      
      time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        final char c = chars[i];
        final int type = Character.getType(c);
        if (type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
            || type == Character.END_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
            || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION
            || type == Character.START_PUNCTUATION) found++;
      }
      System.out.println("java.lang.Character, punctuation types, if " + ((System.nanoTime() - time) / 1000000) + " ms. "+found);

      time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        final char c = chars[i];
        final int type = Character.getType(c);
        switch(type) {
          case Character.CONNECTOR_PUNCTUATION:
          case Character.DASH_PUNCTUATION:
          case Character.END_PUNCTUATION:
          case Character.FINAL_QUOTE_PUNCTUATION:
          case Character.INITIAL_QUOTE_PUNCTUATION:
          case Character.OTHER_PUNCTUATION:
          case Character.START_PUNCTUATION:
            found++;
        }
      }
      System.out.println("java.lang.Character, punctuation types, switch " + ((System.nanoTime() - time) / 1000000) + " ms. "+found);

      
      
      time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        final char c = chars[i];
        if (Char.isPunctuation(c)) found++;
      }
      System.out.println("alix.util.Char.isPunctuation() " + ((System.nanoTime() - time) / 1000000) + " ms. "+found);


      time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        final char c = chars[i];
        if (Character.isLetter(c)) found++;
      }
      System.out.println("java.lang.Character.isLetter() " + ((System.nanoTime() - time) / 1000000) + " ms. "+found);

      
      time = System.nanoTime();
      found = 0;
      for (int i = 0; i < len; i++) {
        final char c = chars[i];
        if (Char.isLetter(c)) found++;
      }
      System.out.println("alix.util.Char.isLetter() " + ((System.nanoTime() - time) / 1000000) + " ms. "+found);

      System.out.println("\n-----");
}
  }
  
  public static void bitset()
  {
    // BitSet is less efficient than array
    long time = System.nanoTime();
    
    BitSet filter = new BitSet(Char.SIZE);
    for (int i = 0; i < Char.SIZE; i++) {
      filter.set(i, (Char.CHARS[i] & (Char.SPACE | Char.PUNCTUATION)) > 0);
    }
    System.out.println("Load filter " + ((System.nanoTime() - time) / 1000000) + " ms.");
    int tot = 0;

    for (int j = 0; j < 10; j++) {
      tot = 0;
      time = System.nanoTime();
      for (int i = 0; i < 100; i++) {
        for (char c = 0; c < 65535; c++) {
          if (filter.get(c)) tot++;
        }
      }
      System.out.println("Filter "+tot+" true in " + ((System.nanoTime() - time) / 1000000) + " ms.");
      
      tot = 0;
      time = System.nanoTime();
      for (int i = 0; i < 100; i++) {
        for (char c = 0; c < 65535; c++) {
          if (Char.isSpace(c) || Char.isPunctuation(c)) tot++;
        }
      }
      System.out.println("Array "+tot+" true in " + ((System.nanoTime() - time) / 1000000) + " ms.");
    }
  }
  /**
   * Testing
   */
  public static void main(String args[]) throws Exception
  {
    // props();
    // System.out.println(Character.isWhitespace(' '));
    // System.out.println(Character.isSpaceChar(' '));
    zola();
  }

}
