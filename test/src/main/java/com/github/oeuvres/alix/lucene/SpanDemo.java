package com.github.oeuvres.alix.lucene;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Sort;
import org.apache.lucene.store.FSDirectory;

import com.github.oeuvres.alix.lucene.spans.SpanDocs;
import com.github.oeuvres.alix.lucene.spans.SpanQueryParser;

/**
 * Interactive command-line demo for {@link SpanDocs}.
 *
 * <h2>Usage</h2>
 * <pre>
 * SpanCoocDemo &lt;indexDir&gt; [textField] [yearField] [alixDocIdField] [storedField] [slop]
 * </pre>
 *
 * <p>All parameters after {@code indexDir} are optional and fall back to
 * the defaults shown below.</p>
 *
 * <h2>Query syntax</h2>
 * <p>Groups are separated by {@code ,} or newline.
 * Terms within a group are whitespace-separated and combined with OR.
 * Groups are combined with AND (proximity).</p>
 * <pre>
 * > libre liberté, responsable
 * > libre, responsable responsabilité
 * </pre>
 *
 * <h2>Special commands</h2>
 * <pre>
 * :slop N     change the span slop (default 19)
 * :ctx  N     change the excerpt context window in tokens (default 10)
 * :max  N     change the maximum number of hits to display (default 20)
 * :help       show this help
 * :quit       exit
 * </pre>
 */
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

        final PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        out.println("Opening index: " + indexDir);
        try (
            FSDirectory    dir    = FSDirectory.open(indexDir);
            IndexReader    reader = DirectoryReader.open(dir);
            BufferedReader stdin  = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
        ) {
            IndexSearcher  searcher = new IndexSearcher(reader);
            StoredFields storedFields = searcher.storedFields();
            final Set<String> fieldSet = Set.of("docline", "content");
            
            
            out.printf("Index opened — %d docs (%d leaves)%n",
                reader.numDocs(), reader.leaves().size());
            out.printf("Fields: text=%s  year=%s  alixId=%s  stored=%s%n",
                textField, yearField, alixIdField, storedField);

            int slop = args.length > 5 ? parseInt(args[5], DEFAULT_SLOP) : DEFAULT_SLOP;
            int ctx  = DEFAULT_CTX;
            int max  = DEFAULT_MAX;

            out.printf("slop=%d  ctx=%d  max=%d%n%n", slop, ctx, max);

            while (true) {
                out.print("> ");
                out.flush();

                final String line = stdin.readLine();
                if (line == null) break;           // EOF
                final String trimmed = line.strip();
                if (trimmed.isEmpty()) continue;

                // --- special commands ---
                if (trimmed.startsWith(":")) {
                    final String[] parts = trimmed.substring(1).split("\\s+", 2);
                    switch (parts[0]) {
                        case "quit", "q", "exit" -> { out.println("Bye."); return; }
                        case "slop" -> {
                            slop = parseInt(parts.length > 1 ? parts[1] : "", slop);
                            out.println("slop=" + slop);
                            continue;
                        }
                        case "ctx" -> {
                            ctx = parseInt(parts.length > 1 ? parts[1] : "", ctx);
                            out.println("ctx=" + ctx);
                            continue;
                        }
                        case "max" -> {
                            max = parseInt(parts.length > 1 ? parts[1] : "", max);
                            out.println("max=" + max);
                            continue;
                        }
                        default -> {
                            out.println("Unknown command. Type :help.");
                            continue;
                        }
                    }
                }

                // --- parse and run ---
                final SpanQuery query;
                try {
                    query = new SpanQueryParser(textField, slop).parse(trimmed);
                } catch (IllegalArgumentException e) {
                    out.println("Parse error: " + e.getMessage());
                    continue;
                }

                out.println("Query: " + query);
                if (!(query instanceof SpanNearQuery)) {
                    out.println("(single group — no proximity constraint; showing matching docs only)");
                }

                final long t0 = System.currentTimeMillis();
                Sort sort =  Sort.RELEVANCE;
                // Sort sort = Sort.INDEXORDER;
                try (SpanDocs sd = SpanDocs.search(searcher, query, null, sort, 1000)) {
                    final long ms = System.currentTimeMillis() - t0;
                    out.printf("%d hit(s) in %d ms%n%n", sd.size(), ms);

                    int shown = 0;
                    while (sd.next() && shown < max) {
                        shown++;
                        final int docId = sd.docId();
                        Document doc = storedFields.document(docId, fieldSet);
                        System.out.println(doc.get("docline"));
                        System.out.println("    spans=" + sd.spanCount());
                        String content = doc.get("content");
                        for (int spanOrd = 0, lim = Math.min(sd.spanCount(), 10); spanOrd < lim; spanOrd++) {
                            int spanStart = sd.spanStartOffset(spanOrd);
                            int spanEnd = sd.spanEndOffset(spanOrd);
                            System.out.print("  – " + spanOrd + ". ");
                            System.out.println(content.substring(spanStart, spanEnd));
                        }
                        
                    }
                    if (sd.size() > max) {
                        out.printf("  … %d more hits not shown (use :max N to see more)%n", sd.size() - max);
                    }

                } catch (Exception e) {
                    out.println("Error during search: " + e.getMessage());
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
