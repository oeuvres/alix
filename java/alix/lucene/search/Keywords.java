package alix.lucene.search;

import java.io.IOException;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;

import alix.lucene.Alix;
import alix.util.Char;
import alix.util.Top;
import alix.util.Top.Entry;

/**
 * Extract most significant terms of a docuement
 * @author fred
 *
 *
 */
public class Keywords
{
  private final String field;
  private Top<String> names;
  private Top<String> words;
  private Top<String> happax;
  private float boostFactor = 1;
  // 
  public Keywords(Alix alix, String field, int docId, Scorer scorer) throws IOException
  {
    this.field = field;
    IndexReader reader = alix.reader();
    int[] docLength = alix.docLength(field);
    Terms vector = reader.getTermVector(docId, field);
    long time = System.nanoTime();
    int docLen = docLength[docId];
    // get index term stats
    Freqs freqs = alix.freqs(field);
    // loop on all terms of the document, get score, keep the top 
    TermsEnum termit = vector.iterator();
    final Top<String> names = new Top<String>(100);
    final Top<String> words = new Top<String>(100);
    final Top<String> happax = new Top<String>(100);
    long occsAll= freqs.occsAll;
    int docsAll = freqs.docsAll;
    if (scorer == null) scorer = new ScorerBM25();
    scorer.setAll(occsAll, docsAll);
    while(termit.next() != null) {
      BytesRef bytes = termit.term();
      if (!freqs.contains(bytes)) continue; // should not arrive, set a pointer
      int termDocs = freqs.docs();
      long termOccs = freqs.length();
      scorer.weight(termOccs, termDocs); // collection level stats
      int occsDoc = (int)termit.totalTermFreq();
      float score = scorer.score(occsDoc, docLen);
      String term = bytes.utf8ToString();
      if (termDocs < 2) {
        happax.push(score, term);
      }
      else if (Char.isUpperCase(term.charAt(0))) {
        names.push(score, term);
      }
      else {
        words.push(score, term);
      }
      
    }
    this.names = names;
    this.words = words;
    this.happax = happax;
  }

  public Top<String> names() {
    return names;
  }

  public Top<String> words() {
    return words;
  }

  public Top<String> happax() {
    return happax;
  }

  /**
   * Create the More like query from a PriorityQueue
   */
  public Query query(Top<String> top, int words, boolean boost) {
    final String field = this.field;
    BooleanQuery.Builder query = new BooleanQuery.Builder();
    float max = top.max();
    for (Top.Entry<String> entry: top) {
      Query tq = new TermQuery(new Term(field, entry.value()));
      if (boost) {
        float factor = boostFactor * entry.score() / max;
        tq = new BoostQuery(tq, factor);
      }
      query.add(tq, BooleanClause.Occur.SHOULD);
      if (--words < 0) break;
    }
    return query.build();
  }
}
