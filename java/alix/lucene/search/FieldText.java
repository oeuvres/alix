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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
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
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.SparseFixedBitSet;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.FrDics;
import alix.lucene.analysis.FrDics.LexEntry;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.util.Char;
import alix.util.IntList;
import alix.web.OptionDistrib.Scorer;

/**
 * <p>
 * An object recording different stats for a lucene text field.
 * Is stable according to a state of the index, could be cached.
 * Record all counts useful for stats.
 * For performances, all fields of the class are visible, so it is unsafe.
 * </p>
 * <p>
 * Provide slices of stats for Terms as s sorted Iterator
 * </p>
 * @author fred
 *
 */
public class FieldText
{
  /** The reader from which to get freqs */
  final DirectoryReader reader;
  /** Name of the indexed field */
  public final String fname;
  /** Biggest formId+1 (like lucene IndexReader.maxDoc()) */
  public final int maxForm;
  /** Global number of occurrences for this field */
  public long occsAll;
  /** Global number of docs relevant for this field */
  public int docsAll;
  /** By document, count of occurrences for this field (for stats), different from docLenghth (empty positions) */
  protected final int[] docOccs;
  /** Store and populate the search and get the id */
  public final BytesRefHash formDic;
  /** By formId, count of docs, for all field */
  public int[] formDocsAll;
  /** Count of occurrences by formId */
  public final long[] formOccsAll;
  /** A tag by formId (maybe used for filtering) */
  public int[] formTag;
  /** By formId, is a stop word */
  protected BitSet formStop; 
  /** By formId, is a locution  */
  protected BitSet formLoc; 
  /** By formId is Pun */
  protected int[] formPun; 


