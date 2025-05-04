package com.github.oeuvres.alix.util;


public class CharTest
{

    static public void main(String[] args)
    {
      String test = ">¶§*\"<,°⁂^1Aa  😀();-,_.;!? ■A\n°^�&-.6<Œ" + (char) 0xAD;
      for (int i = 0, n = test.length(); i < n; i++) {
        char c = test.charAt(i);
        System.out.println(Char.toString(c) + " ");
      }
    }
}
