package alix.util;

import java.util.List;

/**
 * A Cosine comparator taking different types in input 
 * Thanks  OdysseusPolymetis
 * 
 * @author frederic.glorieux@fictif.org
 *
 */
public class Cosine {
  private double dp;
  private double magnitudeA;
  private double magnitudeB;
  
   
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

  public static double magnitude(int[] vec) {
    double sum_mag = 0;
    for (int i = 0; i < vec.length; i++) {
      sum_mag = sum_mag + vec[i] * vec[i];
    }
    return (double)Math.sqrt(sum_mag);
  }

  public static double dotProduct(int[] vec1, int[] vec2) {
    double sum = 0;
    for (int i = 0; i < vec1.length; i++) {
      sum = sum + vec1[i] * vec2[i];
    }
    return sum;
  }
}
