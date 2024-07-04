package com.github.oeuvres.alix.lucene.search;


import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BytesRef;
import org.junit.Test;

import com.github.oeuvres.alix.lucene.search.WordSuggest;


public class WordSuggestTest extends LuceneTestCase {
    WordSuggest sugg;
    
    @Test
    public void testBasics() throws Exception
    {
        Directory indexStore = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), indexStore);
        Document doc = new Document();
        doc.add(new TextField("text", "Maison maison maisonnée MAÎSTRE cabane cœlène", Field.Store.NO));
        writer.addDocument(doc);
        doc.clear();
        doc.add(new TextField("text", "Maïs maisonnette maison", Field.Store.NO));
        writer.addDocument(doc);
        writer.close(); // close after get the reader ?

        IndexReader reader = DirectoryReader.open(indexStore);
        FieldText ftext = new FieldText(reader, "text");
        sugg = new WordSuggest(ftext.formDic);
        System.out.println(sugg.ascii);
        for (int i = 0; i < sugg.ascii.length(); i += 10) {
            System.out.print(" 123456789");
        }
        System.out.println("");
        for (String q: new String[] {
            "_m",
            "_c",
            "ne_",
            "t",
            "aïs",
            "_a",
        }) {
            search(q);
        }
        reader.close();
        indexStore.close();
    }
    
    void search(final String q) {
        int[] formIds = sugg.search(q);
        System.out.print(q + " — ");
        BytesRef bytes = new BytesRef();
        for (int formId: formIds) {
            sugg.formDic.get(formId, bytes);
            System.out.print(" " + bytes.utf8ToString());
        }
        System.out.println();
    }


}
