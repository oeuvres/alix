package alix.lucene.search;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;

import alix.Names;
import alix.lucene.Alix;
import alix.lucene.TestIndex;
import alix.lucene.analysis.FrAnalyzer;
import alix.lucene.search.FieldInt.IntEnum;
import alix.util.Dir;

public class TestFieldFacet
{
    static Logger LOGGER = Logger.getLogger(TestFieldFacet.class.getName());
    static HashSet<String> FIELDS = new HashSet<String>();
    static {
        for (String w : new String[] { Names.ALIX_BOOKID, "byline", "year", "title" }) {
            FIELDS.add(w);
        }
    }
    static Alix alix;

    public static void test() throws IOException
    {
        String field = "boo";
        // test base
        Path path = Paths.get("work/test");
        Dir.rm(path);
        Alix alix = Alix.instance("test", path, new FrAnalyzer(), null);
        IndexWriter writer = alix.writer();
        for (String w : new String[] { "A", "B", "C" }) {
            Document doc = new Document();
            doc.add(new SortedDocValuesField(field, new BytesRef(w)));
            writer.addDocument(doc);
        }
        writer.commit();
        writer.close();
        IndexSearcher searcher = alix.searcher();
        // A SortedDocValuesField is not visible as a normal search
        Query query = new TermQuery(new Term(field, "A"));
        TopDocs top = searcher.search(query, 100);
        System.out.println(top.totalHits);
        for (ScoreDoc hit : top.scoreDocs) {
            System.out.println(hit.doc);
        }
    }
    
    public static void statsBug() throws IOException
    {
        FieldFacet facet = alix.fieldFacet(Names.ALIX_BOOKID);
        FieldText ftext = alix.fieldText("text");
        FormEnum results = facet.forms(ftext, null);

    }

    public static void main(String[] args) throws IOException, InterruptedException
    {
        long time = System.nanoTime();
        // test an existing index
        Path path = Paths.get("work/rougemont");
        alix = Alix.instance("test", path, new FrAnalyzer(), null);
        statsBug();
    }
}
