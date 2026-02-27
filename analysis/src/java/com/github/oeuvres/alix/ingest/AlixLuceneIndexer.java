package com.github.oeuvres.alix.ingest;

import java.io.IOException;
import java.io.Reader;
import java.util.Objects;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.xml.sax.SAXException;

import com.github.oeuvres.alix.ingest.AlixSaxHandler.AlixDocumentConsumer;
import com.github.oeuvres.alix.util.Report;
import com.github.oeuvres.alix.util.Report.ReportNull;
import static com.github.oeuvres.alix.common.Names.*;

/**
 * Builds and indexes a Lucene {@link org.apache.lucene.document.Document} from an {@link AlixDocument}.
 *
 * Field mapping (current scope only; no extra features):
 *
 * STORE:
 * - stored only (CharSequence-backed) under same name.
 *
 * INT:
 * - IntPoint(name, v) + StoredField(name, v).
 *
 * CATEGORY (unique per document, warn on duplicates; keep first):
 * - postings: keyword (not tokenized) under same name
 * - docvalues: SortedDocValuesField under same name (single-valued; required for sorting)
 *
 * FACET (repeatable tags):
 * - postings: keyword (not tokenized) under same name (fast filtering via postings)
 * - docvalues: SortedSetDocValuesField under same name (for faceting/grouping; not for fast filtering)
 *
 * META:
 * - stored + indexed (tokenized) under same name
 *
 * TEXT:
 * - base text (source==null): stored under (name + storedTextSuffix) and indexed under name with TokenStream
 * - derived text (source!=null): indexed under name, using source text occurrences (all matches if repeated)
 *
 * include/exclude:
 * - forwarded to textAnalyzer per field.
 *
 * Notes:
 * - SortedDocValuesField does NOT allow multiple values per doc per field name; we enforce “keep first”.
 * - If a TEXT source field name is repeated, derived fields apply to ALL matching base occurrences
 * (multi-valued Lucene field semantics).
 */
public final class AlixLuceneIndexer implements AlixDocumentConsumer
{
    @FunctionalInterface
    public interface ZoneTextAnalyzer
    {
        TokenStream tokenStream(String fieldName, Reader reader, String include, String exclude) throws IOException;
    }
    
    private static final FieldType STORED_ONLY;
    private static final FieldType KEYWORD_POSTINGS;
    private static final FieldType META_TEXT;
    private static final FieldType TEXT_INDEXED_TS;
    
    static {
        FieldType st = new FieldType();
        st.setStored(true);
        st.setTokenized(false);
        st.setIndexOptions(IndexOptions.NONE);
        st.freeze();
        STORED_ONLY = st;
        
        FieldType kw = new FieldType();
        kw.setStored(true);
        kw.setTokenized(false);
        kw.setIndexOptions(IndexOptions.DOCS);
        kw.freeze();
        KEYWORD_POSTINGS = kw;
        
        FieldType mt = new FieldType();
        mt.setStored(true);
        mt.setTokenized(true);
        mt.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        mt.freeze();
        META_TEXT = mt;
        
        FieldType ti = new FieldType();
        ti.setStored(false);
        ti.setTokenized(true);
        ti.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        ti.freeze();
        TEXT_INDEXED_TS = ti;
    }
    
    private final IndexWriter writer;
    private final ZoneTextAnalyzer textAnalyzer;
    private final Report report;
    
    private final boolean indexBookDocs;
    private final boolean updateById;
    
    public AlixLuceneIndexer(IndexWriter writer,
            ZoneTextAnalyzer textAnalyzer,
            Report report,
            boolean indexBookDocs,
            boolean updateById)
    {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.textAnalyzer = Objects.requireNonNull(textAnalyzer, "textAnalyzer");
        this.report = (report != null) ? report : ReportNull.INSTANCE;
        this.indexBookDocs = indexBookDocs;
        this.updateById = updateById;
    }
    
