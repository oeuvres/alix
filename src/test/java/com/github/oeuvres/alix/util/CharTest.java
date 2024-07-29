package com.github.oeuvres.alix.util;

import org.junit.Test;

public class CharTest
{

    @Test
    public void props()
    {
      String test = "<,°⁂^1Aa  😀();-,_.;!? ■A\n°^�&-.6<Œ" + (char) 0xAD;
      for (int i = 0, n = test.length(); i < n; i++) {
        char c = test.charAt(i);
        System.out.println(Char.toString(c));
      }
    }
}
