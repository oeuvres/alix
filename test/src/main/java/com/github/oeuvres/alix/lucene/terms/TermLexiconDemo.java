package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.nio.file.Path;

/**
 * Minimal demo for the intended TermLexicon API.
 *
 * Usage:
 *   java ... TermLexiconDemo /path/to/index myField intelligence
 */
public final class TermLexiconDemo {
    private TermLexiconDemo() {
    }

    public static void main(String[] args) throws Exception {

        final Path indexDir = Path.of("../web/lucene/piaget");
        final String field = "content";
        final String queryTerm = "juste";

        try (
            FSDirectory directory = FSDirectory.open(indexDir);
            DirectoryReader reader = DirectoryReader.open(directory);
        ) {


            // 2) Open the lexicon and do the two core lookups.
            final TermLexicon lexicon = TermLexicon.openOrBuild(reader, indexDir, field);
            System.out.println("field     = " + lexicon.field());
            System.out.println("vocabSize = " + lexicon.vocabSize());
    
            final int termId = lexicon.id(queryTerm);
            System.out.println("id(" + queryTerm + ") = " + termId);
            if (termId < 0) {
                System.out.println("term not found");
                return;
            }
    
            System.out.println("term(" + termId + ") = " + lexicon.term(termId));


            final BytesRef termBytes = new BytesRef(lexicon.term(termId));
            final PostingsEnum postings = MultiTerms.getTermPostingsEnum(
                reader,
                field,
                termBytes,
                PostingsEnum.POSITIONS
            );

            if (postings == null) {
                System.out.println("no postings for term");
                return;
            }

            int docs = 0;
            int occs = 0;
            for (int docId = postings.nextDoc(); docId != PostingsEnum.NO_MORE_DOCS; docId = postings.nextDoc()) {
                docs++;
                final int freq = postings.freq();
                occs += freq;
                System.out.print("doc=" + docId + " freq=" + freq + " pos=");
                for (int i = 0; i < freq; i++) {
                    if (i > 0) System.out.print(',');
                    System.out.print(postings.nextPosition());
                }
                System.out.println();
            }

            System.out.println("docs=" + docs + " occs=" + occs);
        }
    }
}
