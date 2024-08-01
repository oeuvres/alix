package com.github.oeuvres.alix.lucene.search;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Random;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.TermState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.junit.Test;

import com.github.oeuvres.alix.fr.Tag;
import com.github.oeuvres.alix.fr.TagFilter;
import com.github.oeuvres.alix.lucene.search.WordSuggest;
import com.github.oeuvres.alix.lucene.search.WordSuggest.Suggestion;
import com.github.oeuvres.alix.util.Char;

public class WordSuggestTest
{
    private static String FIELD = "text_cloud";

    public void createIndex() throws IOException
    {
        /*
        Directory indexStore = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), indexStore);
        Document doc = new Document();
        doc.add(new TextField(FIELD, "Maison maison maisonnée MAÎSTRE cabane cœlène", Field.Store.NO));
        writer.addDocument(doc);
        doc.clear();
        doc.add(new TextField(FIELD, "Maïs maisonnette maison", Field.Store.NO));
        writer.addDocument(doc);
        writer.commit();
        writer.close(); // close after get the reader ?
        */
    }
    
    public static void main(String[] args) throws Exception {
        mark();
    }
    
    public static void mark()
    {
        System.out.println(Char.isPunctuationOrSpace('_'));
        System.out.println(Char.toASCII("_op", true));
        markPrint("opération", "_op");
        markPrint("Mammounia", "m");
        markPrint("ammassement", "m");
        markPrint("moplimomotumort", "mo");
    }

    public static void markPrint(String word, String q)
    {
        System.out.println(word + " " + q + " " + WordSuggest.mark(word, q));
    }

    public static void search() throws IOException
    {
        long startTime = System.nanoTime();
        Directory dir = FSDirectory.open(Paths.get("../piaget_labo/lucene/piaget_leaves"));
        IndexReader reader = DirectoryReader.open(dir);
        System.out.println("Open Directory " + (((double)( System.nanoTime() - startTime)) / 1000000) + "ms");
        startTime = System.nanoTime();
        FieldText ftext = new FieldText(reader, FIELD);
        System.out.println("Build FieldText " + (((double)( System.nanoTime() - startTime)) / 1000000) + "ms");
        startTime = System.nanoTime();
        WordSuggest sugg = new WordSuggest(ftext);
        System.out.println("Buid wordSuggest " + (((double)( System.nanoTime() - startTime)) / 1000000) + "ms");
        TagFilter wordFilter = new TagFilter().set(Tag.NOSTOP);
        for (final String q : new String[] {
            "paï", 
            // "_m",
            "_lo",
            // "ne_",
            // "t",
            // "païs",
            "_a", 
            "paï", 
        }) {
            startTime = System.nanoTime();
            Suggestion[] top = sugg.search(q, 10, wordFilter, null);
            System.out.println(q + "  " + (((double)( System.nanoTime() - startTime)) / 1000000) + "ms");
            for (Suggestion word: top) {
                System.out.println(word);
            }
        }
        reader.close();
        dir.close();
    }

    // Implementing Fisher–Yates shuffle
    static void shuffle(int[] ar)
    {
        Random rnd = new Random();
        for (int i = ar.length - 1; i > 0; i--) {
            int index = rnd.nextInt(i + 1);
            // Simple swap
            int a = ar[index];
            ar[index] = ar[i];
            ar[i] = a;
        }
    }


}
