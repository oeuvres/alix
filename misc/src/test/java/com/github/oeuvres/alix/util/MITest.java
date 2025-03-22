package com.github.oeuvres.alix.util;

import java.util.Arrays;

import org.junit.Test;

public class MITest
{

    @Test
    public void expobs()
    {
        final int Oab = 2;
        final int Oa = 15;
        final int Ob = 10;
        final int N = 200;
        System.out.println("Observed: " + Arrays.toString(MI.G.observed(Oab, Oa, Ob, N)));
        System.out.println("Expected: " + Arrays.toString(MI.G.expected(Oab, Oa, Ob, N)));
        System.out.println("G test: " + MI.G.score(Oab, Oa, Ob, N));
        System.out.println("Chi test: " + MI.CHI2.score(Oab, Oa, Ob, N));
        System.out.println("Jaccard test: " + MI.JACCARD.score(Oab, Oa, Ob, N));
    }
}
