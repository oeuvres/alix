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

public class NumWriterTest
{
    @TempDir
    Path tempDir;

    // ---- int: single put --------------------------------------------------

    @Test
    void putInt_basic() throws IOException
    {
        final Path path = tempDir.resolve("int-basic.dat");
        final int n = 6;
        final long totalBytes = (long) n * Integer.BYTES;
        try (NumWriter w = NumWriter.open(path, totalBytes)) {
            w.fill((byte) 0);
            w.put(1L * Integer.BYTES, 10);
            w.put(4L * Integer.BYTES, 20);
        }
        assertEquals(totalBytes, Files.size(path));
        assertArrayEquals(
            new int[] { 0, 10, 0, 0, 20, 0 },
            readAllInts(path)
        );
    }

    @Test
    void putInt_paged_randomAccess() throws IOException
    {
        final Path path = tempDir.resolve("int-random.dat");
        final int n = 20;
        final long totalBytes = (long) n * Integer.BYTES;
        // page 16 => 4 ints/page
        try (NumWriter w = NumWriter.open(path, totalBytes, ByteOrder.LITTLE_ENDIAN, 16)) {
            w.fill((byte) 0);
            w.put(18L * Integer.BYTES, 18);  // page 4
            w.put(2L * Integer.BYTES, 2);     // page 0
            w.put(19L * Integer.BYTES, 19);   // page 4
            w.put(8L * Integer.BYTES, 8);     // page 2
        }
        final int[] result = readAllInts(path);
        assertEquals(0, result[0]);
        assertEquals(2, result[2]);
        assertEquals(8, result[8]);
        assertEquals(18, result[18]);
        assertEquals(19, result[19]);
    }

    // ---- int: bulk put ----------------------------------------------------

    @Test
    void bulkInt_wholeFile() throws IOException
    {
        final Path path = tempDir.resolve("int-bulk-whole.dat");
        final int[] data = { 10, 20, 30, 40, 50, 60, 70, 80 };
        try (NumWriter w = NumWriter.open(path, (long) data.length * Integer.BYTES)) {
            w.put(0L, data, 0, data.length);
        }
        assertArrayEquals(data, readAllInts(path));
    }

    @Test
    void bulkInt_withOffset() throws IOException
    {
        final Path path = tempDir.resolve("int-bulk-off.dat");
        final int[] src = { 99, 99, 1, 2, 3, 99 };
        final int n = 5;
        try (NumWriter w = NumWriter.open(path, (long) n * Integer.BYTES)) {
            w.fill((byte) 0);
            w.put(1L * Integer.BYTES, src, 2, 3);
        }
        assertArrayEquals(
            new int[] { 0, 1, 2, 3, 0 },
            readAllInts(path)
        );
    }

    @Test
    void bulkInt_crossesPages() throws IOException
    {
        final Path path = tempDir.resolve("int-bulk-cross.dat");
        final int[] src = { 20, 30, 40, 50, 60, 70, 80 };
        final int n = 12;
        try (NumWriter w = NumWriter.open(path, (long) n * Integer.BYTES, ByteOrder.LITTLE_ENDIAN, 16)) {
            w.fill((byte) 0xFF);
            w.put(2L * Integer.BYTES, src, 0, src.length);
        }
        final int[] result = readAllInts(path);
        assertEquals(-1, result[0]);  // 0xFF fill => -1 for int
        assertEquals(-1, result[1]);
        assertEquals(20, result[2]);
        assertEquals(80, result[8]);
        assertEquals(-1, result[9]);
    }

    @Test
    void bulkInt_thenSinglePut() throws IOException
    {
        final Path path = tempDir.resolve("int-bulk-then-single.dat");
        final int[] src = { 1, 2, 3, 4 };
        try (NumWriter w = NumWriter.open(path, 8L * Integer.BYTES, ByteOrder.LITTLE_ENDIAN, 16)) {
            w.fill((byte) 0);
            w.put(0L, src, 0, src.length);
            w.put(2L * Integer.BYTES, 99);
        }
        assertArrayEquals(
            new int[] { 1, 2, 99, 4, 0, 0, 0, 0 },
            readAllInts(path)
        );
    }

