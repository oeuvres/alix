package com.github.oeuvres.alix.util;


public class CharDemo
{

    static public void main(String[] args)
    {
      String test = "-6.05>¶§*\"<,°⁂^1Aa  😀();-,_.;!? ■A\n°^�&-.<Œ" + (char) 0xAD;
      for (int i = 0, n = test.length(); i < n; i++) {
        char c = test.charAt(i);
        System.out.println(Char.toString(c) + " ");
      }
    }
}