  /**
   * Build the dictionaries and stats. Each form indexed for the field will be identified by an int (formid).
   * This id will be in freq order, so that the form with formid=1 is the most frequent for the index
   * (formId=0 is the empty string). This order allows optimizations for co-occurrences matrix,
   * 
   * 
   * @param reader
   * @param fieldName
   * @throws Exception 
   */
  public FieldText(final DirectoryReader reader, final String fieldName) throws IOException
  {
    FieldInfos fieldInfos = FieldInfos.getMergedFieldInfos(reader);
    FieldInfo info = fieldInfos.fieldInfo(fieldName);
    if (info == null) {
      throw new IllegalArgumentException("Field \"" + fieldName + "\" is not known in this index");
    }
    IndexOptions options = info.getIndexOptions();
    if (options == IndexOptions.NONE || options == IndexOptions.DOCS) {
      throw new IllegalArgumentException("Field \"" + fieldName + "\" of type " + options + " has no FREQS (see IndexOptions)");
    }
    this.reader = reader;
    /*
     Array in docId order, with the total number of tokens by doc. 
     Term vector cost 1 s. / 1000 books and is not precise. 
     Norms for similarity is not enough precise (1 byte) see SimilarityBase.computeNorm()
     https://github.com/apache/lucene-solr/blob/master/lucene/core/src/java/org/apache/lucene/search/similarities/SimilarityBase.java#L185
    */
    docOccs = new int[reader.maxDoc()];
    final FixedBitSet docs =  new FixedBitSet(reader.maxDoc()); // used to count exactly docs with more than one term
    this.fname = fieldName;
    
    
    ArrayList<FormRecord> stack = new ArrayList<FormRecord>();
    BytesRef bytes;

    // read in IndexReader.totalTermFreq(Term term)
    // «Note that, like other term measures, this measure does not take deleted documents into account.»
    // So it is more efficient to use a term iterator on all terms
    // a tmp dic used as an id provider, is needed if there are more than on leave to the index
    // is also used to remmeber the UTF8 bytes to reorder
    BytesRefHash tmpDic = new BytesRefHash();
    // loop on the index leaves to get all terms and freqs
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      int docBase = context.docBase;
      Terms terms = leaf.terms(fieldName);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator(); // org.apache.lucene.codecs.blocktree.SegmentTermsEnum
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        if (bytes.length == 0) continue; // should not count empty position
        FormRecord rec;
        int tmpId = tmpDic.add(bytes);
        // form already encountered, probabbly another leave
        if (tmpId < 0) {
          tmpId = -tmpId - 1;
          rec = stack.get(tmpId); // should be OK, but has not be tested
        }
        else {
          rec = new FormRecord(tmpId);
          stack.add(tmpId, rec);
        }
        // termLength[formId] += tenum.totalTermFreq(); // not faster if not yet cached
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        Bits live = leaf.getLiveDocs();
        boolean hasLive = (live != null);
        while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (hasLive && !live.get(docLeaf)) continue; // deleted doc
          final int docId = docBase + docLeaf;
          int freq = docsEnum.freq();
          if (freq == 0) continue; // strange, is’n it ? Will probably not arrive
          rec.docs++;
          rec.occs += freq;
          occsAll += freq;
          docOccs[docId] += freq;
          docs.set(docId);
        }
      }
    }
    this.maxForm = stack.size()+1; // should be the stack of non empty term + empty term
    // here we should have all we need to affect a freq formId
    // sort forms, and reloop on them to get optimized things
    java.util.BitSet stopRecord = new java.util.BitSet(); // record StopWords in a growable BitSet
    // temp array to get pun formId
    IntList puns = new IntList();
    formLoc = new SparseFixedBitSet(this.maxForm); // record locutions, size of BitSet will be full
    formDic = new BytesRefHash(); // populate a new hash dic with values
    formDic.add(new BytesRef("")); // add empty string as formId=0 for empty positions
    formOccsAll = new long[this.maxForm];
    formDocsAll = new int[this.maxForm];
    formTag = new int[this.maxForm];
    Collections.sort(stack); // should sort by frequences
    CharsAtt chars = new CharsAtt(); // to test against indexation dicos
    bytes = new BytesRef();
    
    
    for (FormRecord rec: stack)
    {
      tmpDic.get(rec.tmpId, bytes); // get the term
      final int formId = formDic.add(bytes); // copy it in the other dic and get its definitive id
      // if (bytes.length == 0) formId = 0; // if empty pos is counted
      formOccsAll[formId] = rec.occs;
      formDocsAll[formId] = rec.docs;
      if (FrDics.isStop(bytes)) stopRecord.set(formId);
      chars.copy(bytes); // convert utf-8 bytes to utf-16 chars
      final int indexOfSpace = chars.indexOf(' ');
      if (indexOfSpace > 0) formLoc.set(formId);
      LexEntry entry = FrDics.word(chars);
      if (entry != null) {
        formTag[formId] = entry.tag;
        continue;
      }
      entry = FrDics.name(chars);
      if (entry != null) {
        if ( entry.tag == Tag.NAMEpers.flag
            || entry.tag == Tag.NAMEpersf.flag
            || entry.tag == Tag.NAMEpersm.flag
            || entry.tag == Tag.NAMEfict.flag
            || entry.tag == Tag.NAMEauthor.flag
        ) {
          formTag[formId] = Tag.NAMEpers.flag;
          continue;
        }
        else {
          formTag[formId] = entry.tag;
          continue;
        }
      }
      if (Char.isPunctuation(chars.charAt(0))) {
        formTag[formId] = Tag.PUN.flag;
        puns.push(formId);
        continue;
      }
      if (indexOfSpace > 0) {
        chars.setLength(indexOfSpace);
        // monsieur Madeleine
        entry = FrDics.word(chars);
        if (entry != null) {
          if (entry.tag == Tag.SUBpers.flag) {
            formTag[formId] = Tag.NAMEpers.flag;
            continue;
          }
          if (entry.tag == Tag.SUBplace.flag) {
            formTag[formId] = Tag.NAMEplace.flag;
            continue;
          }
        }
        // Jean Valjean
        entry = FrDics.name(chars);
        if (entry != null) {
          if ( entry.tag == Tag.NAMEpers.flag
            || entry.tag == Tag.NAMEpersf.flag
            || entry.tag == Tag.NAMEpersm.flag
          ) {
            formTag[formId] = Tag.NAMEpers.flag;
            continue;
          }
        }
      }
      
      // if (chars.length() < 1) continue; // ?
      if (Char.isUpperCase(chars.charAt(0))) formTag[formId] = Tag.NAME.flag;
    }
    // convert a java.lang growable BitSets in fixed lucene ones
    formStop = new FixedBitSet(stopRecord.length()); // because most common words are probably stop words, the bitset maybe optimized
    for (int formId = stopRecord.nextSetBit(0); formId != -1; formId = stopRecord.nextSetBit(formId + 1)) {
      formStop.set(formId);
    }
    formPun = puns.toArray();
    Arrays.sort(formPun);
    
    // here we should be happy and set class fields
    docsAll = docs.cardinality();
  }
  
  /**
   * Total count of occurrences (except empty positions) for doc
   * @return
   */
  public int docOccs(final int docId)
  {
    return docOccs[docId];
  }

  /*
   * not very efficient way to get occs for a query
  public DocStats docStats(String[] forms, final BitSet filter) throws IOException 
  {
    if (forms == null || forms.length == 0) return null;
    int[] freqs = new int[reader.maxDoc()];
    double[] scores = new double[reader.maxDoc()];
    final boolean hasFilter = (filter != null && filter.cardinality() > 0);
    final boolean hasScorer = (scorer != null);
    final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
    // loop an all index to calculate a score for the forms
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      Bits live = leaf.getLiveDocs();
      final boolean hasLive = (live != null);
      // loop on forms as lucene term
      for (String form: forms) {
        final int formId = formId(form);
        if (hasScorer) scorer.idf(occsAll, docsAll, formOccsAll[formId], formDocsAll[formId]);
        Term term = new Term(fname, form);
        PostingsEnum postings = leaf.postings(term, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = postings.nextDoc()) != NO_MORE_DOCS) {
          if (hasLive && !live.get(docLeaf)) continue; // deleted doc
          int docId = docBase + docLeaf;
          if (hasFilter && !filter.get(docId)) continue; // document not in the filter
          int freq = postings.freq();
          if (freq < 1) throw new ArithmeticException("??? field="+fname+" docId=" + docId+" form="+form+" freq="+freq);
          freqs[docId] += freq;
          if (hasScorer) scores[docId] += scorer.tf(freq, docOccs[docId]);
          else scores[docId] += freq;
        }
      }
    }
    return new DocStats(freqs, scores);
  }
  */

  /**
   * Populate a dictionary of forms by a bitSet of documents,
   * the filter is found in FormEnum.filter
   * @throws IOException 
   */
  public FormEnum filter(FormEnum results) throws IOException
  {
    if (results.filter == null) throw new IllegalArgumentException("Doc filter missing, FormEnum.filter should be not null");; // no sense, let cry 
    BitSet filter = results.filter;
    long[] formOccsPart = new long[formDic.size()];
    int[] formDocsPart = new int[formDic.size()];
    // no doc to filter, give a a safe copy of the stats
    long occsPart = 0;
    BytesRef bytes;
    final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
    // loop an all index to calculate a score for the forms
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      int docBase = context.docBase;
      Terms terms = leaf.terms(fname);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator(); // org.apache.lucene.codecs.blocktree.SegmentTermsEnum
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        final int formId = formDic.find(bytes);
        // int docLeaf;
        // Bits live = leaf.getLiveDocs();
        // boolean hasLive = (live != null);
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        // lucene  doc says « Some implementations are considerably more efficient than a loop on all docs »
        // we should do faster here, navigating by the BitSet
        
        for (int docId = filter.nextSetBit(0); docId != NO_MORE_DOCS; docId = filter.nextSetBit(docId + 1)) {
          final int target = docId - docBase;
          if (docsEnum.advance(target) != target) continue;
          final int freq = docsEnum.freq();
          if (freq == 0) continue; // strange, is’n it ? Will probably not arrive
          formOccsPart[formId] += freq;
          formDocsPart[formId]++;
          occsPart += freq;
          
        }
      }
    }
    results.formOccsPart = formOccsPart;
    results.formDocsPart = formDocsPart;
    results.occsPart = occsPart;
    return results;
  }

  /**
   * Is this formId a StopWord ?
   * @param formId
   * @return
   */
  public boolean isStop(int formId)
  {
    if (formId >= formStop.length()) return false; // outside the set bits, shoul be not a stop word
    return formStop.get(formId);
  }



  /**
   * Get String value for a formId.
   * @param formId
   * @return
   */
  public String form(final int formId)
  {
    BytesRef bytes = new BytesRef();
    this.formDic.get(formId, bytes);
    return bytes.utf8ToString();
  }

  /**
   * Get a String value for a formId, using a mutable array of bytes.
   * @param formId
   * @param bytes
   * @return
   */
  public BytesRef form(int formId, BytesRef bytes)
  {
    return this.formDic.get(formId, bytes);
  }

  /**
   * How many docs for this formId ?
   * @param formId
   * @return
   */
  public int formDocs(int formId)
  {
    return formDocsAll[formId];
  }

  /**
   * Returns formId &gt;= 0 if exists, or &lt; 0 if not.
   * @param bytes
   * @return 
   */
  public int formId(final BytesRef bytes)
  {
    return formDic.find(bytes);
  }

  /**
   * Returns formId &gt;= 0 if exists, or &lt; 0 if not.
   * @param bytes
   * @return 
   */
  public int formId(final String term)
  {
    BytesRef bytes = new BytesRef(term);
    return formDic.find(bytes);
  }

  /**
   * Build a BitSet rule for efficient filtering of forms by formId.
   * @param filter
   * @return
   */
  public BitSet formRule(TagFilter filter)
  {
    BitSet rule = new SparseFixedBitSet(maxForm);
    final boolean noStop = filter.noStop();
    final int stopLim = formStop.length();
    for (int formId=1; formId < maxForm; formId++) {
      if (!noStop); // no tick for stopword
      else if (formId >= stopLim); // formId out of scope of stop words
      else if (!formStop.get(formId)); // not a stop word, let other rules play
      else { // stop word requested and is a stop word, tick and continue
        rule.set(formId);
        continue;
      }
      // set formId by tag
      if (filter.accept(formTag[formId])) rule.set(formId);
    }
    return rule;
  }
  
  /**
   * How many occs for this term ?
   * @param formId
   * @return
   */
  public long formOccs(int formId)
  {
    return formOccsAll[formId];
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param s
   */
  public long formOccs(final String s)
  {
    final BytesRef bytes = new BytesRef(s);
    final int id = formDic.find(bytes);
    if (id < 0) return -1;
    return formOccsAll[id];
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param bytes
   */
  public long formOccs(final BytesRef bytes)
  {
    final int id = formDic.find(bytes);
    if (id < 0) return -1;
    return formOccsAll[id];
  }

  /**
   * Global termlist, maybe filtered but not scored. More efficient than a scorer
   * that loop on each term for global.
   * @return
   */
  public FormEnum results(final TagFilter tags)
  {
    boolean hasTags = (tags != null);
    boolean noStop = (tags != null && tags.noStop());
    boolean locs = (tags != null && tags.locutions());
    double[] formScore = new double[maxForm];
    for (int formId=0; formId < maxForm; formId++) {
      if (noStop && isStop(formId)) continue;
      if (locs && !formLoc.get(formId)) continue;
      if (hasTags && !tags.accept(formTag[formId])) continue;
      // specif.idf(formOccs[formId], formDocsAll[formId] );
      // loop on all docs containing the term ?
      formScore[formId] = formOccsAll[formId];
    }
    // now we have all we need to build a sorted iterator on entries
    FormEnum results = new FormEnum(this);
    results.formScore = formScore;
    results.formDocsHit = formDocsAll;
    results.formOccsFreq = formOccsAll;
    return results;
  }

  /**
   * Count of occurrences by term for a subset of the index,
   * defined as a BitSet. Returns an iterator sorted according 
   * to a scorer. If scorer is null, default is count of occurences.
   */
  public FormEnum results(final TagFilter tags, Scorer scorer, final BitSet filter) throws IOException
  {
    boolean hasTags = (tags != null);
    boolean noStop = (tags != null && tags.noStop());
    boolean locs = (tags != null && tags.locutions());
    boolean hasScorer = (scorer != null);
    boolean hasFilter = (filter != null && filter.cardinality() > 0);
    
    FormEnum results = new FormEnum(this);
    if (hasScorer) results.formScore = new double[maxForm];
    results.formOccsFreq = new long[maxForm];
    results.formDocsHit = new int[maxForm];
    results.occsPart = 0;
    if (filter != null) {
      for (int docId = filter.nextSetBit(0); docId != DocIdSetIterator.NO_MORE_DOCS; docId = filter.nextSetBit(docId + 1)) {
        results.occsPart += docOccs[docId];
      }
    }
    // if (hasSpecif) specif.all(occsAll, docsAll);
    BitSet hitsVek = new FixedBitSet(reader.maxDoc());
    
    BytesRef bytes;
    // loop an all index to calculate a score for each term before build a more expensive object
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      Bits live = leaf.getLiveDocs();
      final boolean hasLive = (live != null);
      Terms terms = leaf.terms(fname);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        if (bytes.length == 0) continue; // do not count empty positions
        int formId = formDic.find(bytes);
        // filter some tags
        if (locs && !formLoc.get(formId)) continue;
        if (noStop && isStop(formId)) continue;
        if (hasTags && !tags.accept(formTag[formId])) continue;
        // if formId is negative, let the error go, problem in reader
        // for each term, set scorer with global stats
        // if (hasSpecif) specif.idf(formOccsAll[formId], formDocsAll[formId] );
        if (hasScorer) scorer.idf(occsAll, docsAll, formOccsAll[formId], formDocsAll[formId]);
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (hasLive && !live.get(docLeaf)) continue; // deleted doc
          int docId = docBase + docLeaf;
          if (hasFilter && !filter.get(docId)) continue; // document not in the filter
          int freq = docsEnum.freq();
          if (freq < 1) throw new ArithmeticException("??? field="+fname+" docId=" + docId+" term="+bytes.utf8ToString()+" freq="+freq);
          hitsVek.set(docId);
          results.formDocsHit[formId]++;
          if (hasScorer) {
            final double score = scorer.tf(freq, docOccs[docId]);
            if (score < 0) results.formScore[formId] -= score; // all variation is significant
            results.formScore[formId] += score;
          }
          results.formOccsFreq[formId] += freq;
        }
        if (hasScorer && filter != null) {
          // add inverse score
          final long restFreq = formOccsAll[formId] - results.formOccsFreq[formId];
          final long restLen = occsAll - results.occsPart;
          double score = scorer.last(restFreq, restLen);
          results.formScore[formId] -= score;
        }
      }
    }
    
    // We have counts, calculate scores
    // loop on the bitSet to have the part Size
    /*
    if (hasSpecif) {
      specif.part(partOccs, hitsVek.cardinality());
      // loop on all form to calculate scores
      for (int formId = 0; formId < formMax; formId++) {
        long formPartOccs = freqs[formId];
        if (formPartOccs < 1) continue;
        double p = specif.prob(scores[formId], formPartOccs, formOccsAll[formId]);
        scores[formId] = p;
      }
    }
    */
    return results;
  }
  

  /**
   * Return tag attached to form according to FrDics.
   * @param formId
   * @return
   */
  public int tag(int formId)
  {
    return formTag[formId];
  }

  /**
   * Get a dictionary of search, without statistics.
   * @param reader
   * @param field
   * @return
   * @throws IOException
   */
  static public BytesRefHash terms(DirectoryReader reader, String field) throws IOException
  {
    BytesRefHash hashDic = new BytesRefHash();
    BytesRef ref;
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      // int docBase = context.docBase;
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      while ((ref = tenum.next()) != null) {
        int formId = hashDic.add(ref);
        if (formId < 0) formId = -formId - 1; // value already given
      }
    }
    return hashDic;
  }


  /**
   * A temporary record used to sort collected terms from global index.
   */
  private class FormRecord implements Comparable<FormRecord>
  {
    final int tmpId;
    int docs;
    long occs;
    
    FormRecord(final int tmpId) {
      this.tmpId = tmpId;
    }
  
    @Override
    public int compareTo(FormRecord o)
    {
      int cp = Long.compare(o.occs, occs);
      if (cp != 0) return cp;
      // not the nicest alpha sort order
      return Integer.compare(tmpId, o.tmpId);
    }
    
  }

  @Override
  public String toString()
  {
    StringBuilder string = new StringBuilder();
    BytesRef ref = new BytesRef();
    int len = Math.min(maxForm, 200);
    for (int i = 0; i < len; i++) {
      formDic.get(i, ref);
      string.append(ref.utf8ToString() + ": " + formOccsAll[i] + "\n");
    }
    return string.toString();
  }

}
