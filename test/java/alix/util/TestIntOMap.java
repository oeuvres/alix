package alix.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import alix.deprecated.Tokenizer;

public class TestIntOMap
{
  /**
   * Testing the object performances
   * 
   * @throws IOException
   */
  public static void main(String[] args) throws IOException
  {
      // french letter in frequency order
      String letters = "easitnrulodcmpévfqgbhàjxèêyMELCzIPDAçSâJBVOTûùRôNîFœHQUGÀÉÇïkZwKWXëYÊÔŒÈüÂÎæäÆ";
      // feel a HashMap with these letters
      IntIntMap alphabet = new IntIntMap();
      for (int i = 0; i < letters.length(); i++) {
          alphabet.put(letters.charAt(i), 0);
      }
      // a big file to read
      Path context = Paths.get(Tokenizer.class.getClassLoader().getResource("").getPath()).getParent();
      Path textfile = Paths.get(context.toString(), "/Textes/zola.txt");
      String text = new String(Files.readAllBytes(textfile), StandardCharsets.UTF_8);
      char c;
      int count = 0;
      //
      long time = System.nanoTime();
      System.out.print("IntObjectMap");
      for (int i = 0; i < text.length(); i++) {
          c = text.charAt(i);
          if (alphabet.contains(c))
              count++;
      }
      System.out.println(", test " + count + " chars in " + ((System.nanoTime() - time) / 1000000) + " ms");
      time = System.nanoTime();
      count = 0;
      System.out.print("String.indexOf");
      for (int i = 0; i < text.length(); i++) {
          c = text.charAt(i);
          if (letters.indexOf(c) > -1)
              count++;
      }
      System.out.println(" test " + count + " chars in " + ((System.nanoTime() - time) / 1000000) + " ms");

  }

}
