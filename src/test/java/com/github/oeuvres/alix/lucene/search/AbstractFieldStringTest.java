package com.github.oeuvres.alix.lucene.search;

import java.util.Arrays;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;
import org.junit.Test;

public class AbstractFieldStringTest
{
    @Test
    public void sortBytes()
    {
        final BytesRefHash dic = new BytesRefHash();
        for (String word: new String[] {"A", "B"} ) {
            final BytesRef bytes = new BytesRef(word);
            dic.add(bytes);
        }
        String[] search = new String[] {"C", null, null, "a", "A", "B", "à", "À", "ç", "c"};
        BytesRef[] forms = AbstractFieldString.bytesSorted(dic, search);
        for (BytesRef bytes: forms) {
            System.out.println(bytes.utf8ToString());
        }
    }
}