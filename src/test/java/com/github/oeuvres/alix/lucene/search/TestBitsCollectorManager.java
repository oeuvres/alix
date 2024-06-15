package com.github.oeuvres.alix.lucene.search;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;
import org.junit.Test;

public class TestBitsCollectorManager extends LuceneTestCase
{

    public void print(BitSet bits)
    {
        for (int docId = bits.nextSetBit(0), max = bits.length(); docId < max; docId = bits.nextSetBit(docId + 1)) {
            System.out.print(", " + docId);
        }
    }
    
    public void testBasics() throws Exception
    {
        Directory indexStore = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), indexStore);
        for (int i = 0; i < 5; i++) {
            Document doc = new Document();
            doc.add(new StringField("string", "a" + i, Field.Store.NO));
            doc.add(new StringField("string", "b" + i, Field.Store.NO));
            writer.addDocument(doc);
        }
        IndexReader reader = writer.getReader();
        writer.close();

        final int maxDoc = reader.maxDoc();
        IndexSearcher searcher = newSearcher(reader, true, true, random().nextBoolean());
        BitsCollectorManager collectorManager = new BitsCollectorManager(maxDoc);

        FixedBitSet bits = searcher.search(new MatchAllDocsQuery(), collectorManager);
        FixedBitSet test = new FixedBitSet(maxDoc);
        test.set(0, 1);
        System.out.println(FixedBitSet.intersectionCount(bits, test) == bits.cardinality());


        /*
         * assertEquals(5, totalHits);
         * 
         * Query query = new BooleanQuery.Builder() .add(new TermQuery(new
         * Term("string", "a1")), Occur.SHOULD) .add(new TermQuery(new Term("string",
         * "b3")), Occur.SHOULD) .build(); totalHits = searcher.search(query,
         * collectorManager); assertEquals(2, totalHits);
         */
        reader.close();
        indexStore.close();
    }
}