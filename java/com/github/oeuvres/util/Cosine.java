package com.github.oeuvres.util;

import java.util.List;

/**
 * A Cosine comparator taking different types in input 
 * Thanks  OdysseusPolymetis
 * 
 * @author glorieux-f
 *
 */
public class Cosine {
  private double dp;
  private double magnitudeA;
  private double magnitudeB;
  /**
   * Take vectors optimized as term:freq couple
   * @param vek1
   * @param vek2
   * @return the similarity score
   */
  public double similarity(IntIntMap vek1, IntIntMap vek2) {
    this.dp = dotProduct(vek1, vek2);
    this.magnitudeA = magnitude(vek1);
    this.magnitudeB = magnitude(vek2);
    return similarityScore(); 
  }
  private double magnitude(IntIntMap vek) {
    double sum = 0;
    vek.reset();
    while(vek.nextKey() != IntIntMap.NO_KEY) {
      sum += vek.currentValue() * vek.currentValue();
    }
    return (double)Math.sqrt(sum);
  }
  private double dotProduct(IntIntMap vek1, IntIntMap vek2) {
    double sum = 0;
    // vek1, loop on keys; vek2, will return 0 if key do not exists 
    int key;
    vek1.reset();
    while((key=vek1.nextKey()) != IntIntMap.NO_KEY) {
      sum += vek1.currentValue() * vek2.get(key);
    }
    return sum;
  }  
  /**
   * Compare term:freq arrays
   * @param vec1
   * @param vec2
   * @return
   */
  public double similarity(int[][] vek1, int[][] vek2) {
    // this.dp = dot_product(vek1, vek2);
    this.magnitudeA = magnitude(vek1);
    this.magnitudeB = magnitude(vek2);
    return similarityScore(); 
  }
  
  private double magnitude(int[][] vek) {
    double sum = 0;
    for ( int i = 0; i < vek.length; i++ ) {
      sum += vek[i][1] * vek[i][1];
    }
    return (double)Math.sqrt(sum);
  }
  /*
   * TODO
   */
  private double dotProduct(int[][] vek1, int[][] vek2) {
    double sum = 0;
    /*
    for (int i = 0; i < vett1.size(); i++) {
      sum = sum + vett1.get(i) * vett2.get(i);
    }
    */
    return sum;
  }
  
  private double similarityScore(){
    return (this.dp) / (this.magnitudeA * this.magnitudeB);
  }
  
  public double similarity(int[] vec1, int[] vec2) {
    this.dp = dotProduct(vec1, vec2);
    this.magnitudeA = magnitude(vec1);
    this.magnitudeB = magnitude(vec2);
    return similarityScore();
  }
  
  public double similarity(List<Integer> vett1, List<Integer> vett2){
    this.dp = dotProduct(vett1, vett2);
    this.magnitudeA = magnitude(vett1);
    this.magnitudeB = magnitude(vett2);
    return similarityScore();
  }

  private double magnitude(List<Integer> vett) {
    double sum_mag = 0;
    for (int i = 0; i < vett.size(); i++) {
      sum_mag = sum_mag + vett.get(i) * vett.get(i);
    }
    return (double)Math.sqrt(sum_mag);
  }

  private double dotProduct(List<Integer> vett1, List<Integer> vett2) {
    double sum = 0;
    for (int i = 0; i < vett1.size(); i++) {
      sum = sum + vett1.get(i) * vett2.get(i);
    }
    return sum;
  }

  private static double magnitude(int[] vec) {
    double sum_mag = 0;
    for (int i = 0; i < vec.length; i++) {
      sum_mag = sum_mag + vec[i] * vec[i];
    }
    return (double)Math.sqrt(sum_mag);
  }

  private static double dotProduct(int[] vec1, int[] vec2) {
    double sum = 0;
    for (int i = 0; i < vec1.length; i++) {
      sum = sum + vec1[i] * vec2[i];
    }
    return sum;
  }
}
