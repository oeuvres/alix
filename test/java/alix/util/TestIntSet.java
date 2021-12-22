package alix.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;

import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.SparseFixedBitSet;

public class TestIntSet
{
  
  public static void findint()
  {
    final int width = 30;
    long time;
    final int length = 16384;
    final int loops = 1024;
    final int[] vector = new int[width];
    final BitSet bitset = new BitSet(length);
    final boolean[] flags = new boolean[length];
    final int[] ints = new int[length];
    final SparseFixedBitSet sparseBits = new SparseFixedBitSet(length);
    final FixedBitSet fixedBits = new FixedBitSet(length);
    final HashSet<Integer> hash = new HashSet<Integer>();
    final IntIntMap set = new IntIntMap();
    
    int i = 0;
    do {
      final int value = (int)(Math.random() * length);
      if (flags[value]) continue;
      vector[i] = value;
      ints[value] = 1;
      flags[value] = true;
      bitset.set(value);
      sparseBits.set(value);
      fixedBits.set(value);
      hash.add(value);
      set.add(value, 1);
    } while(++i < width);
    
    
    
    
    Arrays.sort(vector);
    time = System.nanoTime();
    int found = 0;
    for (int a = 0; a < loops; a++) {
      for(int x = 0; x < length; x++) {
        if (Arrays.binarySearch(vector, x) >= 0) found++;
      }
    }
    System.out.println(found + " found with Arrays.binarySearch() in "+((System.nanoTime() - time) / 1000000) + " ms.");
    
    time = System.nanoTime();
    found = 0;
    for (int a = 0; a < loops; a++) {
      for(int x = 0; x < length; x++) {
        if (bitset.get(x)) found++;
      }
    }
    System.out.println(found + " found with BitSet in "+((System.nanoTime() - time) / 1000000) + " ms.");
    
    time = System.nanoTime();
    found = 0;
    for (int a = 0; a < loops; a++) {
      for(int x = 0; x < length; x++) {
        if (flags[x]) found++;
      }
    }
    System.out.println(found + " found with boolean[] in "+((System.nanoTime() - time) / 1000000) + " ms.");

    time = System.nanoTime();
    found = 0;
    for (int a = 0; a < loops; a++) {
      for(int x = 0; x < length; x++) {
        if (ints[x] != 0) found++;
      }
    }
    System.out.println(found + " found with int[] in "+((System.nanoTime() - time) / 1000000) + " ms.");

    time = System.nanoTime();
    found = 0;
    for (int a = 0; a < loops; a++) {
      for(int x = 0; x < length; x++) {
        if (sparseBits.get(x)) found++;
      }
    }
    System.out.println(found + " found with lucene sparse bitset in "+((System.nanoTime() - time) / 1000000) + " ms.");

    time = System.nanoTime();
    found = 0;
    for (int a = 0; a < loops; a++) {
      for(int x = 0; x < length; x++) {
        if (fixedBits.get(x)) found++;
      }
    }
    System.out.println(found + " found with lucene fixed bitset in "+((System.nanoTime() - time) / 1000000) + " ms.");

    time = System.nanoTime();
    found = 0;
    for (int a = 0; a < loops; a++) {
      for(int x = 0; x < length; x++) {
        if (set.contains(x)) found++;
      }
    }
    System.out.println(found + " found with custom set in "+((System.nanoTime() - time) / 1000000) + " ms.");

    time = System.nanoTime();
    found = 0;
    for (int a = 0; a < loops; a++) {
      for(int x = 0; x < length; x++) {
        if (hash.contains(x)) found++;
      }
    }
    System.out.println(found + " found with java HashSet "+((System.nanoTime() - time) / 1000000) + " ms.");

  }
  

  public static void main(String[] args) throws IOException 
  {
    for (int i = 0; i < 10; i++) {
      findint();
    }
  }


}
