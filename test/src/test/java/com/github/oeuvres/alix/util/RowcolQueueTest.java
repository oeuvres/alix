package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;


import org.junit.Test;

public class RowcolQueueTest {

    @Test
    public void sort()
    {
        RowcolQueue arr = new RowcolQueue();
        arr.push(1, 1);
        arr.push(1, 2);
        arr.push(0, 0);
        arr.push(-1, -1);
        arr.push(1, 2);
        arr.push(-256, -16384);
        while (arr.hasNext()) {
            arr.next();
            System.out.println(arr.row() + "\t" + arr.col());
        }
        System.out.println("----\t----");
        arr.uniq();
        arr.reset();
        while (arr.hasNext()) {
            arr.next();
            System.out.println(arr.row() + "\t" + arr.col());
        }
    }

}
