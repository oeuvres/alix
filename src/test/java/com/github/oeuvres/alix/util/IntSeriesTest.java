package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class IntSeriesTest {

    @Test
    public void calcs()
    {
        IntSeries series = new IntSeries("test", null, 0, 0);
        
        
        series.clear();
        int[] data10 = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        IntList.shuffle(data10);
        series.push(data10);
        assertEquals("D10 ", 10, series.decile(10), 0);

        series.clear();
        int[] data1 = { 1, 2, 2, 3, 4, 7, 9 };
        IntList.shuffle(data1);
        series.push(data1);
        assertEquals("Mean ", 4, series.mean(), 0);
        assertEquals("Median ", 3, series.median(), 0);
        assertEquals("Mode ", 2, series.mode(), 0);

        series.clear();
        int[] data2 = {3, 6, 7, 8, 8, 9, 10, 13, 15, 16, 20};
        IntList.shuffle(data2);
        series.push(data2);
        assertEquals("Q1 ", 7, series.quartile(1), 0);
    }

}
