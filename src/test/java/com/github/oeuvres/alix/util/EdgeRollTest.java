package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.junit.Test;

public class EdgeRollTest
{

    public void dic()
    {
        BytesRefHash dic = new BytesRefHash();
        dic.add(new BytesRef("one"));
        BytesRef bytes = dic.get(0, null);
        System.out.println(bytes.utf8ToString());
    }

    public void arrays()
    {
        int[] sorted = {1, 2, 10, 15};
        System.out.println(Arrays.binarySearch(sorted, 0));
        System.out.println(Arrays.binarySearch(sorted, 16));
        System.out.println(Arrays.binarySearch(sorted, 14));
    }

    @Test
    public void record()
    {
        int[] events = {1, 2, 2, 3, 1, 5, 6, 7, 0, 5, 1, 2, 1};
        EdgeRoller span = new EdgeRoller( new int[]{1, 2, 3}, 3);
        for (int position = 0, max = events.length; position < max; position++) {
            int formId = events[position];
            span.push(position, formId);
        }
        System.out.println(span.edges());
    }

}
