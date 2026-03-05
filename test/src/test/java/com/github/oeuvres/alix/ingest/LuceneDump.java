package com.github.oeuvres.alix.ingest;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

public class LuceneDump
{
    // Prevent terminal spam when a field stores huge text (set to Integer.MAX_VALUE to disable).
    private static final int MAX_CHARS = 500;

    static void dump(Path indexPath) throws IOException 
    {
        try (Directory dir=FSDirectory.open(indexPath)){
            dump(dir);
        }
    }
    
    static void dump(Directory directory) throws IOException
    {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            
            final int pageSize = 1000;
            ScoreDoc after = null;

            while (true) {
              TopDocs hits = searcher.searchAfter(after, new MatchAllDocsQuery(), pageSize);
              if (hits.scoreDocs.length == 0) break;

              for (ScoreDoc sd : hits.scoreDocs) {
                int docId = sd.doc;
                // stored fields for this hit:
                var d = searcher.storedFields().document(docId);
                // ...process d...
                System.out.println("docId=" + docId);
                for (IndexableField f : d.getFields()) {
                    System.out.println(formatField(f));
                  }
              }
              after = hits.scoreDocs[hits.scoreDocs.length - 1];
            }
        }

    }

    private static String formatField(IndexableField f) {
        String name = f.name();

        // Prefer CharSequence if present (supports your AlixDocument-backed stored fields)
        CharSequence cs = f.getCharSequenceValue();
        if (cs != null) return name + " = " + preview(cs.toString());

        String s = f.stringValue();
        if (s != null) return name + " = " + preview(s);

        Number n = f.numericValue();
        if (n != null) return name + " = " + n;

        BytesRef b = f.binaryValue();
        if (b != null) return name + " = <binary " + b.length + " bytes>";

        return name + " = <null>";
      }

      private static String preview(String s) {
        if (s == null) return "null";
        String p = s.replace("\r", "\\r").replace("\n", "\\n");
        if (p.length() <= MAX_CHARS) return p;
        return p.substring(0, MAX_CHARS) + "…";
      }
}
