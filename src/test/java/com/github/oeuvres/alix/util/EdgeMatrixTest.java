package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class EdgeMatrixTest
{

    @Test
    public void record()
    {
        int[] events = {1, 2, 3, 1, 2, 5, -4, 1, 2, 1, 3, 7, 8, 1, 3};
        Map<Integer, Long> nodes = new HashMap<>();
        nodes.put(3, 3L);
        nodes.put(1, 5L);
        EdgeMatrix matrix = new EdgeMatrix(nodes, 15, false);
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
