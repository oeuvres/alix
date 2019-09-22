package alix.lucene.util;
/**
 * A co-occurrences scanner in a  {@link org.apache.lucene.document.TextField} of a lucene index.
 * This field should store term vectors with positions
 * {@link org.apache.lucene.document.FieldType#setStoreTermVectorPositions(boolean)}.
 * Efficiency is based on a pre-indexation of each document
 * as an int vector where each int is a term at its position
 * (a “rail”).
 * This object should be created on a “dead index”, 
 * with all writing operations commited.
 * 
 * In the method 
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

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
import alix.lucene.search.Scorer;
import alix.lucene.search.TermList;
import alix.lucene.search.TopTerms;
import alix.util.Calcul;
import alix.util.IntList;

public class Cooc
{
  /** Suffix for a binary field containing tokens by position */
  private static final String _RAIL = "_rail";
  /** Name of the reference text field */
  private final String field;
  /** Name of the binary field storing the int vector of documents */
  private final String fieldBin;
  /** Dictionary of terms for this field */
  private final BytesRefHash hashDic;
  /** State of the index */
  private final Alix alix;
  /**
   * Build a co-occurrences scanner.
   * @param alix A link to a lucene Index, with tools to get terms.
   * @param field A text field name with term vectors.
   * @throws IOException
   */
  public Cooc(Alix alix, String field) throws IOException
  {
    this.alix = alix;
    this.field = field;
    this.fieldBin = field + _RAIL;
    Freqs freqs = alix.freqs(field); // build and cache the dictionary of cache for the field
    this.hashDic = freqs.hashDic();
  }
  
  
  /**
   * Flaten terms of a document in a position order, according to the dictionary of terms.
   * Write it in a binary buffer, ready to to be stored in a BinaryField.
   * {@link org.apache.lucene.document.BinaryDocValuesField}
   * @param termVector A term vector of a document with positions.
   * @param buf A reusable binary buffer to index.
   * @return
   * @throws IOException
   */
  public ByteBuffer rail(Terms termVector, ByteBuffer buf) throws IOException
  {
    int capacity = buf.capacity();
    buf.clear(); // reset markers but do not erase
    // tested, 2x faster than System.arraycopy after 5 iterations
    Arrays.fill(buf.array(), (byte)0);
    BytesRefHash hashDic = this.hashDic;
    TermsEnum tenum = termVector.iterator();
    PostingsEnum postings = null;
    BytesRef bytes = null;
    int length = -1; // length of the array
    while ((bytes = tenum.next()) != null) {
      int termId = hashDic.find(bytes);
      if (termId < 0) System.out.println("unknown term? "+bytes.utf8ToString());
      postings = tenum.postings(postings, PostingsEnum.POSITIONS);
      postings.nextDoc(); // always one doc
      int freq = postings.freq();
      for (int i = 0; i < freq; i++) {
        int pos = postings.nextPosition();
        int index = pos * 4;
        if (capacity < (index+4)) {
          capacity = Calcul.nextSquare(index+4);
          ByteBuffer expanded = ByteBuffer.allocate(capacity);
          expanded.order(buf.order());
          expanded.put(buf);
          buf = expanded;
        }
        if (length < (index+4)) length = index+4;
        buf.putInt(index, termId);
      }
    }
    buf.limit(length);
    return buf;
  }
  
  /**
   * Reindex all documents of the text field as an int vector
   * storing terms and their positions
   * {@link org.apache.lucene.document.BinaryDocValuesField}
   * @throws IOException 
   */
  public void prepare() throws IOException
  {
    // known issue, writer should have been closed before reindex
    IndexWriter writer = alix.writer();
    writer = alix.writer();
    IndexReader reader = alix.reader(writer);
    int maxDoc = reader.maxDoc();
    // create a byte buffer, big enough to store all docs
    ByteBuffer buf =  ByteBuffer.allocate(2);
    // buf.order(ByteOrder.LITTLE_ENDIAN);
    
    for (int docId = 0; docId < maxDoc; docId++) {
      Terms termVector = reader.getTermVector(docId, field);
      if (termVector == null) continue;
      buf = rail(termVector, buf); // reusable buffer may be 
      BytesRef ref =  new BytesRef(buf.array(), buf.arrayOffset(), buf.limit());
      Field field = new BinaryDocValuesField(fieldBin, ref);
      long code =  writer.tryUpdateDocValue(reader, docId, field);
      if( code < 0) System.out.println("Field \""+fieldBin+"\", update error for doc="+docId+" ["+code+"]");
    }
    reader.close();
    writer.commit();
    writer.forceMerge(1);
    writer.close();
    alix.reader(true); // renew reader, to view the new field
  }
  
  public TopTerms topTerms(final TermList terms, final int left, final int right, final BitSet filter, Scorer scorer) throws IOException
  {
    int[] freqs = freqs(terms, left, right, filter);
    TopTerms dic = new TopTerms(hashDic);
    dic.sort(freqs);
    return dic;
  }

  
  /**
   * Get cooccurrences fron a multi term query.
   * Each document should be available as an int vector
   * see {@link rail()}.
   * A loop will cross all docs, 
   * @throws IOException 
   */
  public int[] freqs(final TermList terms, final int left, final int right, final BitSet filter) throws IOException
  {
    IndexReader reader = alix.reader();
    final int END = DocIdSetIterator.NO_MORE_DOCS;
    // collector of scores
    int[] freqs = new int[this.hashDic.size()];
    // for each doc, a bit set is used to record the relevant positions
    // this will avoid counting interferences when terms are close
    java.util.BitSet positions = new java.util.BitSet();
    // loop on leafs
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      int docLeaf;
      LeafReader leaf = context.reader();
      // loop carefully on docs with a rail
      BinaryDocValues binDocs = leaf.getBinaryDocValues(fieldBin);
      if (binDocs == null) continue; // why not let error ?
      // start iterators for each term
      ArrayList<PostingsEnum> list = new ArrayList<PostingsEnum>();
      for (Term term : terms) {
        if (term == null) continue;
        PostingsEnum postings = leaf.postings(term, PostingsEnum.FREQS|PostingsEnum.POSITIONS);
        if (postings == null) continue;
        int doc = postings.nextDoc(); // advance cursor to the first doc
        list.add(postings);
      }
      PostingsEnum[] termDocs = list.toArray(new PostingsEnum[0]);
      final Bits liveDocs = leaf.getLiveDocs();
      while ( (docLeaf = binDocs.nextDoc()) != END) {
        if (liveDocs != null && !liveDocs.get(docLeaf)) continue; // deleted doc
        int docId = docBase + docLeaf;
        if (filter != null && !filter.get(docId)) continue; // document not in the metadata fillter
        boolean found = false;
        positions.clear();
        // loop on term iterator to get positions for this doc
        for (PostingsEnum postings: termDocs) {
          int doc = postings.docID();
          if (doc == END || doc > docLeaf) continue;
          // 
          // if (doc == d)
          if (doc < docLeaf) doc = postings.advance(docLeaf - 1);
          if (doc > docLeaf) continue;
          int freq = postings.freq();
          if (freq == 0) continue;
          found = true;
          for (; freq > 0; freq --) {
            int position = postings.nextPosition();
            int fromIndex = Math.max(0, position - left);
            int toIndex = position + right + 1; // toIndex (exclusive)
            positions.set(fromIndex, toIndex);
          }
        }
        if (!found) continue;
        BytesRef ref = binDocs.binaryValue();
        ByteBuffer buf = ByteBuffer.wrap(ref.bytes, ref.offset, ref.length);
        // loop on the positions 
        for (int pos = positions.nextSetBit(0); pos >= 0; pos = positions.nextSetBit(pos+1)) {
          int termId = buf.getInt(pos*4);
          freqs[termId]++;
        }
      }
    }
    return freqs;
  }
  
  /**
   * Align a term vector
   * @param array
   * @param value
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

  public String[] strings(BytesRef ref) throws IOException
  {
    return strings(ref.bytes, ref.offset, ref.length);
  }
  
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