    // ---- long -------------------------------------------------------------

    @Test
    void putLong_single() throws IOException
    {
        final Path path = tempDir.resolve("long-single.dat");
        final long totalBytes = 4L * Long.BYTES;
        try (NumWriter w = NumWriter.open(path, totalBytes)) {
            w.fill((byte) 0);
            w.put(0L, Long.MIN_VALUE);
            w.put(3L * Long.BYTES, Long.MAX_VALUE);
        }
        final long[] result = readAllLongs(path);
        assertEquals(Long.MIN_VALUE, result[0]);
        assertEquals(0L, result[1]);
        assertEquals(0L, result[2]);
        assertEquals(Long.MAX_VALUE, result[3]);
    }

    @Test
    void bulkLong() throws IOException
    {
        final Path path = tempDir.resolve("long-bulk.dat");
        final long[] data = { 100L, 200L, 300L, 400L, 500L };
        try (NumWriter w = NumWriter.open(path, (long) data.length * Long.BYTES)) {
            w.put(0L, data, 0, data.length);
        }
        assertArrayEquals(data, readAllLongs(path));
    }

    // ---- double -----------------------------------------------------------

    @Test
    void putDouble_single() throws IOException
    {
        final Path path = tempDir.resolve("double-single.dat");
        final long totalBytes = 3L * Double.BYTES;
        try (NumWriter w = NumWriter.open(path, totalBytes)) {
            w.fill((byte) 0);
            w.put(0L, Math.PI);
            w.put(2L * Double.BYTES, Double.NaN);
        }
        final double[] result = readAllDoubles(path);
        assertEquals(Math.PI, result[0], 0.0);
        assertEquals(0.0, result[1], 0.0);
        assertTrue(Double.isNaN(result[2]));
    }

    @Test
    void bulkDouble() throws IOException
    {
        final Path path = tempDir.resolve("double-bulk.dat");
        final double[] data = { 1.1, 2.2, 3.3, 4.4 };
        try (NumWriter w = NumWriter.open(path, (long) data.length * Double.BYTES)) {
            w.put(0L, data, 0, data.length);
        }
        final double[] result = readAllDoubles(path);
        assertArrayEquals(data, result, 0.0);
    }

    // ---- char -------------------------------------------------------------

    @Test
    void putChar_single() throws IOException
    {
        final Path path = tempDir.resolve("char-single.dat");
        final long totalBytes = 4L * Character.BYTES;
        try (NumWriter w = NumWriter.open(path, totalBytes)) {
            w.fill((byte) 0);
            w.put(0L, 'A');
            w.put(3L * Character.BYTES, '\u00E9'); // é
        }
        final char[] result = readAllChars(path);
        assertEquals('A', result[0]);
        assertEquals('\0', result[1]);
        assertEquals('\0', result[2]);
        assertEquals('\u00E9', result[3]);
    }

    @Test
    void bulkChar() throws IOException
    {
        final Path path = tempDir.resolve("char-bulk.dat");
        final char[] data = "Piaget".toCharArray();
        try (NumWriter w = NumWriter.open(path, (long) data.length * Character.BYTES)) {
            w.put(0L, data, 0, data.length);
        }
        assertArrayEquals(data, readAllChars(path));
    }

    // ---- fill -------------------------------------------------------------

    @Test
    void fill_zero() throws IOException
    {
        final Path path = tempDir.resolve("fill-zero.dat");
        try (NumWriter w = NumWriter.open(path, 32L)) {
            w.fill((byte) 0);
        }
        final byte[] bytes = Files.readAllBytes(path);
        for (byte b : bytes) {
            assertEquals(0, b);
        }
    }

