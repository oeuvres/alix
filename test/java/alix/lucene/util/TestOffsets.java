package alix.lucene.util;

public class TestOffsets
{
  public static void show(Offsets offsets)
  {
    System.out.println("size="+offsets.size());
    for (int pos = 0, size = offsets.size(); pos < size; pos++) {
      System.out.println(""+pos+"\t"+offsets.getStart(pos)+"\t"+offsets.getEnd(pos));
    }
  }
  
  public static void small()
  {
    Offsets offsets = new Offsets();
    offsets.reset();
    for (int pos = 0; pos < 100; pos++) {
      offsets.put(pos, pos, -pos);
      if(pos == 0 || pos == 1 || pos ==2 || pos ==3 || pos ==4 || pos ==7 || pos ==8 || pos ==9) {
        show(offsets);
      }
    }
    show(offsets);
    offsets.reset();
    for (int pos = 0; pos < 10; pos++) {
      offsets.put(pos, -pos, pos+10000);
    }
    show(offsets);
  }
  public static void indexation()
  {
    
  }
  public static void main(String[] args) throws Exception
  {
  }


}
