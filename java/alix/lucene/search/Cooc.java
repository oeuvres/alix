package alix.lucene.search;

import java.io.IOException;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;

import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.MatchesIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BytesRef;

/**
 * Send the co-ocurences of pivot query (single or multi-terms).
 * Requires 
 * @author fred
 *
 */
public class Cooc
{
  /** Searcher */
  private final IndexSearcher searcher;
  /** Reader */
  private final IndexReader reader;
  /** Number of positions left to pivot */
  private final int left = 5;
  /** Number of positions right to pivot */
  private final int right = 5;
  /** Docs of the pivot query */
  private final BitSet filterBits;
  /** Number of hits (documents) containing the pivot query */
  private final int hits;
  /** Number of occurrences of the pivot */
  private int lines;
  /** For each docs, possible positions of terms for the concordance */
  final LinkedHashMap<Integer,BitSet> docMaps = new LinkedHashMap<Integer,BitSet>();
  
  /**
   * Store position of the pivot queries
   * @param pivotQuery
   * @param left
   * @param right
   * @throws IOException 
   */
  public Cooc(final IndexSearcher searcher, final Query pivotQuery, String field, final int left, final int right) throws IOException
  {
    this.searcher = searcher;
    this.reader = searcher.getIndexReader();
    final IndexReader reader = this.reader;
    

    
    final CollectorBits collector = new CollectorBits(searcher);
    searcher.search(pivotQuery, collector);
    this.filterBits = collector.bits();
    BitSet filter = collector.bits();
    
    
    this.hits = collector.hits();
    // Get the positions of the pivot query
    Weight weight = pivotQuery.createWeight(searcher, ScoreMode.COMPLETE, 0);
    
    Iterator<LeafReaderContext> contexts = reader.leaves().iterator(); 
    LeafReaderContext ctx = null;
    int docBase = 0;
    int maxDoc = 0;
    // loop on the bitSet of filtered docs
    for (int docid = filter.nextSetBit(0); docid >= 0; docid = filter.nextSetBit(docid+1)) {
      // search the right context 
      while((docid - docBase) >= maxDoc) {
        if (!contexts.hasNext()) return; // no more contexts, go away
        ctx = contexts.next();
        docBase = ctx.docBase;
        maxDoc = ctx.reader().maxDoc();
      }
      // try to get matches in the current index segment 
      Matches matches = weight.matches(ctx, docid - docBase);
      
      if (matches == null) continue;
      // with matches, fill a bitset of positions to select occurences in the pivot context
      BitSet positions = new BitSet();
      MatchesIterator mi = matches.getMatches(field);
      if (mi == null) continue;
      while(mi.next()) {
        int fromIndex = Math.max(0, mi.startPosition() - left);
        int toIndex = mi.endPosition() + right;
        positions.set(fromIndex, toIndex+1);
        lines++;
      }
      // store this map of positions by doc 
      docMaps.put(docid, positions);
    }
  }
  /**
   * Get the count for a term in the 
   * @param field
   * @param bytes
   * @return
   * @throws IOException
   */
  public long count(String field, BytesRef bytes) throws IOException
  {
    long count = 0;
    Iterator<LeafReaderContext> contexts = reader.leaves().iterator();
    ImpactsEnum impacts = null;
    int docBase = 0;
    int cursor = -1;
    // Better should be to loop ont the corded position maps
    BitSet filter = this.filterBits;
    noleaf:
    for (int docid = filter.nextSetBit(0); docid >= 0; docid = filter.nextSetBit(docid+1)) {
      if (docid < (cursor + docBase)) continue;
      if (docid > (cursor + docBase) && impacts != null) cursor = impacts.advance(docid - docBase);
      if (cursor == ImpactsEnum.NO_MORE_DOCS) impacts = null;
      while (impacts == null) {
        if (!contexts.hasNext()) break noleaf;
        LeafReaderContext ctx = contexts.next();
        docBase = ctx.docBase;
        LeafReader segReader = ctx.reader();
        TermsEnum tenum = segReader.terms(field).iterator();
        if (!tenum.seekExact(bytes)) continue; // term not found in this leaf
        impacts = tenum.impacts(PostingsEnum.POSITIONS);
        cursor = impacts.advance(docid - docBase);
        if (cursor == ImpactsEnum.NO_MORE_DOCS) impacts = null;
      }
      // we should have the leaf cursor as near as possible
      if (docid != (cursor + docBase)) continue;
      BitSet positions = docMaps.get(docid);
      // no positions may have been recorded for this docid (filter bitset bigger than the matching docs)
      if (positions == null) continue;
      int freq = impacts.freq();
      for (int i = 0; i < freq; i++) {
        int pos = impacts.nextPosition();
        if (positions.get(pos)) count++;
      }
    }
    return count;
  }
  

}
