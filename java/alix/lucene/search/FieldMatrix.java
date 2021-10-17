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
import java.util.logging.Logger;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import alix.lucene.Alix;
import alix.web.Distrib.Scorer;


/**
 * Stats from 
 * 
 * @author glorieux-f
 *
 */
public class FieldMatrix
{
  /** Logger */
  static Logger LOGGER = Logger.getLogger(FieldMatrix.class.getName());
  /** Reference to the state of the index */
  // private final Alix alix;
  /** Reference to a lucene reader */
  private final IndexReader reader;
  /** Name of the text field */
  private final String fieldName;
  /** Reference to  freqs for the field */
  private final FieldText fieldText;
  /** Max for docId */
  // final private int docMax;
  /** Max for formId */
  final private int formMax;


  public FieldMatrix(Alix alix, String field) throws IOException
  {
    this.reader = alix.reader();
    this.fieldName = field;
    this.fieldText = alix.fieldText(field); // build and cache the dictionary for the field
    this.formMax = fieldText.formDic.size();
    // this.docMax = alix.reader().maxDoc();
  }
  
  /**
   * Loop on all terms and all docs to calculate a vector of significant terms 
   * by document repartition only, like Chi2-test.
   * Described in Muller, 1977, p. 49
   * @throws IOException 
   * 
   */
  public double[] test(Scorer scorer, BitSet filter) throws IOException
  {
    double[] scores = new double[formMax];
    int[] docOccs = fieldText.docOccs;
    long[] formOccs = fieldText.formOccsAll;
    int[] formDocs = fieldText.formDocsAll;
    double allOccs = fieldText.occsAll;
    double allDocs = fieldText.docsAll;
    final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
    boolean hasFilter = (filter != null);
    BytesRef bytes;
    // localize some objects
    BytesRefHash dic = fieldText.formDic;
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      int docBase = context.docBase;
      Terms terms = leaf.terms(fieldName);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        if (bytes.length == 0) continue; // should not count empty position
        final int formId = dic.find(bytes);
        if (formId < 0) {
          throw new IOException("unknown terms? " + formId + ". \""+ bytes.utf8ToString() + "\". Something seems wrong in the index.");
        }
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        Bits live = leaf.getLiveDocs();
        boolean hasLive = (live != null);
        // occsAll, , 
        scorer.idf(allOccs, allDocs, formOccs[formId], formDocs[formId]);
        while ((docLeaf = docsEnum.nextDoc()) != NO_MORE_DOCS) {
          if (hasLive && !live.get(docLeaf)) continue; // deleted doc
          final int docId = docBase + docLeaf;
          if (hasFilter && !filter.get(docId)) continue;
          final int docLen = docOccs[docId];
          if (docLen < 1) continue; // empty doc, continue
          final int freq = docsEnum.freq();
          scores[formId] += scorer.tf(freq, docLen);
        }
      }
    }
    return scores;
  }
  

}
