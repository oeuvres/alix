package com.github.oeuvres.alix.util;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

public class IntListTest {

    @Test
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

}
