package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class EdgeRollTest
{

    public void test()
    {
        int[] events = {1, 2, 1, 2, 1};
        EdgeRoll span = new EdgeRoll( new int[]{1, 2}, 5);
        for (int position = 0, max = events.length; position < max; position++) {
            int formId = events[position];
            span.push(position, formId);
        }
        System.out.println(span.edges());
    }

}
