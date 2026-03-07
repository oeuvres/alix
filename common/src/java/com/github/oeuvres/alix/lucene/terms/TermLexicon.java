package com.github.oeuvres.alix.lucene.terms;

import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.*;
import org.apache.lucene.util.fst.*;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;

import static java.lang.Math.toIntExact;

/**
 * Snapshot lexicon for a single field, persisted inside a frozen Lucene directory:
 *
 * <field>.terms.fst : term(BytesRef) -> termId (dense 0..V-1, lexicographic order)
 * <field>.terms.dat : concatenated UTF-8 bytes of terms in termId order
 * <field>.terms.off : big-endian long offsets into .dat; length = V+1; off[V] == datLength
 *
 * Notes:
 * - termId is defined by the snapshot build: iteration order of TermsEnum for the field.
 * - getId(String) assumes the String is already in the indexed term form for the field.
 */
public final class TermLexicon implements Closeable
{
    private final Directory dir; // used to open the FST
    private final FST<Long> fst;
    
    private final ByteBuffer dat; // mmap .dat
    private final LongBuffer off; // mmap .off (big-endian longs)
    
    private final int vocabSize;
    
    private static final long MTIME_TOLERANCE_MS = 5_000;
    private static final int MONO_CHECK = 1024;
    
    private static final ThreadLocal<BytesRefBuilder> TL_BRB = ThreadLocal.withInitial(BytesRefBuilder::new);
    
    private TermLexicon(Directory dir, FST<Long> fst, ByteBuffer dat, LongBuffer off)
    {
        this.dir = dir;
        this.fst = fst;
        this.dat = dat.asReadOnlyBuffer();
        this.off = off.asReadOnlyBuffer();
        this.vocabSize = off.capacity() - 1;
    }
    
    public int vocabSize()
    {
        return vocabSize;
    }
    
    /** Open lexicon files for {@code field} from a frozen index directory. */
    public static TermLexicon open(Path indexDir, String field) throws IOException
    {
        final String fstName = field + ".terms.fst";
        final String datName = field + ".terms.dat";
        final String offName = field + ".terms.off";
        
        ensureExists(indexDir, fstName);
        ensureExists(indexDir, datName);
        ensureExists(indexDir, offName);
        checkMtimeCoherence(indexDir, fstName, datName, offName);
        
        // mmap data files (Path-based mmap is fine in a frozen directory)
        ByteBuffer dat = mapReadOnly(indexDir.resolve(datName));
        ByteBuffer offBB = mapReadOnly(indexDir.resolve(offName)).order(ByteOrder.BIG_ENDIAN);
        
        if ((offBB.remaining() & 7) != 0) {
            throw new IOException("Invalid " + offName + ": size not multiple of 8 bytes");
        }
        LongBuffer off = offBB.asLongBuffer();
        if (off.capacity() < 2) {
            throw new IOException("Invalid " + offName + ": need at least 2 offsets (V+1)");
        }
        
        // Basic structural checks tying off/dat together
        long datLen = dat.capacity();
        long first = off.get(0);
        long last = off.get(off.capacity() - 1);
        if (first != 0L) {
            throw new IOException("Invalid " + offName + ": off[0] must be 0, got " + first);
        }
        if (last != datLen) {
            throw new IOException("Mismatch: " + offName + " last offset=" + last +
                    " but " + datName + " length=" + datLen);
        }
        
        // Bounded monotonicity check (cheap, catches common corruption)
        monotonicityCheck(off, offName);
        
        // Load FST via Lucene Directory APIs
        Directory d = FSDirectory.open(indexDir);
        try (IndexInput in = d.openInput(fstName, IOContext.READONCE)) {
            PositiveIntOutputs outputs = PositiveIntOutputs.getSingleton();
            FST.FSTMetadata<Long> md = FST.readMetadata(in, outputs);
            FST<Long> fst = new FST<>(md, in);
            return new TermLexicon(d, fst, dat, off);
        } catch (IOException e) {
            d.close();
            throw e;
        }
    }
    
    /** term(String) -> termId, or -1 if absent. */
    public int getId(String termForm) throws IOException
    {
        BytesRefBuilder brb = TL_BRB.get();
        brb.copyChars(termForm); // UTF-16 -> UTF-8 BytesRef
        Long v = Util.get(fst, brb.get());
        return v == null ? -1 : toIntExact(v);
    }
    
    /** term(BytesRef) -> termId, or -1 if absent. */
    public int getId(BytesRef termBytes) throws IOException
    {
        Long v = Util.get(fst, termBytes);
        return v == null ? -1 : toIntExact(v);
    }
    
    /** termId -> term bytes copied into {@code dst} (no per-call allocation). */
    public BytesRef getTermBytes(int termId, BytesRefBuilder dst)
    {
        checkTermId(termId);
        long start = off.get(termId);
        long end = off.get(termId + 1);
        int len = toIntExact(end - start);
        
        dst.grow(len);
        byte[] arr = dst.bytes();
        
        ByteBuffer dup = dat.duplicate();
        dup.position(toIntExact(start));
        dup.get(arr, 0, len);
        
        dst.setLength(len);
        return dst.get();
    }
    
    /** Convenience: termId -> String (allocates). */
    public String getTermString(int termId)
    {
        BytesRefBuilder brb = new BytesRefBuilder();
        return getTermBytes(termId, brb).utf8ToString();
    }
    
    private void checkTermId(int termId)
    {
        if (termId < 0 || termId >= vocabSize) {
            throw new IllegalArgumentException("termId out of range: " + termId + " (vocabSize=" + vocabSize + ")");
        }
    }
    