    @Test
    void fill_afterPut_resetsFile() throws IOException
    {
        final Path path = tempDir.resolve("fill-reset.dat");
        try (NumWriter w = NumWriter.open(path, 8L * Integer.BYTES, ByteOrder.LITTLE_ENDIAN, 16)) {
            w.fill((byte) 0);
            w.put(3L * Integer.BYTES, 99);
            w.fill((byte) 0);
        }
        for (int v : readAllInts(path)) {
            assertEquals(0, v);
        }
    }

    // ---- edge cases -------------------------------------------------------

    @Test
    void zeroLength_createsEmptyFile() throws IOException
    {
        final Path path = tempDir.resolve("empty.dat");
        try (NumWriter w = NumWriter.open(path, 0L)) {
            w.fill((byte) 0);
        }
        assertEquals(0L, Files.size(path));
    }

    @Test
    void putInt_outOfRange_throws() throws IOException
    {
        final Path path = tempDir.resolve("range.dat");
        try (NumWriter w = NumWriter.open(path, 12L)) {
            assertThrows(IllegalArgumentException.class,
                () -> w.put(-1L, 7));
            // byte 12 + 4 > 12
            assertThrows(IllegalArgumentException.class,
                () -> w.put(12L, 7));
            // byte 10 + 4 > 12
            assertThrows(IllegalArgumentException.class,
                () -> w.put(10L, 7));
        }
    }

    @Test
    void bulkInt_outOfRange_throws() throws IOException
    {
        final Path path = tempDir.resolve("bulk-range.dat");
        try (NumWriter w = NumWriter.open(path, 20L)) {
            final int[] src = { 1, 2, 3 };
            assertThrows(IllegalArgumentException.class,
                () -> w.put(12L, src, 0, 3)); // 12 + 12 > 20
            assertThrows(IllegalArgumentException.class,
                () -> w.put(-1L, src, 0, 1));
            assertThrows(IndexOutOfBoundsException.class,
                () -> w.put(0L, src, 2, 3));
        }
    }

    @Test
    void open_negativeTotalBytes_throws()
    {
        final Path path = tempDir.resolve("negative.dat");
        assertThrows(IllegalArgumentException.class,
            () -> NumWriter.open(path, -1L)
        );
    }

    // ---- defaults ---------------------------------------------------------

    @Test
    void open_defaultByteOrder_isLittleEndian() throws IOException
    {
        final Path path = tempDir.resolve("default-order.dat");
        try (NumWriter w = NumWriter.open(path, 4L)) {
            w.put(0L, 0x01020304);
        }
        final byte[] bytes = Files.readAllBytes(path);
        // little-endian: least significant byte first
        assertEquals(0x04, bytes[0] & 0xFF);
        assertEquals(0x03, bytes[1] & 0xFF);
        assertEquals(0x02, bytes[2] & 0xFF);
        assertEquals(0x01, bytes[3] & 0xFF);
    }

    // ---- channel close ----------------------------------------------------

    @Test
    void close_releasesFile() throws IOException
    {
        final Path path = tempDir.resolve("close-release.dat");
        final NumWriter w = NumWriter.open(path, 40L);
        w.put(0L, 1);
        w.close();
        assertTrue(Files.deleteIfExists(path));
    }

    @Test
    void close_thenRename() throws IOException
    {
        final Path src = tempDir.resolve("before.dat");
        final Path dst = tempDir.resolve("after.dat");
        final NumWriter w = NumWriter.open(src, 16L);
        w.fill((byte) 42);
        w.close();
        Files.move(src, dst);
        assertTrue(Files.exists(dst));
        assertFalse(Files.exists(src));
    }

    @Test
    void put_afterClose_throws() throws IOException
    {
        final Path path = tempDir.resolve("after-close.dat");
        final NumWriter w = NumWriter.open(path, 16L);
        w.close();
        assertThrows(ClosedChannelException.class, () -> w.put(0L, 1));
    }

    @Test
    void close_idempotent() throws IOException
    {
        final Path path = tempDir.resolve("double-close.dat");
        final NumWriter w = NumWriter.open(path, 16L);
        w.fill((byte) 0);
        w.close();
        assertDoesNotThrow(w::close);
    }

