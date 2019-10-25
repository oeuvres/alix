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
package alix.lucene.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import alix.lucene.Alix;
import alix.lucene.search.Freqs;
import alix.lucene.search.TermList;
import alix.lucene.search.TopTerms;

/** 
 * A co-occurrences scanner in a  {@link org.apache.lucene.document.TextField} of a lucene index.
 * This field should store term vectors with positions
 * {@link org.apache.lucene.document.FieldType#setStoreTermVectorPositions(boolean)}.
 * Efficiency is based on a post-indexing of each document,
 * affecting an int id to each  term at its position (a “rail”).
 * Also, coocs should be written on a “dead index”, 
 * with all writing operations committed.
 */
public class Cooc
{
  /** Suffix for a binary field containing tokens by position */
  public static final String _RAIL = "_rail";
  /** Name of the reference text field */
  private final String field;
  /** Name of the binary field storing the int vector of documents */
  private final String fieldRail;
  /** Keep the freqs for the field */
  private final Freqs freqs;
  /** Dictionary of terms for this field */
  private final BytesRefHash hashDic;
  /** State of the index */
  private final Alix alix;
  /**
   * Build a co-occurrences scanner.
   * 
   * @param alix A link to a lucene Index, with tools to get terms.
   * @param field A text field name with term vectors.
   * @throws IOException
   */
  public Cooc(Alix alix, String field) throws IOException
  {
    this.alix = alix;
    this.field = field;
    this.fieldRail = field + _RAIL;
    this.freqs = alix.freqs(field); // build and cache the dictionary of cache for the field
    this.hashDic = freqs.hashDic();
  }
  
  /**
   * Reindex all documents of the text field as an int vector
   * storing terms at their positions
   * {@link org.apache.lucene.document.BinaryDocValuesField}.
   * Byte ordering is the java default.
   * 
   * @throws IOException 
   */
  public void write() throws IOException
  {
    // known issue, writer should have been closed before reindex
    IndexWriter writer = alix.writer();
    IndexReader reader = alix.reader(writer);
    int maxDoc = reader.maxDoc();
    // create a byte buffer, will grow if more 
    BinaryInts buf =  new BinaryInts(1024);
    
    for (int docId = 0; docId < maxDoc; docId++) {
      Terms termVector = reader.getTermVector(docId, field);
      if (termVector == null) continue;
      rail(termVector, buf);
      Field field = new BinaryDocValuesField(fieldRail, buf.getBytesRef());
      long code =  writer.tryUpdateDocValue(reader, docId, field);
      if( code < 0) System.out.println("Field \""+fieldRail+"\", update error for doc="+docId+" ["+code+"]");
    }
    reader.close();
    writer.commit();
    writer.forceMerge(1);
    writer.close();
    alix.reader(true); // renew reader, to view the new field
  }

