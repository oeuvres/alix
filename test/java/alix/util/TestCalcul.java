package alix.util;

import alix.maths.Calcul;

public class TestCalcul
{
  public static void main(String[] args) 
  {
    System.out.println(Calcul.roman2int("IX".toCharArray()));
    System.out.println(Calcul.roman2int("XL".toCharArray()));
    System.out.println(Calcul.roman2int("XLV".toCharArray()));
    System.out.println(Calcul.roman2int("MIX".toCharArray()));
    System.out.println(Calcul.roman2int("USA".toCharArray()));
  }
}