    @Override
    public void accept(AlixDocument d) throws SAXException
    {
        if (d.documentType() == AlixDocument.DocumentType.BOOK && !indexBookDocs) return;

        final Document doc = new Document();

        final String docId = d.documentId();
        if (docId != null && !docId.isBlank()) {
            doc.add(new StringField(ALIX_ID, docId, Field.Store.YES));
        }

        // CATEGORY uniqueness tracking (keep first per CATEGORY field name)
        // Small-N => linear scan storage.
        String[] seenCatNames = null;
        int seenCatCount = 0;

        // Canonical order: STORE, INT, CATEGORY, FACET, META, TEXT
        for (AlixDocument.FieldType t : AlixDocument.FieldType.CANONICAL_ORDER)
        {
            for (int i = 0; i < d.fieldCount(); i++)
            {
                final AlixDocument.AlixField f = d.fieldAt(i);
                if (f.type != t) continue;
                // can’t reuse char sequence
                final CharSequence value = f.getCharSequence(d);
                final String name = f.name;

                try
                {
                    switch (f.type)
                    {
                        case STORE -> {
                            doc.add(new StoredField(name, value, STORED_ONLY));
                        }

                        case INT -> {
                            try {
                                final int v = Integer.parseInt(value, 0, value.length(), 10);
                                doc.add(new IntPoint(name, v));
                                doc.add(new StoredField(name, v));
                            }
                            catch (NumberFormatException e) {
                                report.warn(value + ": int parse error (docId=" + d.documentId() +")");
                            }
                            // final int v = parseInt(f.getCharSequence(d)., int offset, int len);
                        }

                        case CATEGORY -> {
                            // enforce unique per doc per CATEGORY field name
                            boolean dup = false;
                            if (seenCatNames != null) {
                                for (int k = 0; k < seenCatCount; k++) {
                                    if (seenCatNames[k].equals(f.name)) { dup = true; break; }
                                }
                            }
                            if (dup) {
                                report.warn("docId=" + docId + " duplicate CATEGORY '" + f.name + "' (keeping first)");
                                continue;
                            }
                            if (seenCatNames == null) seenCatNames = new String[8];
                            if (seenCatCount == seenCatNames.length) {
                                String[] n = new String[seenCatCount + (seenCatCount >> 1) + 1];
                                System.arraycopy(seenCatNames, 0, n, 0, seenCatCount);
                                seenCatNames = n;
                            }
                            seenCatNames[seenCatCount++] = f.name;

                            // postings keyword for fast filtering
                            doc.add(new Field(f.name, f.getCharSequence(d), KEYWORD_POSTINGS));

                            // docvalues for sorting/faceting on category (single-valued)
                            // Requires BytesRef => inevitable UTF-8 conversion; value is small.
                            doc.add(new SortedDocValuesField(f.name, new BytesRef(f.getCharSequence(d).toString())));
                        }

                        case FACET -> {
                            // postings keyword for fast filtering
                            doc.add(new Field(f.name, f.getCharSequence(d), KEYWORD_POSTINGS));

                            // docvalues set for faceting/grouping
                            doc.add(new SortedSetDocValuesField(f.name, new BytesRef(f.getCharSequence(d).toString())));
                        }

                        case META -> {
                            // stored + indexed (tokenized) from CharSequence (uses IW analyzer)
                            doc.add(new Field(f.name, f.getCharSequence(d), META_TEXT));
                        }

                        case TEXT -> {
                            if (f.source == null) {
                                // Base TEXT: store under <name> + suffix, index under <name>
                                doc.add(new StoredField(name, value, STORED_ONLY));

                                try (Reader r = d.openReader(f.off, f.len)) {
                                    TokenStream ts = textAnalyzer.tokenStream(f.name, r, f.include, f.exclude);
                                    doc.add(new Field(f.name, ts, TEXT_INDEXED_TS));
                                }

                                // Word-count/stats design note:
                                // If you need word counts known at analysis time:
                                // - wrap TokenStream with a counting TokenFilter and store counts externally,
                                //   then update docvalues in a second step (IndexWriter.updateDocValues), OR
                                // - run analysis twice (once to count, once to index).
                            }
                            else {
                                // Derived TEXT: apply to ALL matching base sources if repeated
                                int srcCount = 0;
                                for (int j = 0; j < d.fieldCount(); j++) {
                                    AlixDocument.AlixField src = d.fieldAt(j);
                                    if (src.type == AlixDocument.FieldType.TEXT
                                            && src.source == null
                                            && f.source.equals(src.name)) {
                                        srcCount++;
                                        try (Reader r = d.openReader(src.off, src.len)) {
                                            TokenStream ts = textAnalyzer.tokenStream(f.name, r, f.include, f.exclude);
                                            doc.add(new Field(f.name, ts, TEXT_INDEXED_TS));
                                        }
                                    }
                                }
                                if (srcCount == 0) {
                                    report.warn("docId=" + docId + " missing source='" + f.source
                                            + "' for derived text field '" + f.name + "'");
                                }
                            }
                        }
                    }
                }
                catch (IOException e) {
                    throw new SAXException("I/O while analyzing field '" + f.name + "'", e);
                }
                catch (RuntimeException e) {
                    throw new SAXException("Error building Lucene fields from '" + f.name + "' type=" + f.type, e);
                }
            }
        }

        try {
            if (updateById && docId != null && !docId.isBlank()) {
                writer.updateDocument(new Term(ALIX_ID, docId), doc);
            } else {
                writer.addDocument(doc);
            }
        }
        catch (IOException e) {
            throw new SAXException("IndexWriter failure", e);
        }
    }
    
}
