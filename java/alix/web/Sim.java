package alix.web;

import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;

import alix.lucene.search.SimilarityChi2;
import alix.lucene.search.SimilarityChi2inv;
import alix.lucene.search.SimilarityG;
import alix.lucene.search.SimilarityGsimple;
import alix.lucene.search.SimilarityOccs;

public enum Sim implements Option {
  occs("Occurrences") {
    private Similarity similarity = new SimilarityOccs();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  g("G-Test") {
    private Similarity similarity = new SimilarityG();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  gsimple("G-Test simplifié") {
    private Similarity similarity = new SimilarityGsimple();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  chi2("Chi2") {
    private Similarity similarity = new SimilarityChi2();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  chi2inv("Chi2 inverse") {
    private Similarity similarity = new SimilarityChi2inv();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  bm25("BM25") {
    private Similarity similarity = new BM25Similarity();
    @Override
    public Similarity similarity() {
      return similarity;
    }
  },
  



  
  ;

  abstract public Similarity similarity();

  
  private Sim(final String label) {  
    this.label = label ;
  }

  // Repeating myself
  final public String label;
  public String label() { return label; }
  public String hint() { return null; }
}
