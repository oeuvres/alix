package alix.lucene.util;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.stream.IntStream;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import alix.lucene.Alix;
import alix.lucene.search.Freqs;
import alix.util.IntList;

/**
 * Persistent storage of full sequence of all document terms for a field.
 * Used for co-occurrences stats.
 * Data structure of the file
 * int:maxDoc, maxDoc*[int:docLength], maxDoc*[docLength*[int:termId], int:-1]
 * 
 * ChronicleMap has been tested, but it is not more than x2 compared to lucene BinaryField.
 * 
 * @author fred
 *
 */
public class Rail
{
  /** State of the index */
  private final Alix alix;
  /** Name of the reference text field */
  private final String field;
  /** Keep the freqs for the field */
  private final Freqs freqs;
  /** Dictionary of terms for this field */
  private final BytesRefHash hashDic;
  /** The path of underlaying file store */
  private final Path path;
  /** Cache a fileChannel for read */
  private FileChannel channel;
  /** A buffer on file */
  private MappedByteBuffer channelMap;
  /** Max for docId */
  private int maxDoc;
  /** Index of  positions for each doc im channel */
  private int[] posInt;
  /** Index of sizes for each doc */
  private int[] limInt;
  /** used for output */
  private BytesRef ref = new BytesRef();


  public Rail(Alix alix, String field) throws IOException
  {
    this.alix = alix;
    this.field = field;
    this.freqs = alix.freqs(field); // build and cache the dictionary of cache for the field
    this.hashDic = freqs.hashDic();
    this.path = Paths.get( alix.path.toString(), field+".rail");
    if (!path.toFile().exists()) store();
  }
  
  /**
   * Reindex all documents of the text field as an int vector
   * storing terms at their positions
   * {@link org.apache.lucene.document.BinaryDocValuesField}.
   * Byte ordering is the java default.
   * 
   * @throws IOException 
   */
  public void store() throws IOException
  {
    final FileLock lock;
    IndexReader reader = alix.reader();
    int maxDoc = reader.maxDoc();
    final FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE);
    lock = channel.lock(); // may throw OverlappingFileLockException if someone else has lock
    
    
    int[] docLength = freqs.docLength;

    long byteCap = 4 + 4 * maxDoc;
    for (int i = 0; i < maxDoc; i++) {
      byteCap += (docLength[i]+1) * 4;
    }
    
    // first store, the maxDoc count, will help to predict array sizes
    MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, byteCap);
    IntBuffer bufint = buf.asIntBuffer();
    bufint.put(maxDoc);
    bufint.put(docLength);
    IntList ints = new IntList();
    
    for (int docId = 0; docId < maxDoc; docId++) {
      Terms termVector = reader.getTermVector(docId, field);
      if (termVector == null) {
        bufint.put(-1);
        continue;
      }
      rail(termVector, ints);
      bufint.put(ints.data(), 0, ints.length());
      bufint.put(-1);
    }
    buf.force();
    channel.force(true);
    lock.close();
    channel.close();
  }
  
  /**
   * Load and calculate index for the rail file
   * @throws IOException 
   */
  public void load() throws IOException
  {
    FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
    DataInputStream data = new DataInputStream(Channels.newInputStream(channel));
    int maxDoc = data.readInt();
    this.maxDoc = maxDoc;
    int[] posInt = new int[maxDoc];
    int[] limInt = new int[maxDoc];
    int indInt = 1 + maxDoc;
    for(int i = 0; i < maxDoc; i++) {
      posInt[i] = indInt;
      int docLen = data.readInt();
      limInt[i] = docLen;
      indInt += docLen +1;
    }
    this.posInt = posInt;
    this.limInt = limInt;
    this.channel = channel;
    this.channelMap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
    
  }
  
  public String toString(int docId) throws IOException
  {
    int limit = 100;
    StringBuilder sb = new StringBuilder();
    IntBuffer bufInt = channelMap.position(posInt[docId] * 4).asIntBuffer();
    bufInt.limit(limInt[docId]);
    BytesRef ref = new BytesRef();
    while (bufInt.hasRemaining()) {
      int termId = bufInt.get();
      this.hashDic.get(termId, ref);
      sb.append(ref.utf8ToString());
      sb.append(" ");
      if (limit-- <= 0) {
        sb.append("[…]");
      }
    }
    return sb.toString();
  }
  
  /**
   * From a set of documents provided as a BitSet,
   * return a freqlist as an int vector,
   * where index is the termId for the field,
   * the value is count of occurrences of the term.
   * Counts are extracted from stored <i>rails</i>.
   * @throws IOException 
   */
  public int[] freqs(final BitSet filter) throws IOException
  {
    int[] freqs = new int[hashDic.size()];
    boolean hasFilter = (filter != null);
    IndexReader reader = alix.reader();
    int maxDoc = this.maxDoc;
    int[] posInt = this.posInt;
    int[] limInt = this.limInt;
    /*
    // parallel is slower and hard to ensure
    IntStream loop = IntStream.range(0, maxDoc).filter(docId -> {
      if (limInt[docId] == 0) return false;
      if (hasFilter && !filter.get(docId)) return false;
      return true;
    }).parallel().map(docId -> {
      // channelMap.position(posByte[docId]).asIntBuffer() DO NOT change the channelMap pos in parallell
      MappedByteBuffer buf = null;
      try { // create a buffer has no cost
        buf = channel.map(FileChannel.MapMode.READ_ONLY, posByte[docId], limInt[docId] * 4);
      } catch (IOException e) {
        e.printStackTrace();
      }
      IntBuffer bufInt = buf.asIntBuffer();
      while (bufInt.hasRemaining()) {
        int termId = bufInt.get();
        freqs[termId]++;
      }
      return docId;
    });
    loop.count(); // go
    */
    // no cost in time and memory to take an int view
    IntBuffer bufInt = channelMap.asIntBuffer();
    for (int docId = 0; docId < maxDoc; docId++) {
      if (limInt[docId] == 0) continue; // deleted or with no value for this field
      if (hasFilter && !filter.get(docId)) continue; // document not in the filter
      // new buffer is expensive for each doc
      // MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_ONLY, posByte[docId], limInt[docId] * 4);
      bufInt.position(posInt[docId]);
      for (int i = 0, max = limInt[docId] ; i < max; i++) {
        int termId = bufInt.get();
        freqs[termId]++;
      }
    }
    return freqs;
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
  public void rail(Terms termVector, IntList buf) throws IOException
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
   * Tokens of a doc as strings from a byte array
   * @param rail Binary version an int array
   * @param offset Start index in the array
   * @param length Length of bytes to consider from offset
   * @return
   * @throws IOException
   */
  public String[] strings(int[] rail) throws IOException
  {
    int len = rail.length;
    String[] words = new String[len];
    BytesRef ref = new BytesRef();
    for (int i = 0; i < len; i++) {
      int termId = rail[i];
      this.hashDic.get(termId, ref);
      words[i] = ref.utf8ToString();
    }
    return words;
  }
  
  public String form(final int termId) {
    this.hashDic.get(termId, ref);
    return ref.utf8ToString();
  }

}
