package alix.lucene.search;

import java.io.IOException;

import org.apache.lucene.index.IndexReader;
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
import alix.lucene.analysis.CharsAtt;
import alix.lucene.analysis.CharsMaps;
import alix.util.Char;
import alix.util.Top;

/**
 * Extract most significant terms of a document
 * @author fred
 *
 *
 */
public class Keywords
{
  private final String field;
  private Top<String> names;
  private Top<String> theme;
  private Top<String> happax;
  private float boostFactor = 1.0f;
  // 
  public Keywords(Alix alix, String field, int docId) throws IOException
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
    final Top<String> happax = new Top<String>(100);
    final Top<String> theme = new Top<String>(100);
    long occsAll= freqs.occsAll;
    int docsAll = freqs.docsAll;
    Scorer scorer = new ScorerBM25();
    Scorer scorerTheme = new ScorerTheme();
    Scorer scorerTfidf = new ScorerTfidf();
    scorer.setAll(occsAll, docsAll);
    scorerTheme.setAll(occsAll, docsAll);
    scorerTfidf.setAll(occsAll, docsAll);
    CharsAtt att = new CharsAtt();
    while(termit.next() != null) {
      BytesRef bytes = termit.term();
      if (!freqs.contains(bytes)) continue; // should not arrive, set a pointer
      // count 
      int termDocs = freqs.docs();
      long termOccs = freqs.length();
      scorer.weight(termOccs, termDocs); // collection level stats
      scorerTheme.weight(termOccs, termDocs); // collection level stats
      scorerTfidf.weight(termOccs, termDocs);
      int occsDoc = (int)termit.totalTermFreq();
      double score = scorer.score(occsDoc, docLen);
      String term = bytes.utf8ToString();
      
      if (termDocs < 2) {
        happax.push(score, term);
      }
      else if (Char.isUpperCase(term.charAt(0))) {
        names.push(occsDoc, term);
      }
      else {
        att.setEmpty().append(term);
        if (!CharsMaps.isStop(att))
          theme.push(scorerTheme.score(occsDoc, docLen), term);
      }
      
    }
    this.theme = theme;
    this.names = names;
    this.happax = happax;
  }

  public Top<String> names() {
    return names;
  }

  public Top<String> theme() {
    return theme;
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
    double max = top.max();
    for (Top.Entry<String> entry: top) {
      if (entry.score() <= 0) break;
      Query tq = new TermQuery(new Term(field, entry.value()));
      if (boost) {
        float factor = (float)(boostFactor * entry.score() / max);
        tq = new BoostQuery(tq, factor);
      }
      query.add(tq, BooleanClause.Occur.SHOULD);
      if (--words < 0) break;
    }
    return query.build();
  }
}
