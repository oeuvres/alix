package alix.lucene.util;

public class TestBinaryInts
{
  public static void show(BinaryInts ints)
  {
    System.out.println("size="+ints.size());
    for (int pos = 0, size = ints.size(); pos < size; pos++) {
      System.out.println(""+pos+"\t"+ints.get(pos));
    }
  }
  public static void main(String[] args) throws Exception
  {
    BinaryInts ints = new BinaryInts();
    ints.reset();
    for (int pos = 0; pos < 100; pos++) {
      ints.put(pos, pos);
      if(pos == 0 || pos == 1 || pos ==2 || pos ==3 || pos ==4 || pos ==7 || pos ==8 || pos ==9) {
        show(ints);
      }
    }
    show(ints);
    ints.reset();
    for (int pos = 0; pos < 10; pos++) {
      ints.put(pos, -pos);
    }
    show(ints);
  }


}
