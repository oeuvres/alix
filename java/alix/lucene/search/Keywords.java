package alix.lucene.search;

import java.io.IOException;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BytesRef;

import alix.lucene.Alix;
import alix.util.Top;
import alix.util.Top.Entry;

/**
 * Extract most significant terms of a docuement
 * @author fred
 *
 */
public class Keywords
{
  double ms;
  Top<String> top;
  public Keywords(Alix alix, String field, int docId, Scorer scorer) throws IOException
  {
    IndexReader reader = alix.reader();
    int[] docLength = alix.docLength(field);
    Terms vector = reader.getTermVector(docId, field);
    long time = System.nanoTime();
    int docLen = docLength[docId];
    // get index term stats
    Freqs freqs = alix.freqs(field);
    // loop on all terms of the document, get score, keep the top 
    TermsEnum termit = vector.iterator();
    final Top<String> top = new Top<String>(100);
    long occsAll= freqs.occsAll;
    int docsAll = freqs.docsAll;
    if (scorer == null) scorer = new ScorerBM25();
    scorer.setAll(occsAll, docsAll);
    while(termit.next() != null) {
      BytesRef bytes = termit.term();
      if (!freqs.contains(bytes)) continue; // should not arrive, set a pointer
      int termDocs = freqs.docs();
      // if (termDocs < 2) continue; // filter unique ?
      long termOccs = freqs.length();
      scorer.weight(termOccs, termDocs); // collection level stats
      int occsDoc = (int)termit.totalTermFreq();
      double score = scorer.score(occsDoc, docLen);
      if (!top.isInsertable(score)) continue;
      top.push(score, bytes.utf8ToString());
    }
    this.top = top;
    ms = (System.nanoTime() - time) / 1000000.0;
  }
  
  public double ms() 
  {
    return ms;
  }
  
  public Top<String> top() {
    return top;
  }
}
