package alix.web;

import java.io.IOException;

public class TestOption
{
  
  static public void show()
  {
    System.out.println(
      OptionMI.jaccard.options()
    );
    OptionMI fallback = OptionMI.jaccard;
    
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
