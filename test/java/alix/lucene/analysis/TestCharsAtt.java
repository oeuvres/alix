package alix.lucene.analysis;

import java.io.IOException;

import alix.lucene.analysis.tokenattributes.CharsAtt;

public class TestCharsAtt
{
  public static void main(String[] args) throws IOException
  {
    CharsAtt test = new CharsAtt("états-unis");
    System.out.println(test.capitalize());
    System.out.println(test.endsWith("nis"));
  }
}