    @Override
    public void close() throws IOException
    {
        dir.close(); // mmaps are managed by the OS/JVM; close Lucene directory handles
    }
    
    // -------------------- Builder --------------------
    
    public static final class Builder
    {
        private Builder()
        {
        }
        
        /**
         * Build <field>.terms.{fst,dat,off} into the given Lucene directory.
         * Fails if any destination file already exists.
         *
         * The reader must represent the frozen snapshot you want to serve (DirectoryReader.open(IndexCommit)).
         */
        public static void buildInto(Path indexDir, IndexReader snapshotReader, String field) throws IOException
        {
            final String fstName = field + ".terms.fst";
            final String datName = field + ".terms.dat";
            final String offName = field + ".terms.off";
            
            try (Directory outDir = FSDirectory.open(indexDir)) {
                ensureNotExists(outDir, fstName);
                ensureNotExists(outDir, datName);
                ensureNotExists(outDir, offName);
                
                Terms terms = MultiTerms.getTerms(snapshotReader, field);
                if (terms == null) {
                    throw new IllegalArgumentException("Field not found in reader: " + field);
                }
                
                // Temp files + rename to avoid leaving partial final files.
                final String tmpFst = fstName + ".tmp";
                final String tmpDat = datName + ".tmp";
                final String tmpOff = offName + ".tmp";
                ensureNotExists(outDir, tmpFst);
                ensureNotExists(outDir, tmpDat);
                ensureNotExists(outDir, tmpOff);
                
                try {
                    buildFiles(outDir, terms, tmpDat, tmpOff, tmpFst);
                    outDir.rename(tmpDat, datName);
                    outDir.rename(tmpOff, offName);
                    outDir.rename(tmpFst, fstName);
                } catch (IOException e) {
                    safeDelete(outDir, tmpDat);
                    safeDelete(outDir, tmpOff);
                    safeDelete(outDir, tmpFst);
                    throw e;
                }
            }
        }
        
        private static void buildFiles(
            Directory outDir,
            Terms terms,
            String datFile,
            String offFile,
            String fstFile) throws IOException
        {
            final PositiveIntOutputs outputs = PositiveIntOutputs.getSingleton();
            final FSTCompiler<Long> compiler =
                new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE1, outputs).build(); // Lucene 10.4 style
            final IntsRefBuilder ints = new IntsRefBuilder();

            long id = 0;
            long datPos = 0;

            try (IndexOutput datOut = outDir.createOutput(datFile, IOContext.DEFAULT);
                 IndexOutput offOut = outDir.createOutput(offFile, IOContext.DEFAULT)) {

                // offsets[0] = 0
                offOut.writeLong(0L);

                TermsEnum te = terms.iterator();
                BytesRef term;
                while ((term = te.next()) != null) {
                    // term -> id in FST
                    compiler.add(Util.toIntsRef(term, ints), id++);

                    // append bytes to .dat
                    datOut.writeBytes(term.bytes, term.offset, term.length);
                    datPos += term.length;

                    // next offset
                    offOut.writeLong(datPos);
                }
            }

            // compile and save FST
            final FST.FSTMetadata<Long> md = compiler.compile();
            if (md == null) {
                throw new IOException("No terms for field; cannot build FST");
            }
            final FST<Long> fst = FST.fromFSTReader(md, compiler.getFSTReader());

            try (IndexOutput fstOut = outDir.createOutput(fstFile, IOContext.DEFAULT)) {
                fst.save(fstOut, fstOut);
            }
        }

        private static void safeDelete(Directory dir, String name)
        {
            try {
                if (fileExists(dir, name))
                    dir.deleteFile(name);
            } catch (Exception ignored) {
            }
        }
    }
    
    // -------------------- Startup checks / IO helpers --------------------
    
    private static ByteBuffer mapReadOnly(Path p) throws IOException
    {
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
    }
    
    private static void ensureExists(Path dir, String file) throws IOException
    {
        Path p = dir.resolve(file);
        if (!Files.isRegularFile(p))
            throw new NoSuchFileException(p.toString());
    }
    
    private static void checkMtimeCoherence(Path dir, String... files) throws IOException
    {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (String f : files) {
            long t = Files.getLastModifiedTime(dir.resolve(f)).toMillis();
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        if ((max - min) > MTIME_TOLERANCE_MS) {
            throw new IOException("Lexicon files mtimes differ by " + (max - min) + "ms; " +
                    "possible partial copy or mixed versions: " + Arrays.toString(files));
        }
    }
    
    private static void monotonicityCheck(LongBuffer off, String offName) throws IOException
    {
        int n = off.capacity();
        int head = Math.min(MONO_CHECK, n);
        int tailStart = Math.max(0, n - MONO_CHECK);
        
        // Head check
        long prev = off.get(0);
        for (int i = 1; i < head; i++) {
            long cur = off.get(i);
            if (cur < prev)
                throw new IOException("Invalid " + offName + ": offsets decrease at i=" + i);
            prev = cur;
        }
        // Tail check
        prev = off.get(tailStart);
        for (int i = tailStart + 1; i < n; i++) {
            long cur = off.get(i);
            if (cur < prev)
                throw new IOException("Invalid " + offName + ": offsets decrease near end at i=" + i);
            prev = cur;
        }
    }
    
    private static void ensureNotExists(Directory dir, String name) throws IOException
    {
        if (fileExists(dir, name))
            throw new FileAlreadyExistsException(name);
    }
    
    private static boolean fileExists(Directory dir, String name) throws IOException
    {
        for (String s : dir.listAll()) {
            if (s.equals(name))
                return true;
        }
        return false;
    }
}