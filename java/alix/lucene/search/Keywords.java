/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import alix.lucene.analysis.CharsMaps;
import alix.lucene.analysis.tokenattributes.CharsAtt;
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
