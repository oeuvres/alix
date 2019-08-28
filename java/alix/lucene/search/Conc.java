package alix.lucene.search;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;

import alix.lucene.Alix;
import alix.lucene.analysis.AnalyzerAlix;

/**
 * 
 * @author fred
 *
 */
@SuppressWarnings("deprecation")
public class Conc
{
    static String spaces;
    static {
        char[] cs = new char[200];
        Arrays.fill(cs, ' ');
        spaces = new String(cs);
    }
    public static void nomain(String args[]) throws Exception
    {
        Analyzer analyzer = new AnalyzerAlix();
        Directory directory = new RAMDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(directory, config);
        Document doc = new Document();
        String name = "text";
        String text = "Lucene is an Information Retrieval library written in Java";
        doc.add(new Field(name, text, Alix.ftypeAll));
        text = "Lucene est une librairie d'indexation plein texte en Java.";
        doc.add(new Field(name, text, Alix.ftypeAll));
        indexWriter.addDocument(doc);
        indexWriter.addDocument(doc);
        IndexReader ir=DirectoryReader.open(indexWriter);
        // IndexSearcher is = new IndexSearcher(ir);
        doc = ir.document(1);
        Terms vector = ir.getTermVector(0, name);
        System.out.println(vector.getStats());
        // vector.
        TermsEnum termit = vector.iterator();
        while(termit.next() != null) {
           //  System.out.println(termit.term().utf8ToString()+" "+termit.totalTermFreq()+" "+termit.docFreq()+" "+termit.postings(reuse, flags));
        }
        System.out.println(termit.seekCeil(new BytesRef("ple")));
        System.out.println(termit.term().utf8ToString());
        
        // offsets = new OffsetList(doc.getField("offsets").binaryValue());

        /*
        String usage = "java alix.lucene.Conc lucene-index content\n\n";
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String index = args[0];
        String content = args[1];
        IndexReader reader = DirectoryReader.open(FSDirectory.open(Paths.get(index)));
        IndexSearcher searcher = new IndexSearcher(reader);
        // Analyzer analyzer = new StandardAnalyzer();
        // SimpleQueryParser parser = new SimpleQueryParser(analyzer, content);
        
        TermQuery filter = new TermQuery(new Term(Alix.FILENAME, "bergson_evolution"));
       
        BooleanClause clause1 = new BooleanClause(filter, BooleanClause.Occur.FILTER);

        int lwidth = 50;
        int rwidth = 50;
        while (true) {
            System.out.println("Enter query: ");
            String line = in.readLine();
            if (line == null || line.trim().isEmpty()) break;
            // Query query = parser.parse(line);
            SpanQuery pivot = new SpanTermQuery(new Term(content, line));
            BooleanQuery bq = new BooleanQuery.Builder().add(filter, BooleanClause.Occur.FILTER).add(pivot, BooleanClause.Occur.MUST).build();
            
            
            int count = searcher.count(bq);
            System.out.println(count+" documents");
            SpanWeight spanWeight = bq.createWeight(searcher, false, 1);
            LeafReaderContext context = searcher.getIndexReader().leaves().iterator().next();
            System.out.println(spanWeight.getSpans(context, Postings.OFFSETS).getClass());
            TermSpans spans = (TermSpans) spanWeight.getSpans(context, Postings.OFFSETS);
            int docId = 0;

            while((docId = spans.nextDoc()) != Spans.NO_MORE_DOCS){
              System.out.println(spans.toString());
              System.out.println("docId="+docId);
              Document doc = reader.document(docId);
              String text = doc.get(content);
              TermList vector = reader.getTermVector(docId, content);
              System.out.println("TermList size="+vector.size());
              final TermsEnum termsEnum = vector.iterator();
              BytesRef termBytesRef;
              while ((termBytesRef = termsEnum.next()) != null) {
                  System.out.print(termBytesRef.utf8ToString());
                  System.out.print(" ");
              }
              while(spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
                  PostingsEnum postings = spans.getPostings();
                  int start = postings.startOffset();
                  int end = postings.endOffset();
                  int lstart = start - lwidth;
                  if (lstart < 0) {
                      System.out.print(spaces.substring(0, -lstart));
                      lstart = 0;
                  }
                  System.out.print(text.substring(lstart, start));
                  System.out.print(" | ");
                  System.out.print(text.substring(start, end));
                  System.out.print(" | ");
                  int rend = Math.min(end+rwidth+1, text.length());
                  System.out.println(text.substring(end+1, rend));
              }
            }
               // Date end = new Date();
        // info(end.getTime() - start.getTime() + " total ms.");
        }
        */
    }
    public static void main(String args[]) throws IOException
    {
      Path path = Paths.get("work/test");
      Files.walk(path)
      .map(Path::toFile)
      .forEach(File::delete);
      Files.createDirectories(path);
      // Directory dir = FSDirectory.open(indexPath);
      Directory dir = MMapDirectory.open(path); // https://dzone.com/articles/use-lucene’s-mmapdirectory

      Analyzer analyzer = new AnalyzerAlix();
      IndexWriterConfig conf = new IndexWriterConfig(analyzer);
      conf.setUseCompoundFile(false); // show separate file by segment
      conf.setMaxBufferedDocs(48); // 48 MB buffer
      
      conf.setOpenMode(OpenMode.CREATE_OR_APPEND);
      conf.setSimilarity(new BM25Similarity());
      // Optional: for better indexing performance, if you
      // are indexing many documents, increase the RAM
      // buffer. But if you do this, increase the max heap
      // size to the JVM (eg add -Xmx512m or -Xmx1g):
      //
      // conf.setRAMBufferSizeMB(256.0);
      IndexWriter lucwriter = new IndexWriter(dir, conf);
      String[] docs = {
          "aaa aab aac aba abb baa bba bab",
          "caa cbb aaa abb abc",
      };
      for(String text: docs) {
        Document doc = new Document();
        doc.add(new Field("text", text, Alix.ftypeAll));
        lucwriter.addDocument(doc);
      }
      lucwriter.commit();
      lucwriter.forceMerge(1);
      lucwriter.close();

    }

}