  /**
   * Flatten terms of a document in a position order, according to the dictionary of terms.
   * Write it in a binary buffer, ready to to be stored in a BinaryField.
   * {@link org.apache.lucene.document.BinaryDocValuesField}
   * The buffer could be modified if resizing was needed.
   * @param termVector A term vector of a document with positions.
   * @param buf A reusable binary buffer to index.
   * @throws IOException
   */
  public void rail(Terms termVector, BinaryInts buf) throws IOException
  {
    buf.reset(); // celan all
    BytesRefHash hashDic = this.hashDic;
    TermsEnum tenum = termVector.iterator();
    PostingsEnum postings = null;
    BytesRef bytes = null;
    int maxpos = -1;
    int minpos = Integer.MAX_VALUE;
    while ((bytes = tenum.next()) != null) {
      int termId = hashDic.find(bytes);
      if (termId < 0) System.out.println("unknown term? "+bytes.utf8ToString());
      postings = tenum.postings(postings, PostingsEnum.POSITIONS);
      postings.nextDoc(); // always one doc
      int freq = postings.freq();
      for (int i = 0; i < freq; i++) {
        int pos = postings.nextPosition();
        if (pos > maxpos) maxpos = pos;
        if (pos < minpos) minpos = pos;
        buf.put(pos, termId);
      }
    }
  }
  

  
  /**
   * Get cooccurrences fron a multi term query.
   * Each document should be available as an int vector
   * see {@link #rail(Terms, BinaryInts)}.
   * A loop will cross all docs, and 
   * @param terms List of terms to search accross docs to get positions.
   * @param left Number of tokens to catch at the left of the pivot.
   * @param right Number of tokens to catch at the right of the pivot.
   * @param filter Optional filter to limit the corpus of documents.
   * @throws IOException
   */
  public TopTerms topTerms(final TermList terms, final int left, final int right, final BitSet filter) throws IOException
  {
    TopTerms dic = new TopTerms(hashDic);
    // BM25 seems the best scorer
    /*
    Scorer scorer = new ScorerBM25(); 
    scorer.setAll(occsAll, docsAll);
    dic.setLengths(termLength);
    dic.setDocs(termDocs);
    */
    IndexReader reader = alix.reader();
    final int END = DocIdSetIterator.NO_MORE_DOCS;
    // collector of scores
    int size = this.hashDic.size();
    int[] freqs = new int[size];
    int[] hits = new int[size];
    // to count documents, a set to count only first occ in a doc
    java.util.BitSet dicSet = new java.util.BitSet(size);

    // for each doc, a bit set is used to record the relevant positions
    // this will avoid counting interferences when terms are close
    java.util.BitSet contexts = new java.util.BitSet();
    java.util.BitSet pivots = new java.util.BitSet();
    // loop on leafs
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      int docLeaf;
      LeafReader leaf = context.reader();
      // loop carefully on docs with a rail
      BinaryDocValues binDocs = leaf.getBinaryDocValues(fieldRail);
      // probably nothing indexed
      if (binDocs == null) continue; 
      // start iterators for each term
      ArrayList<PostingsEnum> list = new ArrayList<PostingsEnum>();
      for (Term term : terms) {
        if (term == null) continue;
        PostingsEnum postings = leaf.postings(term, PostingsEnum.FREQS|PostingsEnum.POSITIONS);
        if (postings == null) continue;
        postings.nextDoc(); // advance cursor to the first doc
        list.add(postings);
      }
      PostingsEnum[] termDocs = list.toArray(new PostingsEnum[0]);
      final Bits liveDocs = leaf.getLiveDocs();
      while ( (docLeaf = binDocs.nextDoc()) != END) {
        if (liveDocs != null && !liveDocs.get(docLeaf)) continue; // deleted doc
        int docId = docBase + docLeaf;
        if (filter != null && !filter.get(docId)) continue; // document not in the metadata fillter
        
        boolean found = false;
        contexts.clear();
        pivots.clear();
        // loop on term iterator to get positions for this doc
        for (PostingsEnum postings: termDocs) {
          int doc = postings.docID();
          if (doc == END || doc > docLeaf) continue;
          // 
          if (doc < docLeaf) doc = postings.advance(docLeaf - 1);
          if (doc > docLeaf) continue;
          int freq = postings.freq();
          if (freq == 0) continue;
          found = true;
          for (; freq > 0; freq --) {
            final int position = postings.nextPosition();
            final int fromIndex = Math.max(0, position - left);
            final int toIndex = position + right + 1; // toIndex (exclusive)
            contexts.set(fromIndex, toIndex);
            pivots.set(position);
          }
        }
        if (!found) continue;
        // substract pivots from context, this way should avoid counting pivot
        contexts.andNot(pivots);
        BytesRef ref = binDocs.binaryValue();
        ByteBuffer buf = ByteBuffer.wrap(ref.bytes, ref.offset, ref.length);
        // loop on the positions 
        int pos = contexts.nextSetBit(0);
        if (pos < 0) continue; // word found but without context, ex: first word without left
        int max = ref.length - 3;
        dicSet.clear(); // clear the term set, to count only first occ as doc
        while (true) {
          int index = pos*4;
          if (index >= max) break; // position further than available tokens
          int termId = buf.getInt(index);
          freqs[termId]++;
          if (!dicSet.get(termId)) {
            hits[termId]++;
            dicSet.set(termId);
          }
          pos = contexts.nextSetBit(pos+1);
          // System.out.print(pos);
          if (pos < 0) break; // no more positions
        }
      }
    }
    /*
    // try to calculate a score
    double[] scores = new double[size];
    for (int i = 0; i < size; i++) {
      
    }
    */
    dic.setHits(hits);
    dic.setOccs(freqs);
    return dic;
  }
  
  /**
   * Get the token sequence of a document.
   * @throws IOException 
   * 
   */
  public  String[] sequence(int docId) throws IOException
  {
    IndexReader reader = alix.reader();
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      if (docBase > docId) return null;
      int docLeaf = docId - docBase;
      LeafReader leaf = context.reader();
      BinaryDocValues binDocs = leaf.getBinaryDocValues(fieldRail);
      if (binDocs == null) return null;
      int docFound = binDocs.advance(docLeaf);
      // maybe found on next leaf
      if (docFound == DocIdSetIterator.NO_MORE_DOCS) continue;
      // docId not found
      if (docFound != docLeaf) return null;
      BytesRef ref = binDocs.binaryValue();
      return strings(ref);
    }
    return null;
  }
  
  /**
   * Get the tokens of a term vector as an array of strings.
   * @param termVector
   * @return
   * @throws IOException
   */
  public static String[] strings(Terms termVector) throws IOException
  {
    TermsEnum tenum = termVector.iterator();
    PostingsEnum postings = null;
    String[] words = new String[1000];
    BytesRef bytes = null;
    while ((bytes = tenum.next()) != null) {
      postings = tenum.postings(postings, PostingsEnum.POSITIONS);
      postings.nextDoc(); // always one doc
      int freq = postings.freq();
      for (int i = 0; i < freq; i++) {
        int pos = postings.nextPosition();
        words = ArrayUtil.grow(words, pos + 1);
        words[pos] = bytes.utf8ToString();
      }
    }
    return words;
  }

  /**
   * Tokens of a doc as strings from bytes.
   * @param ref
   * @return An indexed document as an array of strings.
   * @throws IOException
   */
  public String[] strings(BytesRef ref) throws IOException
  {
    return strings(ref.bytes, ref.offset, ref.length);
  }
  
  /**
   * Tokens of a doc as strings from a byte array
   * @param rail Binary version an int array
   * @param offset Start index in the array
   * @param length Length of bytes to consider from offset
   * @return
   * @throws IOException
   */
  public String[] strings(byte[] rail, int offset, int length) throws IOException
  {
    ByteBuffer buf = ByteBuffer.wrap(rail, offset, length);
    int size = length / 4;
    String[] words = new String[size];
    BytesRef ref = new BytesRef();
    for (int pos = 0; pos < size; pos++) {
      int termId = buf.getInt();
      this.hashDic.get(termId, ref);
      words[pos] = ref.utf8ToString();
    }
    return words;
  }

}
