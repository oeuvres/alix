package com.github.oeuvres.alix.lucene.vecs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * Exports a Lucene field as the three text inputs expected by BIU-NLP/word2vecf.
 *
 * <p>The training stream preserves term frequency: if a selected term occurs
 * {@code tf} times in a live document, its {@code (term, document)} pair is
 * emitted {@code tf} times. The complete occurrence stream is then shuffled.</p>
 *
 * <p>Outputs:</p>
 * <ul>
 *   <li>{@code <prefix>.pairs}: one UTF-8 {@code "term DOC_n"} pair per line;</li>
 *   <li>{@code <prefix>.wv}: word vocabulary, {@code "term documentFrequency"};</li>
 *   <li>{@code <prefix>.cv}: context vocabulary, {@code "DOC_n degree"}.</li>
 * </ul>
 *
 * <p>The pair file is deterministically shuffled. This matters because
 * word2vecf reads explicit pairs in file order and does not shuffle them.</p>
 *
 * <p>The first context-vocabulary entry is an unused {@code __DUMMY__ 0}.
 * word2vecf inherits a special case from word2vec that prevents context index
 * zero from being sampled as a negative; reserving index zero avoids giving a
 * real Lucene document that accidental privilege.</p>
 */
public final class Word2vecfData
{
    private static final int IO_BUFFER = 1 << 20;
    private static final int WORD2VECF_MAX_TOKEN_BYTES = 99;
    private static final String CONTEXT_PREFIX = "DOC_";
    private static final String DUMMY_CONTEXT = "__DUMMY__";

    private record Word(String text, long totalFreq) {}

    /** Primitive growable long array, avoiding one object per positive pair. */
    private static final class LongList
    {
        private long[] values;
        private int size;

        LongList(final int initialCapacity)
        {
            values = new long[Math.max(16, initialCapacity)];
        }

        void add(final long value)
        {
            if (size == values.length) {
                final int next = Math.max(size + 1, size + (size >>> 1));
                final long[] grown = new long[next];
                System.arraycopy(values, 0, grown, 0, size);
                values = grown;
            }
            values[size++] = value;
        }

        int size()
        {
            return size;
        }

        long get(final int index)
        {
            return values[index];
        }

        void swap(final int a, final int b)
        {
            final long tmp = values[a];
            values[a] = values[b];
            values[b] = tmp;
        }
    }

    private Word2vecfData()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        if (args.length < 2) {
            usage();
            return;
        }

        final Path indexDir = Path.of(args[0]).toAbsolutePath().normalize();
        final String field = args[1];

        Path outDir = Path.of(".").toAbsolutePath().normalize();
        String prefix = "word2vecf-" + safeFilePart(field);
        int minDocFreq = 1;
        long seed = 13L;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--outDir" -> outDir = Path.of(requireValue(args, ++i, "--outDir"))
                    .toAbsolutePath().normalize();
                case "--prefix" -> prefix = requireValue(args, ++i, "--prefix");
                case "--minDocFreq" -> minDocFreq = Integer.parseInt(
                    requireValue(args, ++i, "--minDocFreq"));
                case "--seed" -> seed = Long.parseLong(requireValue(args, ++i, "--seed"));
                case "--help", "-h" -> {
                    usage();
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        if (minDocFreq < 1) {
            throw new IllegalArgumentException("minDocFreq must be >= 1");
        }

        Files.createDirectories(outDir);
        final Path pairsPath = outDir.resolve(prefix + ".pairs");
        final Path wordVocabPath = outDir.resolve(prefix + ".wv");
        final Path contextVocabPath = outDir.resolve(prefix + ".cv");
        ensureAbsent(pairsPath);
        ensureAbsent(wordVocabPath);
        ensureAbsent(contextVocabPath);

        try (FSDirectory directory = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            export(reader, field, minDocFreq, seed,
                pairsPath, wordVocabPath, contextVocabPath);
        }
    }

    private static void export(
        final IndexReader reader,
        final String field,
        final int minDocFreq,
        final long seed,
        final Path pairsPath,
        final Path wordVocabPath,
        final Path contextVocabPath
    ) throws IOException {
        final Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            throw new IllegalArgumentException("Field not found or without terms: " + field);
        }

        final BitSet liveDocs = liveDocs(reader);
        final int maxDoc = reader.maxDoc();
        final long[] contextCounts = new long[maxDoc];
        final List<Word> words = new ArrayList<>();
        final long sumTotalTermFreq = terms.getSumTotalTermFreq();
        final int expectedPairs = (sumTotalTermFreq > 0 && sumTotalTermFreq < Integer.MAX_VALUE - 8)
            ? (int) sumTotalTermFreq
            : (1 << 20);
        final LongList pairs = new LongList(expectedPairs);

