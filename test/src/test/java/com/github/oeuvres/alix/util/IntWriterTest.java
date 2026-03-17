package com.github.oeuvres.alix.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        try (IntWriter w = IntWriter.open(path, 6, ByteOrder.BIG_ENDIAN, 64)) {
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
        try (IntWriter w = IntWriter.open(path, 4, ByteOrder.LITTLE_ENDIAN, 64)) {
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
        try (IntWriter w = IntWriter.open(path, 10, ByteOrder.BIG_ENDIAN, 16)) {
            w.fill(-1);
            w.put(0, 100);
            w.put(3, 103);  // last int of page 0
            w.put(4, 104);  // first int of page 1
            w.put(9, 109);  // last int of file
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
        try (IntWriter w = IntWriter.open(path, 20, ByteOrder.BIG_ENDIAN, 16)) {
            w.fill(0);
            // jump forward and back to force repeated page swaps
            w.put(18, 18);  // page 4
            w.put(2, 2);    // page 0 — flushes page 4
            w.put(19, 19);  // page 4 — flushes page 0
            w.put(8, 8);    // page 2 — flushes page 4
            w.put(9, 9);    // page 2 — same page, no flush
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
        try (IntWriter w = IntWriter.open(path, 4, ByteOrder.BIG_ENDIAN, 64)) {
            w.fill(0);
            w.put(1, 111);
            w.put(1, 222);
        }
        assertArrayEquals(
            new int[] { 0, 222, 0, 0 },
            readAllInts(path, ByteOrder.BIG_ENDIAN)
        );
    }

    // ---- fill after put (invalidates cached page) -------------------------

    @Test
    void fill_afterPut_resetsFile() throws IOException
    {
        final Path path = tempDir.resolve("fill-after-put.dat");
        try (IntWriter w = IntWriter.open(path, 8, ByteOrder.BIG_ENDIAN, 16)) {
            w.fill(0);
            w.put(3, 99);
            w.put(7, 77);
            // fill again — must erase previous puts
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
        // page 16 => fill writes multiple pages, then put must reload
        try (IntWriter w = IntWriter.open(path, 12, ByteOrder.BIG_ENDIAN, 16)) {
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
        try (IntWriter w = IntWriter.open(path, n, ByteOrder.LITTLE_ENDIAN, 16)) {
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

    // ---- edge cases -------------------------------------------------------

    @Test
    void zeroLength_createsEmptyFile() throws IOException
    {
        final Path path = tempDir.resolve("empty.dat");
        try (IntWriter w = IntWriter.open(path, 0, ByteOrder.BIG_ENDIAN, 64)) {
            w.fill(-1); // no-op on empty
        }
        assertEquals(0L, Files.size(path));
    }

    @Test
    void singleInt() throws IOException
    {
        final Path path = tempDir.resolve("single.dat");
        try (IntWriter w = IntWriter.open(path, 1, ByteOrder.BIG_ENDIAN, 64)) {
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
        try (IntWriter w = IntWriter.open(path, 3, ByteOrder.BIG_ENDIAN, 64)) {
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
            () -> IntWriter.open(path, -1, ByteOrder.BIG_ENDIAN, 64)
        );
    }

    // ---- normalizePageSize ------------------------------------------------

    @Test
    void normalizePageSize_minimum()
    {
        assertEquals(Integer.BYTES, IntWriter.normalizePageSize(0));
        assertEquals(Integer.BYTES, IntWriter.normalizePageSize(1));
        assertEquals(Integer.BYTES, IntWriter.normalizePageSize(Integer.BYTES));
    }

    @Test
    void normalizePageSize_roundsUp()
    {
        assertEquals(8, IntWriter.normalizePageSize(5));
        assertEquals(8, IntWriter.normalizePageSize(6));
        assertEquals(8, IntWriter.normalizePageSize(7));
        assertEquals(8, IntWriter.normalizePageSize(8));
        assertEquals(12, IntWriter.normalizePageSize(9));
    }

    // ---- ensureFileSize ---------------------------------------------------

    @Test
    void ensureFileSize_grow_shrink_exact() throws IOException
    {
        final Path path = tempDir.resolve("ensure.dat");
        try (FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // grow from 0
            IntWriter.ensureFileSize(ch, 100L);
            assertEquals(100L, ch.size());
            // exact — no-op
            IntWriter.ensureFileSize(ch, 100L);
            assertEquals(100L, ch.size());
            // shrink
            IntWriter.ensureFileSize(ch, 40L);
            assertEquals(40L, ch.size());
            // to zero
            IntWriter.ensureFileSize(ch, 0L);
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
                () -> IntWriter.ensureFileSize(ch, -1L)
            );
        }
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
