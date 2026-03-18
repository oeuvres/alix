package com.github.oeuvres.alix.util;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class IntWriterTest
{
    @TempDir
    Path tempDir;

    // ---- basic fill + put ------------------------------------------------

    @Test
    void fillAndPut_bigEndian() throws IOException
    {
        final Path path = tempDir.resolve("fill-put-be.dat");
        try (NumWriter w = NumWriter.open(path, 6, ByteOrder.BIG_ENDIAN, 64)) {
            w.fill(-1);
            w.put(1, 10);
            w.put(4, 20);
        }
        assertEquals(6L * Integer.BYTES, Files.size(path));
        assertArrayEquals(
            new int[] { -1, 10, -1, -1, 20, -1 },
            readAllInts(path, ByteOrder.BIG_ENDIAN)
        );
    }

    @Test
    void fillAndPut_littleEndian() throws IOException
    {
        final Path path = tempDir.resolve("fill-put-le.dat");
        try (NumWriter w = NumWriter.open(path, 4, ByteOrder.LITTLE_ENDIAN, 64)) {
            w.fill(0);
            w.put(0, 0x01020304);
            w.put(3, 0x11223344);
        }
        assertArrayEquals(
            new int[] { 0x01020304, 0, 0, 0x11223344 },
            readAllInts(path, ByteOrder.LITTLE_ENDIAN)
        );
    }

    // ---- paging -----------------------------------------------------------

    @Test
    void paged_crossPageBoundary() throws IOException
    {
        final Path path = tempDir.resolve("paged.dat");
        // 10 ints = 40 bytes, page 16 => 4 ints/page
        try (NumWriter w = NumWriter.open(path, 10, ByteOrder.BIG_ENDIAN, 16)) {
            w.fill(-1);
            w.put(0, 100);
            w.put(3, 103);
            w.put(4, 104);
            w.put(9, 109);
        }
        assertArrayEquals(
            new int[] { 100, -1, -1, 103, 104, -1, -1, -1, -1, 109 },
            readAllInts(path, ByteOrder.BIG_ENDIAN)
        );
    }

    @Test
    void paged_randomAccess_flushesCorrectly() throws IOException
    {
        final Path path = tempDir.resolve("random.dat");
        // 20 ints, page 16 => 4 ints/page, 5 pages
        try (NumWriter w = NumWriter.open(path, 20, ByteOrder.BIG_ENDIAN, 16)) {
            w.fill(0);
            w.put(18, 18);  // page 4
            w.put(2, 2);    // page 0 — flushes page 4
            w.put(19, 19);  // page 4 — flushes page 0
            w.put(8, 8);    // page 2 — flushes page 4
            w.put(9, 9);    // page 2 — same page
            w.put(0, 0);    // page 0 — flushes page 2
        }
        final int[] expected = new int[20];
        expected[0] = 0;
        expected[2] = 2;
        expected[8] = 8;
        expected[9] = 9;
        expected[18] = 18;
        expected[19] = 19;
        assertArrayEquals(expected, readAllInts(path, ByteOrder.BIG_ENDIAN));
    }

    // ---- overwrite --------------------------------------------------------

    @Test
    void put_overwritesSameIndex() throws IOException
    {
        final Path path = tempDir.resolve("overwrite.dat");
        try (NumWriter w = NumWriter.open(path, 4, ByteOrder.BIG_ENDIAN, 64)) {
            w.fill(0);
            w.put(1, 111);
            w.put(1, 222);
        }
        assertArrayEquals(
            new int[] { 0, 222, 0, 0 },
            readAllInts(path, ByteOrder.BIG_ENDIAN)
        );
    }

    // ---- fill after put ---------------------------------------------------

    @Test
    void fill_afterPut_resetsFile() throws IOException
    {
        final Path path = tempDir.resolve("fill-after-put.dat");
        try (NumWriter w = NumWriter.open(path, 8, ByteOrder.BIG_ENDIAN, 16)) {
            w.fill(0);
            w.put(3, 99);
            w.put(7, 77);
            w.fill(5);
        }
        final int[] expected = new int[8];
        java.util.Arrays.fill(expected, 5);
        assertArrayEquals(expected, readAllInts(path, ByteOrder.BIG_ENDIAN));
    }

    @Test
    void put_afterFill_usesNewValue() throws IOException
    {
        final Path path = tempDir.resolve("put-after-fill.dat");
        try (NumWriter w = NumWriter.open(path, 12, ByteOrder.BIG_ENDIAN, 16)) {
            w.fill(1);
            w.fill(2);
            w.put(5, 99);
        }
        final int[] result = readAllInts(path, ByteOrder.BIG_ENDIAN);
        assertEquals(2, result[0]);
        assertEquals(2, result[4]);
        assertEquals(99, result[5]);
        assertEquals(2, result[11]);
    }

    // ---- sequential write (no fill) ---------------------------------------

    @Test
    void sequentialPut_noFill() throws IOException
    {
        final Path path = tempDir.resolve("sequential.dat");
        final int n = 25;
        try (NumWriter w = NumWriter.open(path, n, ByteOrder.LITTLE_ENDIAN, 16)) {
            for (int i = 0; i < n; i++) {
                w.put(i, i * 10);
            }
        }
        final int[] result = readAllInts(path, ByteOrder.LITTLE_ENDIAN);
        assertEquals(n, result.length);
        for (int i = 0; i < n; i++) {
            assertEquals(i * 10, result[i], "index " + i);
        }
    }

    // ---- bulk put ---------------------------------------------------------

    @Test
    void bulkPut_wholeFile() throws IOException
    {
        final Path path = tempDir.resolve("bulk-whole.dat");
        final int[] data = { 10, 20, 30, 40, 50, 60, 70, 80 };
        try (NumWriter w = NumWriter.open(path, data.length, ByteOrder.BIG_ENDIAN, 16)) {
            w.put(0L, data, 0, data.length);
        }
        assertArrayEquals(data, readAllInts(path, ByteOrder.BIG_ENDIAN));
    }

    @Test
    void bulkPut_withOffset() throws IOException
    {
        final Path path = tempDir.resolve("bulk-offset.dat");
        final int[] src = { 99, 99, 1, 2, 3, 99 };
        try (NumWriter w = NumWriter.open(path, 5, ByteOrder.LITTLE_ENDIAN, 64)) {
            w.fill(0);
            w.put(1L, src, 2, 3); // writes {1, 2, 3} at file indices 1..3
        }
        assertArrayEquals(
            new int[] { 0, 1, 2, 3, 0 },
            readAllInts(path, ByteOrder.LITTLE_ENDIAN)
        );
    }

    @Test
    void bulkPut_crossesPages() throws IOException
    {
        final Path path = tempDir.resolve("bulk-cross.dat");
        // page 16 => 4 ints/page; write 7 ints starting at index 2
        // spans page 0 (indices 2-3), page 1 (4-7), page 2 (8)
        final int[] src = { 20, 30, 40, 50, 60, 70, 80 };
        try (NumWriter w = NumWriter.open(path, 12, ByteOrder.BIG_ENDIAN, 16)) {
            w.fill(-1);
            w.put(2L, src, 0, src.length);
        }
        assertArrayEquals(
            new int[] { -1, -1, 20, 30, 40, 50, 60, 70, 80, -1, -1, -1 },
            readAllInts(path, ByteOrder.BIG_ENDIAN)
        );
    }

    @Test
    void bulkPut_thenSinglePut_reloadsPage() throws IOException
    {
        final Path path = tempDir.resolve("bulk-then-single.dat");
        final int[] src = { 1, 2, 3, 4 };
        try (NumWriter w = NumWriter.open(path, 8, ByteOrder.BIG_ENDIAN, 16)) {
            w.fill(0);
            w.put(0L, src, 0, src.length);
            // single put on same page — must reload from disk
            w.put(2, 99);
        }
        assertArrayEquals(
            new int[] { 1, 2, 99, 4, 0, 0, 0, 0 },
            readAllInts(path, ByteOrder.BIG_ENDIAN)
        );
    }

    @Test
    void bulkPut_zeroLength_noOp() throws IOException
    {
        final Path path = tempDir.resolve("bulk-zero.dat");
        try (NumWriter w = NumWriter.open(path, 4, ByteOrder.BIG_ENDIAN, 64)) {
            w.fill(7);
            w.put(0L, new int[0], 0, 0);
        }
        final int[] result = readAllInts(path, ByteOrder.BIG_ENDIAN);
        for (int v : result) {
            assertEquals(7, v);
        }
    }

    @Test
    void bulkPut_outOfRange_throws() throws IOException
    {
        final Path path = tempDir.resolve("bulk-range.dat");
        try (NumWriter w = NumWriter.open(path, 5, ByteOrder.BIG_ENDIAN, 64)) {
            final int[] src = { 1, 2, 3 };
            // starts at 4, len 3 => ends at 7 > totalInts 5
            assertThrows(IllegalArgumentException.class, () -> w.put(4L, src, 0, 3));
            // negative index
            assertThrows(IllegalArgumentException.class, () -> w.put(-1L, src, 0, 1));
            // src bounds
            assertThrows(IndexOutOfBoundsException.class, () -> w.put(0L, src, 2, 3));
        }
    }

    // ---- edge cases -------------------------------------------------------

    @Test
    void zeroLength_createsEmptyFile() throws IOException
    {
        final Path path = tempDir.resolve("empty.dat");
        try (NumWriter w = NumWriter.open(path, 0, ByteOrder.BIG_ENDIAN, 64)) {
            w.fill(-1);
        }
        assertEquals(0L, Files.size(path));
    }

    @Test
    void singleInt() throws IOException
    {
        final Path path = tempDir.resolve("single.dat");
        try (NumWriter w = NumWriter.open(path, 1, ByteOrder.BIG_ENDIAN, 64)) {
            w.put(0, Integer.MIN_VALUE);
        }
        assertArrayEquals(
            new int[] { Integer.MIN_VALUE },
            readAllInts(path, ByteOrder.BIG_ENDIAN)
        );
    }

    @Test
    void put_outOfRange_throws() throws IOException
    {
        final Path path = tempDir.resolve("range.dat");
        try (NumWriter w = NumWriter.open(path, 3, ByteOrder.BIG_ENDIAN, 64)) {
            assertThrows(IllegalArgumentException.class, () -> w.put(-1, 7));
            assertThrows(IllegalArgumentException.class, () -> w.put(3, 7));
            assertThrows(IllegalArgumentException.class, () -> w.put(Long.MAX_VALUE, 7));
        }
    }

    @Test
    void open_negativeTotalInts_throws()
    {
        final Path path = tempDir.resolve("negative.dat");
        assertThrows(IllegalArgumentException.class,
            () -> NumWriter.open(path, -1, ByteOrder.BIG_ENDIAN, 64)
        );
    }

    // ---- channel close ----------------------------------------------------

    @Test
    void close_releasesFile() throws IOException
    {
        final Path path = tempDir.resolve("close-release.dat");
        final NumWriter w = NumWriter.open(path, 10, ByteOrder.BIG_ENDIAN, 64);
        w.put(0, 1);
        w.close();
        // on Windows this fails if the channel is still held
        assertTrue(Files.deleteIfExists(path), "file should be deletable after close");
    }

    @Test
    void close_thenRename() throws IOException
    {
        final Path src = tempDir.resolve("before-rename.dat");
        final Path dst = tempDir.resolve("after-rename.dat");
        final NumWriter w = NumWriter.open(src, 4, ByteOrder.BIG_ENDIAN, 64);
        w.fill(42);
        w.close();
        // on Windows this fails if the channel is still held
        Files.move(src, dst);
        assertTrue(Files.exists(dst));
        assertFalse(Files.exists(src));
        assertArrayEquals(
            new int[] { 42, 42, 42, 42 },
            readAllInts(dst, ByteOrder.BIG_ENDIAN)
        );
    }

    @Test
    void put_afterClose_throws() throws IOException
    {
        final Path path = tempDir.resolve("after-close.dat");
        final NumWriter w = NumWriter.open(path, 4, ByteOrder.BIG_ENDIAN, 64);
        w.close();
        assertThrows(ClosedChannelException.class, () -> w.put(0, 1));
    }

    @Test
    void close_idempotent() throws IOException
    {
        final Path path = tempDir.resolve("double-close.dat");
        final NumWriter w = NumWriter.open(path, 4, ByteOrder.BIG_ENDIAN, 64);
        w.fill(0);
        w.close();
        // second close must not throw
        assertDoesNotThrow(w::close);
    }

    // ---- normalizePageSize ------------------------------------------------

    @Test
    void normalizePageSize_minimum()
    {
        assertEquals(Integer.BYTES, NumWriter.normalizePageSize(0));
        assertEquals(Integer.BYTES, NumWriter.normalizePageSize(1));
        assertEquals(Integer.BYTES, NumWriter.normalizePageSize(Integer.BYTES));
    }

    @Test
    void normalizePageSize_roundsUp()
    {
        assertEquals(8, NumWriter.normalizePageSize(5));
        assertEquals(8, NumWriter.normalizePageSize(6));
        assertEquals(8, NumWriter.normalizePageSize(7));
        assertEquals(8, NumWriter.normalizePageSize(8));
        assertEquals(12, NumWriter.normalizePageSize(9));
    }

    // ---- ensureFileSize ---------------------------------------------------

    @Test
    void ensureFileSize_grow_shrink_exact() throws IOException
    {
        final Path path = tempDir.resolve("ensure.dat");
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            NumWriter.ensureFileSize(ch, 100L);
            assertEquals(100L, ch.size());
            NumWriter.ensureFileSize(ch, 100L);
            assertEquals(100L, ch.size());
            NumWriter.ensureFileSize(ch, 40L);
            assertEquals(40L, ch.size());
            NumWriter.ensureFileSize(ch, 0L);
            assertEquals(0L, ch.size());
        }
    }

    @Test
    void ensureFileSize_negativeSize_throws() throws IOException
    {
        final Path path = tempDir.resolve("neg-size.dat");
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertThrows(IllegalArgumentException.class,
                () -> NumWriter.ensureFileSize(ch, -1L)
            );
        }
    }

    // ---- >2 GB ------------------------------------------------------------

    /**
     * Write first and last int in a file larger than 2&nbsp;GB,
     * close, reopen, and verify both values.
     * Skipped automatically if disk space is insufficient.
     */
    @Test
    @Tag("heavy")
    void largerThan2GB_writeAndReadBack() throws IOException
    {
        // 536_870_913 ints * 4 = 2_147_483_652 bytes (4 bytes over Integer.MAX_VALUE)
        final long totalInts = 536_870_913L;
        final long totalBytes = totalInts * (long) Integer.BYTES;

        final long usable = Files.getFileStore(tempDir).getUsableSpace();
        assumeTrue(usable >= totalBytes + (1L << 20), "insufficient disk space for >2GB test");

        final Path path = tempDir.resolve("large.dat");
        final int firstVal = 0xCAFEBABE;
        final long lastIndex = totalInts - 1;
        final int lastVal = 0xDEADBEEF;

        try (NumWriter w = NumWriter.open(path, totalInts, ByteOrder.BIG_ENDIAN, 1 << 20)) {
            w.put(0L, firstVal);
            w.put(lastIndex, lastVal);
        }

        assertEquals(totalBytes, Files.size(path));

        // reopen with raw channel and read back
        final ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            // first int
            ch.read(buf, 0L);
            buf.flip();
            assertEquals(firstVal, buf.getInt());

            // last int — byte position exceeds Integer.MAX_VALUE
            buf.clear();
            ch.read(buf, lastIndex * (long) Integer.BYTES);
            buf.flip();
            assertEquals(lastVal, buf.getInt());
        }

        // verify close released the file
        assertTrue(Files.deleteIfExists(path));
    }

    // ---- helper -----------------------------------------------------------

    private static int[] readAllInts(final Path path, final ByteOrder order) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(path);
        if ((bytes.length & 3) != 0) {
            throw new AssertionError("File size not a multiple of 4: " + bytes.length);
        }
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(order);
        final int[] ints = new int[bytes.length >>> 2];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = buf.getInt();
        }
        return ints;
    }
}
