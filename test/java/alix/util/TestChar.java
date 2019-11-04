package alix.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;
import java.util.Scanner;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;

import alix.fr.Tag;
import alix.lucene.analysis.MetaTokenizer;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;

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
  
  
  static String zola() throws URISyntaxException, UnsupportedEncodingException, IOException 
  {
    String res = "/res/zola.txt";
    String text = new Scanner(TestChar.class.getResourceAsStream(res), "UTF-8").useDelimiter("\\A").next();
    return text;
  }
  
  static void charIs() throws UnsupportedEncodingException, IOException, URISyntaxException
  {
    long time;
    int found;
    String zola = zola();
    final int len = zola.length();
    System.out.println("zola.txt "+ len / 10000 / 100.0+" millions de caractères.");
    char[] chars = zola.toCharArray();
    double max = 10;
    int more = 3;
    long timesum = 0;
    for (int loop = 0; loop < max + more ; loop++) {
      time = System.nanoTime();
      char print = 0;
      for (int i = 0; i < len; i++) {
        if (chars[i] == 0) break; // do something with char or the compiler will skip
      }
      if (loop >= more) timesum += System.nanoTime() - time;
      System.out.println("Boucler sur tous les caractères " + ((System.nanoTime() - time) / 1000000) + " ms. ");
    }
    
    System.out.println("Boucler sur tous les caractères " + ((timesum / 10) / 100000 / 10.0) + " ms.");
    
    for (int loop = 10; loop > 0 ; loop--) {
      
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
        if (!Char.isSpace(c)) found++;
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
    charIs();
  }

}