    // ---- normalizePageSize ------------------------------------------------

    @Test
    void normalizePageSize_minimum()
    {
        assertEquals(Long.BYTES, NumWriter.normalizePageSize(0));
        assertEquals(Long.BYTES, NumWriter.normalizePageSize(1));
        assertEquals(Long.BYTES, NumWriter.normalizePageSize((int) Long.BYTES));
    }

    @Test
    void normalizePageSize_roundsUp()
    {
        assertEquals(16, NumWriter.normalizePageSize(9));
        assertEquals(16, NumWriter.normalizePageSize(15));
        assertEquals(16, NumWriter.normalizePageSize(16));
        assertEquals(24, NumWriter.normalizePageSize(17));
    }

    // ---- >2 GB ------------------------------------------------------------

    @Test
    @Tag("heavy")
    void largerThan2GB_intWriteAndReadBack() throws IOException
    {
        final long totalInts = 536_870_913L; // * 4 = 4 bytes over Integer.MAX_VALUE
        final long totalBytes = totalInts * Integer.BYTES;

        final long usable = Files.getFileStore(tempDir).getUsableSpace();
        assumeTrue(usable >= totalBytes + (1L << 20), "insufficient disk space");

        final Path path = tempDir.resolve("large.dat");
        final int firstVal = 0xCAFEBABE;
        final long lastBytePos = (totalInts - 1L) * Integer.BYTES;
        final int lastVal = 0xDEADBEEF;

        try (NumWriter w = NumWriter.open(path, totalBytes)) {
            w.put(0L, firstVal);
            w.put(lastBytePos, lastVal);
        }

        assertEquals(totalBytes, Files.size(path));

        final ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ch.read(buf, 0L);
            buf.flip();
            assertEquals(firstVal, buf.getInt());

            buf.clear();
            ch.read(buf, lastBytePos);
            buf.flip();
            assertEquals(lastVal, buf.getInt());
        }

        assertTrue(Files.deleteIfExists(path));
    }

    // ---- mixed types in same file -----------------------------------------

    @Test
    void mixedTypes_headerThenInts() throws IOException
    {
        final Path path = tempDir.resolve("mixed.dat");
        // header: 1 long (version) + 1 int (count), then 4 ints
        final long headerBytes = Long.BYTES + Integer.BYTES; // 12
        final int count = 4;
        final long totalBytes = headerBytes + (long) count * Integer.BYTES;

        try (NumWriter w = NumWriter.open(path, totalBytes)) {
            w.fill((byte) 0);
            w.put(0L, 42L);                    // version
            w.put(Long.BYTES, count);             // count
            int[] data = { 100, 200, 300, 400 };
            w.put(headerBytes, data, 0, data.length);
        }

        final byte[] bytes = Files.readAllBytes(path);
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(42L, buf.getLong());
        assertEquals(4, buf.getInt());
        assertEquals(100, buf.getInt());
        assertEquals(200, buf.getInt());
        assertEquals(300, buf.getInt());
        assertEquals(400, buf.getInt());
    }

    // ---- helpers ----------------------------------------------------------

    private static int[] readAllInts(final Path path) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(path);
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final int[] ints = new int[bytes.length / Integer.BYTES];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = buf.getInt();
        }
        return ints;
    }

    private static long[] readAllLongs(final Path path) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(path);
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final long[] longs = new long[bytes.length / Long.BYTES];
        for (int i = 0; i < longs.length; i++) {
            longs[i] = buf.getLong();
        }
        return longs;
    }

    private static double[] readAllDoubles(final Path path) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(path);
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final double[] doubles = new double[bytes.length / Double.BYTES];
        for (int i = 0; i < doubles.length; i++) {
            doubles[i] = buf.getDouble();
        }
        return doubles;
    }

    private static char[] readAllChars(final Path path) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(path);
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final char[] chars = new char[bytes.length / Character.BYTES];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = buf.getChar();
        }
        return chars;
    }
}
