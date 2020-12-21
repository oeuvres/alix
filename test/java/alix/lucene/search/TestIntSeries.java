package alix.lucene.search;

import java.io.IOException;

import alix.lucene.Alix;
import alix.lucene.TestIndex;

public class TestIntSeries
{
  public static void main(String[] args) throws IOException, InterruptedException 
  {
    Alix alix = TestIndex.index();
    FieldInt ints = new FieldInt(alix.reader(), TestIndex.INT);
    System.out.println("card="+ints.card() +" min="+ints.min()+" max="+ints.max()+" mean="+ints.mean());
  }
}