        final TermsEnum tenum = terms.iterator();
        PostingsEnum postings = null;
        BytesRef termBytes;
        long scannedTerms = 0;
        long skippedShortDf = 0;

        while ((termBytes = tenum.next()) != null) {
            scannedTerms++;
            final String word = word2vecfToken(termBytes.utf8ToString(), "Lucene term");

            // docFreq may include deleted documents, so collect and count live
            // postings explicitly before applying minDocFreq.
            int[] docs = new int[Math.max(1, tenum.docFreq())];
            int[] freqs = new int[Math.max(1, tenum.docFreq())];
            int liveDf = 0;
            long liveTf = 0;
            postings = tenum.postings(postings, PostingsEnum.FREQS);
            for (int docId = postings.nextDoc();
                    docId != DocIdSetIterator.NO_MORE_DOCS;
                    docId = postings.nextDoc()) {
                if (!liveDocs.get(docId)) {
                    continue;
                }
                if (liveDf == docs.length) {
                    final int next = docs.length + Math.max(16, docs.length >>> 1);
                    final int[] grownDocs = new int[next];
                    final int[] grownFreqs = new int[next];
                    System.arraycopy(docs, 0, grownDocs, 0, docs.length);
                    System.arraycopy(freqs, 0, grownFreqs, 0, freqs.length);
                    docs = grownDocs;
                    freqs = grownFreqs;
                }
                final int freq = postings.freq();
                docs[liveDf] = docId;
                freqs[liveDf] = freq;
                liveDf++;
                liveTf += freq;
            }

            if (liveDf < minDocFreq) {
                skippedShortDf++;
                continue;
            }

            final int wordId = words.size();
            words.add(new Word(word, liveTf));
            for (int i = 0; i < liveDf; i++) {
                final int docId = docs[i];
                final int freq = freqs[i];
                for (int k = 0; k < freq; k++) {
                    pairs.add(pack(wordId, docId));
                }
                contextCounts[docId] += freq;
            }
        }

        if (words.isEmpty()) {
            throw new IllegalArgumentException(
                "No terms remain for field " + field + " with minDocFreq=" + minDocFreq);
        }
        if (pairs.size() == 0) {
            throw new IllegalStateException("No positive term-document pairs produced");
        }

        shuffle(pairs, seed);

        writePairs(pairsPath, pairs, words);
        writeWordVocab(wordVocabPath, words);
        final int contextCount = writeContextVocab(contextVocabPath, contextCounts);

        long tokenSum = 0;
        int liveFieldDocs = 0;
        long maxContextTokens = 0;
        for (int docId = 0; docId < contextCounts.length; docId++) {
            final long count = contextCounts[docId];
            if (count <= 0) continue;
            liveFieldDocs++;
            tokenSum += count;
            maxContextTokens = Math.max(maxContextTokens, count);
        }

        if (tokenSum != pairs.size()) {
            throw new IllegalStateException(
                "Internal count mismatch: context token sum=" + tokenSum
                    + " pairs=" + pairs.size());
        }

