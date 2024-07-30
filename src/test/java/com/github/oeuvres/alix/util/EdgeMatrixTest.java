package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class EdgeMatrixTest
{

    @Test
    public void record()
    {
        int[] events = {1, 2, 3, 1, 2, 5, -4, 1, 2, 1, 3, 7, 8, 1, 3};
        int[] nodes = new int[]{3,1};
        EdgeMatrix matrix = new EdgeMatrix(nodes, false);
        int previous = -1;
        for (int position = 0, max = events.length; position < max; position++) {
            final int nodeValue = events[position];
            if (previous >= 0) {
                matrix.inc(previous, nodeValue);
            }
            previous = nodeValue;
        }
        System.out.println(matrix);
    }

}
