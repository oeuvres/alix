package com.github.oeuvres.alix.lucene.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

public class AlixWriter
{
    /** Lucene field type for alix text field */
    public static final FieldType ftypeText = new FieldType();
    static {
        ftypeText.setTokenized(true);
        // freqs required, position needed for co-occurrences
        ftypeText.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        ftypeText.setOmitNorms(false); // keep norms for Similarity, http://makble.com/what-is-lucene-norms
        ftypeText.setStoreTermVectors(true);
        ftypeText.setStoreTermVectorPositions(true);
        ftypeText.setStoreTermVectorOffsets(true);
        ftypeText.setStored(false); // TokenStream fields cannot be stored
        ftypeText.freeze();
    }
    /** lucene field type for alix meta type */
    public static final FieldType ftypeMeta = new FieldType();
    static {
        ftypeMeta.setTokenized(true); // token
        ftypeMeta.setIndexOptions(IndexOptions.DOCS_AND_FREQS); // no position needed
        ftypeMeta.setOmitNorms(false); // keep norms for Similarity, http://makble.com/what-is-lucene-norms
        ftypeMeta.setStoreTermVectors(true); // store term vectors, hilite by automat not robust enough
        ftypeMeta.setStoreTermVectorPositions(true);
        ftypeMeta.setStoreTermVectorOffsets(true);
        ftypeMeta.setStored(false); // TokenStream fields cannot be stored
        ftypeMeta.freeze();
    }

    public static IndexWriter writer(final Path path, final Analyzer analyzer) throws IOException
    {
        Files.createDirectories(path);
        Directory dir = FSDirectory.open(path);
        IndexWriterConfig conf = new IndexWriterConfig(analyzer);
        // Use false for batch indexing with very large ram buffer settings.
        conf.setUseCompoundFile(false);
        // may needed, increase the max heap size to the JVM (eg add -Xmx512m or  -Xmx1g):
        conf.setRAMBufferSizeMB(1024.0);
        conf.setOpenMode(OpenMode.CREATE);
        // no effect found with modification ConcurrentMergeScheduler
        /*
         * int threads = Runtime.getRuntime().availableProcessors() - 1;
         * ConcurrentMergeScheduler cms = new ConcurrentMergeScheduler();
         * cms.setMaxMergesAndThreads(threads, threads); cms.disableAutoIOThrottle();
         * conf.setMergeScheduler(cms);
         */
        // order docId by a field after merge ? No functionality should rely on such
        // order
        // conf.setIndexSort(new Sort(new SortField(YEAR, SortField.Type.INT)));
        IndexWriter writer = new IndexWriter(dir, conf);
        return writer;
    }
}
