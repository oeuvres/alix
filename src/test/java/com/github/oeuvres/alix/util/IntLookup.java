package com.github.oeuvres.alix.util;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.SparseFixedBitSet;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;



public class IntLookup
{
    static final int maxForm = 25000;
    static final int setSize = 100;
    static final int loops = 10;
    static final int tests = 10000000;
    static Random random = new Random();
    static final long seed = maxForm * (tests - setSize);
    static final IntList set = new IntList(setSize);
    static {
        random.setSeed( (seed * seed) / loops * tests);
        for (int i=0; i < setSize; i++) {
            final int value = random.nextInt(maxForm);
            set.push(value);
        }
    }
    

    
    static void binarySearch()
    {
        System.out.print("BinarySearch");
        final int[] lookup = set.uniq();
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (Arrays.binarySearch(lookup, value) > 0) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    static void bitSet()
    {
        System.out.print("BitSet");
        final BitSet lookup = new BitSet();
        for (int i: set.uniq()) {
            lookup.set(i);
        }
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (lookup.get(value)) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    static void bool()
    {
        System.out.print("bool");
        int[] values = set.uniq();
        boolean[] lookup = new boolean[maxForm];
        for (int i: values) {
            lookup[i] = true;
        }
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (lookup[value]) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    static void fixedBitSet()
    {
        System.out.print("FixedBitSet");
        final FixedBitSet lookup = new FixedBitSet(maxForm);
        for (int i: set.uniq()) {
            lookup.set(i);
        }
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (lookup.get(value)) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    static void hashMap()
    {
        System.out.print("HashMap");
        final HashMap<Integer, Boolean> lookup = new HashMap<>();
        for (int i: set.uniq()) {
            lookup.put(i, true);
        }
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (lookup.containsKey(value)) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    static void hashSet()
    {
        System.out.print("HashSet");
        final HashSet<Integer> lookup = new HashSet<Integer>();
        for (int i: set.uniq()) {
            lookup.add(i);
        }
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (lookup.contains(value)) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    static void intArray()
    {
        System.out.print("intArray");
        int[] values = set.uniq();
        int[] lookup = new int[maxForm];
        for (int i: values) {
            lookup[i] = 1;
        }
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (lookup[value] != 0) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    static void intMap()
    {
        System.out.print("IntMap");
        final IntIntMap lookup = new IntIntMap();
        for (int i: set.uniq()) {
            lookup.add(i, i);
        }
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (lookup.contains(value)) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    static void sparseBitSet()
    {
        System.out.print("SparseBitSet");
        final SparseFixedBitSet lookup = new SparseFixedBitSet(maxForm);
        for (int i: set.uniq()) {
            lookup.set(i);
        }
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (lookup.get(value)) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    static void treeSet()
    {
        System.out.print("TreeSet");
        final TreeSet<Integer> lookup = new TreeSet<Integer>();
        for (int i: set.uniq()) {
            lookup.add(i);
        }
        for (int loop = 0; loop < loops; loop++) {
            long start = System.nanoTime();
            int found = 0;
            for (int test = 0; test < tests; test++) {
                final int value = random.nextInt(maxForm);
                if (lookup.contains(value)) found++;
            }
            final int laps = (int)((double)(System.nanoTime() - start) / 1000000);
            System.out.println("  " + laps + " ms. found=" + found);
        }
        System.out.println(GraphLayout.parseInstance(lookup).toFootprint());
        System.out.println();
    }

    public static void main(String[] args)
    {
        System.out.println(VM.current().details());
        intArray();
        bool();
        fixedBitSet();
        bitSet();
        sparseBitSet();
        hashSet();
        hashMap();
        intMap();
        binarySearch();
        treeSet();
    }
}
