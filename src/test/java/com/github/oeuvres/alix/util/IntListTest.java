package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class IntListTest {

    static Random RANDOM = new Random();
    static int SIZE = 1000;
    static final int MAXFORM = 25000;
    static final int LOOPS = 10;
    static final int OPS = 1000;


    public void uniq()
    {
        int[] uniq;
        int[] src1 = null;
        uniq = IntList.uniq(src1);
        assertNull(""+Arrays.toString(uniq) ,uniq);
        int[] src2 = {};
        uniq = IntList.uniq(src2);
        assertArrayEquals(""+Arrays.toString(uniq) + "≠" + Arrays.toString(src2), src2, uniq);
        int[] src3 = {4};
        uniq = IntList.uniq(src3);
        assertArrayEquals(""+Arrays.toString(uniq) + "≠" + Arrays.toString(src3), src3, uniq);
        int[] src4 = {1,2,3};
        uniq = IntList.uniq(src4);
        assertArrayEquals(""+Arrays.toString(uniq) + "≠" + Arrays.toString(src4), src4, uniq);
        int[] src5 = {3,2,1};
        int[] exp5 = {1,2,3};
        uniq = IntList.uniq(src5);
        assertArrayEquals(""+Arrays.toString(uniq) + "≠" + Arrays.toString(exp5), exp5, uniq);
        int[] src6 = {3,2,1,3,3,2,2,1};
        int[] exp6 = {1,2,3};
        uniq = IntList.uniq(src6);
        assertArrayEquals(""+Arrays.toString(uniq) + "≠" + Arrays.toString(exp6), exp6, uniq);
    }

    static public void ints()
    {
        System.out.println("IntList");
        IntList ints = new IntList();
        for (int loop = 0; loop < LOOPS; loop++) {
            ints.clear();
            long start = System.nanoTime();
            for (int pos = 0; pos < SIZE; pos++) {
                ints.push(RANDOM.nextInt(MAXFORM));
            }
            double laps = ((double)(System.nanoTime() - start) / 1000000);
            System.out.print("  loading=" + laps + " ms. ");
            for (int op = 0; op < OPS; op++) {
                final int pos = RANDOM.nextInt(SIZE);
                ints.inc(pos);
            }
            laps = ((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  inc=" + laps + " ms. ");
        }
        System.out.println();
    }

    static public void arrayList()
    {
        System.out.println("ArrayList");
        List<Integer> ints = new ArrayList<>();
        for (int loop = 0; loop < LOOPS; loop++) {
            ints.clear();
            long start = System.nanoTime();
            for (int pos = 0; pos < SIZE; pos++) {
                ints.add(RANDOM.nextInt(MAXFORM));
            }
            double laps = ((double)(System.nanoTime() - start) / 1000000);
            System.out.print("  loading=" + laps + " ms. ");
            for (int op = 0; op < OPS; op++) {
                final int pos = RANDOM.nextInt(SIZE);
                ints.add(pos, ints.get(pos) + 1);
            }
            laps = ((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  inc=" + laps + " ms. ");
        }
        System.out.println();
    }

    public static void main(String[] args)
    {
        ints();
        arrayList();
    }
}

