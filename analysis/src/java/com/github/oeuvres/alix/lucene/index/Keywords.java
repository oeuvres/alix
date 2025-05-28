package com.github.oeuvres.alix.lucene.index;


import java.io.File;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.lucene.index.Term;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;

import static com.github.oeuvres.alix.common.Names.*;
import com.github.oeuvres.alix.lucene.search.AlixReader;
import com.github.oeuvres.alix.lucene.search.Distrib;
import com.github.oeuvres.alix.lucene.search.Doc;
import com.github.oeuvres.alix.lucene.search.FormEnum;
import com.github.oeuvres.alix.util.ML;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "Keywords", description = "Extract all keywords of documents from a lucene index")
public class Keywords implements Callable<Integer>
{
    @Parameters(index = "0", arity = "1", paramLabel = "lucene_index/", description = "1 lucene index folder")
    /** Lucene index folder */
    File lucenefile;
    
    static final String BIBL = "bibl";
    static final String TEXT_CLOUD = "text_cloud";
    
    public Keywords()
    {
        super();
    }

    
    @Override
    public Integer call() throws Exception
    {
        final Set<String> fields = Set.of(ALIX_ID, ALIX_BOOKID, BIBL, "analytic");
        final AlixReader alixReader = AlixReader.instance(lucenefile.getName(), lucenefile.toPath());
        final IndexReader reader = alixReader.reader();
        final IndexSearcher searcher = new IndexSearcher(reader);
        final StoredFields storedFields = reader.storedFields();
        TopDocs results = searcher.search(
            new TermQuery(new Term(ALIX_TYPE, TEXT)), 
            100000, 
            new Sort(new SortField(ALIX_ID, SortField.Type.STRING))
        );
        ScoreDoc[] hits = results.scoreDocs;
        

        for (ScoreDoc src : hits) {
            final int docId = src.doc;
            final Document document = storedFields.document(docId, fields);
            final String bibl = document.get(BIBL).replaceAll("<a [^>]+>", "").replaceAll("</a>", "");
            System.out.println("<p>" + bibl + "</p>");
            
            FormEnum forms = Doc.formEnum(alixReader, docId, TEXT_CLOUD, Distrib.OCCS, true);
            printKeywords(forms, Distrib.FREQ); // OCCS = FREQ
            printKeywords(forms, Distrib.TFIDF);
            printKeywords(forms, Distrib.CHI2);
            printKeywords(forms, Distrib.BM25);
            printKeywords(forms, Distrib.G);
        }

        return 0;
    }
    
    private void printKeywords(FormEnum forms, Distrib distrib)
    {
        forms.score(distrib);
        forms.sort(FormEnum.Order.SCORE, 50, false);
        boolean first = true;
        System.out.print("<p>");
        System.out.print("<b>" + distrib.name() + "</b>: ");
        while (forms.hasNext()) {
            forms.next();
            String form = forms.form();
            if (first) first = false;
            else System.out.print(", ");
            System.out.print(ML.escape(form));
            System.out.print(" <small>(" + forms.freq() + ")</small>");
        }
        System.out.println("<p>");
    }




    /**
     * Send index loading.
     * 
     * @param args Command line arguments.
     * @throws Exception XML or Lucene errors.
     */
    public static void main(String[] args) throws Exception
    {
        int exitCode = new CommandLine(new Keywords()).execute(args);
        System.exit(exitCode);
    }

}
