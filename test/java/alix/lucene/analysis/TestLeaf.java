package alix.lucene.analysis;




import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;

import alix.lucene.Alix;
import alix.lucene.TestIndex;
import alix.lucene.search.BitsFromQuery;

public class TestLeaf
{


  public static void main(String args[]) throws Exception
  {
    Alix alix = TestIndex.index();
    // Loop on the terms of a text field to get the top terms from a filter
    BooleanQuery filterQuery = new BooleanQuery.Builder().add(IntPoint.newExactQuery(TestIndex.INT, 0), Occur.SHOULD)
        .add(IntPoint.newExactQuery(TestIndex.INT, 1), Occur.SHOULD).build();
    /*
    BooleanQuery wordQuery = new BooleanQuery.Builder()
        .add(new TermQuery(new Term(Indexer.TEXT, "zz")), Occur.SHOULD)
        .add(new TermQuery(new Term(Indexer.TEXT, "f")), Occur.SHOULD).build();
    BooleanQuery pivotQuery = new BooleanQuery.Builder().add(filterQuery, Occur.SHOULD).add(wordQuery, Occur.SHOULD)
        .build();
    */
    BitsFromQuery filter = null;
    if (filterQuery != null) filter = new BitsFromQuery(filterQuery);
    IndexReader reader = alix.reader();
    String field = TestIndex.TEXT;
    BytesRef bytes;
    for (LeafReaderContext context : reader.leaves()) {
      BitSet bits = null;
      if (filter != null) {
        bits = filter.bits(context); // the filtered docs for this segment
        if (bits == null) continue; // no matching doc, go away
      }
      LeafReader leaf = context.reader();
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      
      TermsEnum tenum = terms.iterator();
      System.out.println(" positions="+terms.hasPositions());
      PostingsEnum docsEnum = null;
      
      while ((bytes = tenum.next()) != null) {
        System.out.print(bytes.utf8ToString());
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (bits != null && !bits.get(docLeaf)) continue; // document not in the metadata fillter
          System.out.print(", "+docsEnum.freq());
        }
        System.out.println(".");
      }
    }
    
    /* term vector has no valuable id
    for (int docId = 0, maxDoc = reader.maxDoc(); docId < maxDoc; docId++) {
      Terms terms = reader.getTermVector(docId, Indexer.TEXT);
      TermsEnum termsEnum = terms.iterator();
      BytesRef term = null;
      System.out.println(docId);
      while ((term = termsEnum.next()) != null) {
        System.out.println(term.utf8ToString()+" "+termsEnum.termState());
      }
    }
    */
    
   /*
    By document, store 
    */
    
    /*
    long[] docLength = lucene.docLength(Indexer.TEXT);
    for (int i = 0, length = docLength.length; i < length; i++) {
      System.out.print(i+":"+docLength[i]+", ");
    }
    */
    


    /*


    String field = Indexer.INT;
    // get a vector of int values by docId
     */
    /*
     * IndexSearcher searcher = lucene.searcher(); Query q = new
     * MatchAllDocsQuery(); CollectorBits collector = new CollectorBits(searcher);
     * searcher.search(q, collector); DocIdSet docs = collector.docs();
     * DocIdSetIterator it = docs.iterator(); int docId; while((docId =
     * it.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) { System.out.println(docId);
     * } // System.out.println(docs.bits()); // q.createWeight(searcher, scoreMode,
     * boost)
     */

    /*
     * Cooc cooc = new Cooc(alix.searcher(), pivotQuery, Indexer.content, 1, 1);
     * System.out.println(cooc.docMaps); BytesRef bytes = new BytesRef(); DicBytes
     * bytesDic = alix.dic(Indexer.content); Cursor cursor = bytesDic.iterator();
     * while (cursor.hasNext()) { cursor.next(); cursor.term(bytes);
     * System.out.println(bytes.utf8ToString()+": "+cooc.count(Indexer.content,
     * bytes)+"/"+cursor.count()); }
     */
  }

}
