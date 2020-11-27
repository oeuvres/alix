package alix.util;

import java.util.Arrays;

public class TestIntList
{

  public static void uniq()
  {
    IntList list = new IntList();
    for (int i=0; i <= 2; i++) {
      for (int j = 5; j >= -3 ; j--) {
        final int value = j + i;
        System.out.print(value+", ");
        list.push(value);
      }
    }
    System.out.println();
    System.out.println(Arrays.toString(list.data));
    System.out.println(list);
    System.out.println(Arrays.toString(list.uniq()));
  }
  
  public static void main(String[] args) throws Exception
  {
    uniq();
  }

}
