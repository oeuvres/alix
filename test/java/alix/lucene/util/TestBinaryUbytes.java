package alix.lucene.util;

public class TestBinaryUbytes
{
  public static void show(BinaryUbytes bytes)
  {
    System.out.println("size="+bytes.size());
    for (int pos = 0, size = bytes.size(); pos < size; pos++) {
      System.out.println(""+pos+"\t"+bytes.get(pos));
    }
  }
  public static void main(String[] args) throws Exception
  {
    BinaryUbytes bytes = new BinaryUbytes();
    bytes.reset();
    for (int pos = 0; pos < 300; pos++) {
      bytes.put(pos, pos);
      if(pos == 0 || pos == 1 || pos ==2 || pos ==3 || pos ==4 || pos ==7 || pos ==8 || pos ==9) {
        show(bytes);
      }
    }
    show(bytes);
    bytes.reset();
    for (int pos = 0; pos < 10; pos++) {
      bytes.put(pos, -pos);
    }
    show(bytes);
  }


}
