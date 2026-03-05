package com.github.oeuvres.alix.ingest;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Objects;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.xml.sax.SAXException;

import com.github.oeuvres.alix.ingest.AlixDocument.AlixField;
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
 * TEXT:
 * - base text (source==null): stored under (name + storedTextSuffix) and indexed under name with TokenStream
 * - derived text (source!=null): indexed under name, using source text occurrences (all matches if repeated)
 *
 * Notes:
 * - SortedDocValuesField does NOT allow multiple values per doc per field name; we enforce “keep first”.
 * - If a TEXT source field name is repeated, derived fields apply to ALL matching base occurrences
 * (multi-valued Lucene field semantics).
 */
public final class AlixLuceneIndexer implements AlixDocumentConsumer
{
    
    private static final FieldType STORED_ONLY;
    private static final FieldType KEYWORD_POSTINGS;
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
        
        FieldType ti = new FieldType();
        ti.setStored(false);
        ti.setTokenized(true);
        ti.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        ti.freeze();
        TEXT_INDEXED_TS = ti;
    }
    
    private final IndexWriter writer;
    private final Report report;
    
    public AlixLuceneIndexer(IndexWriter writer, Report report)
    {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.report = (report != null) ? report : ReportNull.INSTANCE;
    }
    
    @Override
    public void accept(AlixDocument alixDoc) throws SAXException
    {
        
        final Document luceneDoc = new Document();
        
        final String docId = alixDoc.docId();
        if (docId != null && !docId.isBlank()) {
            luceneDoc.add(new StringField(ALIX_ID, docId, Field.Store.YES));
        }
        
        // CATEGORY uniqueness tracking (keep first per CATEGORY field name)
        // Small-N => linear scan storage.
        String[] seenCatNames = null;
        int seenCatCount = 0;
        
        for (int i = 0; i < alixDoc.fieldCount(); i++) {
            final AlixField alixField = alixDoc.fieldAt(i);
            // can’t reuse char sequence
            final String name = alixField.name;
            final String value = alixField.getValueAsString();
            
            try {
                switch (alixField.type) {
                    case STORE -> {
                        luceneDoc.add(new StoredField(name, value, STORED_ONLY));
                    }
                    
                    case INT -> {
                        try {
                            final int v = Integer.parseInt(value, 0, value.length(), 10);
                            luceneDoc.add(new IntPoint(name, v));
                            luceneDoc.add(new StoredField(name, v));
                        } catch (NumberFormatException e) {
                            report.warn(value + ": int parse error (docId=" + alixDoc.docId() + ")");
                        }
                        // final int v = parseInt(f.getCharSequence(d)., int offset, int len);
                    }
                    
                    case CATEGORY -> {
                        // enforce unique per doc per CATEGORY field name
                        boolean dup = false;
                        if (seenCatNames != null) {
                            for (int k = 0; k < seenCatCount; k++) {
                                if (seenCatNames[k].equals(alixField.name)) {
                                    dup = true;
                                    break;
                                }
                            }
                        }
                        if (dup) {
                            report.warn("docId=" + docId + " duplicate CATEGORY '" + alixField.name + "' (keeping first)");
                            continue;
                        }
                        if (seenCatNames == null)
                            seenCatNames = new String[8];
                        if (seenCatCount == seenCatNames.length) {
                            String[] n = new String[seenCatCount + (seenCatCount >> 1) + 1];
                            System.arraycopy(seenCatNames, 0, n, 0, seenCatCount);
                            seenCatNames = n;
                        }
                        seenCatNames[seenCatCount++] = alixField.name;
                        
                        // postings keyword for fast filtering
                        luceneDoc.add(new Field(alixField.name, value, KEYWORD_POSTINGS));
                        
                        // docvalues for sorting/faceting on category (single-valued)
                        // Requires BytesRef => inevitable UTF-8 conversion; value is small.
                        luceneDoc.add(new SortedDocValuesField(alixField.name, new BytesRef(value.toString())));
                    }
                    
                    case FACET -> {
                        // postings keyword for fast filtering
                        luceneDoc.add(new Field(alixField.name, value, KEYWORD_POSTINGS));
                        
                        // docvalues set for faceting/grouping
                        luceneDoc.add(new SortedSetDocValuesField(alixField.name, new BytesRef(value.toString())));
                    }
                    
                    
                    case TEXT -> {
                        if (alixField.source == null) {
                            // Base TEXT: store under <name> + suffix, index under <name>
                            luceneDoc.add(new StoredField(name, value, STORED_ONLY));
                            
                            // feel field with a TokenStream break the Analyzer reuse logic
                            // no need of a reader here, store need a string
                            luceneDoc.add(new Field(alixField.name, value, TEXT_INDEXED_TS));
                            
                            // Word-count/stats design note:
                            // If you need word counts known at analysis time:
                            // - wrap TokenStream with a counting TokenFilter and store counts externally,
                            //   then update docvalues in a second step (IndexWriter.updateDocValues), OR
                            // - run analysis twice (once to count, once to index).
                        } else {
                            // Derived TEXT: apply to ALL matching base sources if repeated
                            int srcCount = 0;
                            for (int j = 0; j < alixDoc.fieldCount(); j++) {
                                AlixField src = alixDoc.fieldAt(j);
                                if (src.type == AlixDocument.FieldType.TEXT
                                        && src.source == null
                                        && alixField.source.equals(src.name))
                                {
                                    srcCount++;
                                    Field luceneField = new Field(alixField.name, src.getValueAsString(), TEXT_INDEXED_TS);
                                    // TokenStream ts = textAnalyzer.tokenStream(alixField.name, src.getValueAsString());
                                    luceneDoc.add(luceneField);
                                }
                            }
                            if (srcCount == 0) {
                                report.warn("docId=" + docId + " missing source='" + alixField.source
                                        + "' for derived text field '" + alixField.name + "'");
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                throw new SAXException("Error building Lucene fields from '" + alixField.name + "' type=" + alixField.type, e);
            }
        }
        
        try {
            writer.updateDocument(new Term(ALIX_ID, docId), luceneDoc);
        } catch (IOException e) {
            System.err.println(luceneDoc);
            throw new SAXException("IndexWriter failure", e);
        }
    }
    
}
