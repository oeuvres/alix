package com.github.oeuvres.alix.lucene.terms;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

/**
 * Dumps all indexed terms of one Lucene field to a UTF-8 text file.
 */
public final class TermsDumper {
    private TermsDumper() {
    }

    /**
     * Dumps all indexed terms of one field.
     *
     * @param args command-line arguments: {@code <indexDir> <field> <outFile>}
     * @throws IOException if the index or output file cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        /*
        if (args.length != 3) {
            System.err.println("Usage: java DumpFieldTerms <indexDir> <field> <outFile>");
            System.exit(1);
        }
        */

        Path indexDir = Path.of("../web/lucene/piaget");
        String field = "content";
        Path outFile = Path.of("../web/target/content.txt");

        try (
                FSDirectory dir = FSDirectory.open(indexDir);
                DirectoryReader reader = DirectoryReader.open(dir);
                BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)
        ) {
            Terms terms = MultiTerms.getTerms(reader, field);
            if (terms == null) {
                throw new IllegalArgumentException("No terms for field: " + field);
            }
            writer.write("term\tdocs\toccs\n");
            PostingsEnum postings = null;
            TermsEnum tenum = terms.iterator();
            while (tenum.next() != null) {
                writer.write(tenum.term().utf8ToString());
                writer.write("\t" + tenum.docFreq());
                postings = tenum.postings(postings, PostingsEnum.FREQS);
                long freq = 0;
                for (int docId = postings.nextDoc(); docId != DocIdSetIterator.NO_MORE_DOCS; docId = postings.nextDoc()) {
                    freq += postings.freq();
                }
                writer.write("\t" + freq + "\n");
            }
            
        }
    }
}