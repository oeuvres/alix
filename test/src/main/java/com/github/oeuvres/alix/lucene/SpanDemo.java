package com.github.oeuvres.alix.lucene;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;

import com.github.oeuvres.alix.lucene.spans.SpanQueryParser;
import com.github.oeuvres.alix.lucene.spans.SpanWalker;


public class SpanDemo {

    private static final String DEFAULT_TEXT_FIELD    = "content";
    private static final String DEFAULT_YEAR_FIELD    = "year";
    private static final String DEFAULT_ALIX_ID_FIELD = "alix.docid";
    private static final String DEFAULT_STORED_FIELD  = "content";
    private static final int    DEFAULT_SLOP           = 19;
    private static final int    DEFAULT_CTX            = 10;
    private static final int    DEFAULT_MAX            = 10;

    public static void main(final String[] args) throws IOException {


        final Path      indexDir      = Path.of("D:\\code\\alix\\web\\lucene\\piaget");
        final String    textField     = args.length > 1 ? args[1] : DEFAULT_TEXT_FIELD;
        final String    yearField     = args.length > 2 ? args[2] : DEFAULT_YEAR_FIELD;
        final String    alixIdField   = args.length > 3 ? args[3] : DEFAULT_ALIX_ID_FIELD;
        final String    storedField   = args.length > 4 ? args[4] : DEFAULT_STORED_FIELD;

        Writer writer = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
        
        writer.append("Opening index: " + indexDir + "\n");
        try (
            FSDirectory    dir    = FSDirectory.open(indexDir);
            IndexReader    reader = DirectoryReader.open(dir);
            BufferedReader stdin  = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
        ) {
            IndexSearcher  searcher = new IndexSearcher(reader);
            StoredFields storedFields = searcher.storedFields();
            
                        
            writer.append("Index opened — ")
                .append(""+reader.numDocs()).append(" docs")
                .append("("+reader.leaves().size()).append(" leaves)")
                .append("\n");

            int slop = args.length > 5 ? parseInt(args[5], DEFAULT_SLOP) : DEFAULT_SLOP;
            int ctx  = DEFAULT_CTX;
            int max  = DEFAULT_MAX;

            writer.append("slop=").append(String.valueOf(slop))
                .append(" ctx=").append(String.valueOf(ctx))
                .append(" max=").append(String.valueOf(max))
                .append("\n\n");
            
            

            while (true) {
                writer.append("> ");
                writer.flush();

                final String line = stdin.readLine();
                if (line == null) break;           // EOF
                final String trimmed = line.strip();
                if (trimmed.isEmpty()) continue;

                // --- special commands ---
                if (trimmed.startsWith(":")) {
                    final String[] parts = trimmed.substring(1).split("\\s+", 2);
                    switch (parts[0]) {
                        case "quit", "q", "exit" -> { writer.append("Bye.\n"); return; }
                        case "slop" -> {
                            slop = parseInt(parts.length > 1 ? parts[1] : "", slop);
                            writer.append("slop=" + slop + "\n");
                            continue;
                        }
                        case "ctx" -> {
                            ctx = parseInt(parts.length > 1 ? parts[1] : "", ctx);
                            writer.append("ctx=" + ctx + "\n");
                            continue;
                        }
                        case "max" -> {
                            max = parseInt(parts.length > 1 ? parts[1] : "", max);
                            writer.append("max=" + max + "\n");
                            continue;
                        }
                        default -> {
                            writer.append("Unknown command. Type :help." + "\n");
                            continue;
                        }
                    }
                }

                // --- parse and run ---
                final long t0 = System.currentTimeMillis();
                final SpanQuery query;
                try {
                    query = new SpanQueryParser(textField, slop).parse(trimmed);
                } catch (IllegalArgumentException e) {
                    writer.append("Parse error: " + e.getMessage() + "\n");
                    continue;
                }

                writer.append("Query: " + query + "\n");
                if (!(query instanceof SpanNearQuery)) {
                    writer.append("(single group — no proximity constraint; showing matching docs only)\n");
                }

                
                
                
                try {
                    HtmlResults results = new HtmlResults(writer, storedFields, storedField)
                        .doclineFieldName("docline")
                        .docLimit(20)
                        .spanLimit(5)
                        .ctx(20);
                    SpanWalker walker = new SpanWalker(searcher, query, null, results);
                    writer.append(String.valueOf(walker.hits())).append(" hits " + (System.currentTimeMillis() - t0) + "ms \n");
                    int nextDoc = walker.walk(0);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }




    private static int parseInt(final String s, final int fallback) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