        System.out.printf("index maxDoc       %,d%n", reader.maxDoc());
        System.out.printf("index liveDocs     %,d%n", liveDocs.cardinality());
        System.out.printf("field docs         %,d%n", liveFieldDocs);
        System.out.printf("terms scanned      %,d%n", scannedTerms);
        System.out.printf("terms selected     %,d%n", words.size());
        System.out.printf("terms below minDF  %,d%n", skippedShortDf);
        System.out.printf("training pairs     %,d%n", pairs.size());
        System.out.printf("contexts           %,d (+ dummy)%n", contextCount);
        System.out.printf("max context tokens %,d%n", maxContextTokens);
        System.out.printf("shuffle seed       %d%n", seed);
        System.out.println("pairs              " + pairsPath);
        System.out.println("word vocabulary    " + wordVocabPath);
        System.out.println("context vocabulary " + contextVocabPath);
    }

    private static void writePairs(
        final Path path,
        final LongList pairs,
        final List<Word> words
    ) throws IOException {
        try (BufferedWriter out = writer(path)) {
            for (int i = 0; i < pairs.size(); i++) {
                final long pair = pairs.get(i);
                final int wordId = unpackWord(pair);
                final int docId = unpackDoc(pair);
                out.write(words.get(wordId).text());
                out.write(' ');
                out.write(CONTEXT_PREFIX);
                out.write(Integer.toString(docId));
                out.newLine();
            }
        }
    }

    private static void writeWordVocab(final Path path, final List<Word> words)
        throws IOException
    {
        // word2vecf re-sorts the loaded vocabulary by count internally. Sorting
        // here merely makes the file easy to inspect.
        final List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingLong(Word::totalFreq).reversed()
            .thenComparing(Word::text));

        try (BufferedWriter out = writer(path)) {
            for (Word word : sorted) {
                out.write(word.text());
                out.write(' ');
                out.write(Long.toString(word.totalFreq()));
                out.newLine();
            }
        }
    }

    private static int writeContextVocab(final Path path, final long[] contextCounts)
        throws IOException
    {
        final List<Integer> docs = new ArrayList<>();
        for (int docId = 0; docId < contextCounts.length; docId++) {
            if (contextCounts[docId] > 0) {
                docs.add(docId);
            }
        }
        docs.sort(Comparator
            .<Integer>comparingLong(docId -> contextCounts[docId]).reversed()
            .thenComparingInt(Integer::intValue));

        try (BufferedWriter out = writer(path)) {
            // word2vecf's negative sampler treats context index 0 specially.
            // A zero-count unused entry keeps every real document eligible for
            // negative sampling while contributing no mass to the unigram table.
            out.write(DUMMY_CONTEXT);
            out.write(" 0");
            out.newLine();

            for (int docId : docs) {
                out.write(CONTEXT_PREFIX);
                out.write(Integer.toString(docId));
                out.write(' ');
                out.write(Long.toString(contextCounts[docId]));
                out.newLine();
            }
        }
        return docs.size();
    }

    private static BufferedWriter writer(final Path path) throws IOException
    {
        return new BufferedWriter(
            new OutputStreamWriter(
                Files.newOutputStream(path, StandardOpenOption.CREATE_NEW),
                StandardCharsets.UTF_8),
            IO_BUFFER);
    }

    private static BitSet liveDocs(final IndexReader reader) throws IOException
    {
        final BitSet live = new BitSet(reader.maxDoc());
        for (LeafReaderContext ctx : reader.leaves()) {
            final LeafReader leaf = ctx.reader();
            final Bits bits = leaf.getLiveDocs();
            final int base = ctx.docBase;
            if (bits == null) {
                live.set(base, base + leaf.maxDoc());
                continue;
            }
            for (int localDoc = 0; localDoc < leaf.maxDoc(); localDoc++) {
                if (bits.get(localDoc)) {
                    live.set(base + localDoc);
                }
            }
        }
        return live;
    }

    private static long pack(final int wordId, final int docId)
    {
        return ((long) wordId << 32) | (docId & 0xffffffffL);
    }

    private static int unpackWord(final long pair)
    {
        return (int) (pair >>> 32);
    }

    private static int unpackDoc(final long pair)
    {
        return (int) pair;
    }

    private static void shuffle(final LongList pairs, final long seed)
    {
        final SplittableRandom random = new SplittableRandom(seed);
        for (int i = pairs.size() - 1; i > 0; i--) {
            final int j = random.nextInt(i + 1);
            pairs.swap(i, j);
        }
    }

    private static String word2vecfToken(final String token, final String kind)
    {
        if (token.isEmpty()) {
            throw new IllegalArgumentException(kind + " is empty");
        }
        final StringBuilder buf = new StringBuilder(token.length());
        for (int i = 0; i < token.length();) {
            final int cp = token.codePointAt(i);
            buf.appendCodePoint(Character.isWhitespace(cp) ? '_' : cp);
            i += Character.charCount(cp);
        }
        final String normalized = buf.toString();
        final int utf8Length = normalized.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Length > WORD2VECF_MAX_TOKEN_BYTES) {
            throw new IllegalArgumentException(
                kind + " exceeds word2vecf's 99-byte token limit after whitespace replacement ("
                    + utf8Length + "): " + normalized);
        }
        return normalized;
    }

    private static String safeFilePart(final String value)
    {
        final String safe = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isEmpty() ? "field" : safe;
    }

    private static String requireValue(final String[] args, final int index, final String option)
    {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value after " + option);
        }
        return args[index];
    }

    private static void ensureAbsent(final Path path) throws IOException
    {
        if (Files.exists(path)) {
            throw new IOException("Refusing to overwrite existing file: " + path);
        }
    }

    private static void usage()
    {
        System.out.println("Usage:");
        System.out.println("  Word2vecfData <luceneIndex> <field> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --outDir DIR       output directory (default: current directory)");
        System.out.println("  --prefix NAME      output basename (default: word2vecf-<field>)");
        System.out.println("  --minDocFreq N     retain terms occurring in at least N live docs (default: 1)");
        System.out.println("  --seed N           deterministic pair-shuffle seed (default: 13)");
        System.out.println();
        System.out.println("Outputs:");
        System.out.println("  <prefix>.pairs     shuffled term-document pairs, repeated by term frequency");
        System.out.println("  <prefix>.wv        word vocabulary: term totalFrequency");
        System.out.println("  <prefix>.cv        context vocabulary: document selectedTokenCount");
    }
}
