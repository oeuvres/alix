package alix.web;

import java.io.IOException;

public class TestOption
{
  
  static public void show()
  {
    System.out.println(
      Distance.jaccard.options()
    );
    Distance fallback = Distance.jaccard;
    
    System.out.println(
      Enum.valueOf(fallback.getDeclaringClass(), "none")
      // fallback.getDeclaringClass()
    );
    
  }
  
  public static void main(String[] args) throws IOException 
  {
    show();
  }

}
