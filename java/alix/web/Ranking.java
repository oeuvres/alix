package alix.web;

import alix.lucene.search.*;

public enum Ranking implements Option {
  
  occs("Occurrences", "") {
    @Override
    public Specif specif() {
      return new SpecifOccs();
    }
  },
  
  g("G-test (log-likelihood)", "G = 2 Σ(Oi.ln(Oi/Ei))") {
    @Override
    public Specif specif() {
      return new SpecifG();
    }
  },
  
  bm25("BM25", "genre de tf-idf plus précis pour les documents courts") {
    @Override
    public Specif specif() {
      return new SpecifBM25();
    }
    
  },

  tfidf("tf-idf", "fréquence relative des termes par document, pondérée par la fréquence dans les autres documents") {
    @Override
    public Specif specif() {
      return new SpecifTfidf();
    }
    
  },

  chi2("Chi2", "Chi2 = Σ(Oi - Ei)²/Ei") {
    @Override
    public Specif specif() {
      return new SpecifChi2();
    }
  },


  /*

  dice("Dice", "2*m11 / (m10² + m01²)") {
    @Override
    public Specif specif() {
      return new SpecifDice();
    }
  },

  jaccard("Jaccard", "m11 / (m10 + m01 + m11)") {
    @Override
    public Specif specif() {
      return new SpecifJaccard();
    }
  },
  */

  /*
  hypergeo("Loi hypergeometrique (Lafon)") {
    @Override
    public Specif specif() {
      return new SpecifHypergeo();
    }
  },
  */

  /* pas bon 
  binomial("Loi binomiale") {
    @Override
    public Specif specif() {
      return new SpecifBinomial();
    }
  },
  */
  
  /* Bof
  
  alpha("Naturel", "") {
    @Override
    public Specif specif() {
      return null;
    }
  },
  */




  
  ;

  abstract public Specif specif();

  
  private Ranking(final String label, final String hint) {  
    this.label = label ;
    this.hint = hint ;
  }

  // Repeating myself
  final public String label;
  final public String hint;
  public String label() { return label; }
  public String hint() { return hint; }
}
