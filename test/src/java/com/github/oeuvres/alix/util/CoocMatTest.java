package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

public class CoocMatTest
{

    @Test
    public void record()
    {
        final int nodeLen = 8;
        BitSet nodes = new FixedBitSet(nodeLen * 2);
        for (int i = 0; i < nodeLen; i++) nodes.set(i * 2);
        CoocMat mat = new CoocMat(nodes);
        Random random = new Random();
        int events = 512;
        while (--events > 0) {
            final int xId = random.nextInt(nodeLen) * 2;
            final int yId = random.nextInt(nodeLen) * 2;
            System.out.println("(" + xId + "," + yId + ")");
            mat.inc(xId, yId);
        }
        System.out.println(mat);
    }

}
