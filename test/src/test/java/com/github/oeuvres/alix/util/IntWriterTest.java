package com.github.oeuvres.alix.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public class IntWriterTest
{
    @TempDir
    Path tempDir;

    @Test
    void singleMap_fillAndPut_bigEndian() throws IOException
    {
        final Path path = tempDir.resolve("single-map.dat");

        // 6 ints = 24 bytes, page size 64 => single-map path
        try (IntWriter writer = IntWriter.open(path, 6, ByteOrder.BIG_ENDIAN, 64)) {
            writer.fill(-1);
            writer.put(1, 10);
            writer.put(4, 20);
        }

        assertEquals(6L * Integer.BYTES, Files.size(path));
        assertArrayEquals(
            new int[] { -1, 10, -1, -1, 20, -1 },
            readAllInts(path, ByteOrder.BIG_ENDIAN)
        );
    }

    @Test
    void paged_fillAndPut_bigEndian() throws IOException
    {
        final Path path = tempDir.resolve("paged.dat");

        // 10 ints = 40 bytes, page size 16 => paged path (4 ints/page)
        try (IntWriter writer = IntWriter.open(path, 10, ByteOrder.BIG_ENDIAN, 16)) {
            writer.fill(-1);
            writer.put(0, 100);
            writer.put(3, 103); // end of page 0
            writer.put(4, 104); // start of page 1
            writer.put(9, 109); // last int
        }

        assertEquals(10L * Integer.BYTES, Files.size(path));
        assertArrayEquals(
            new int[] { 100, -1, -1, 103, 104, -1, -1, -1, -1, 109 },
            readAllInts(path, ByteOrder.BIG_ENDIAN)
        );
    }

    @Test
    void singleMap_fillAndPut_littleEndian() throws IOException
    {
        final Path path = tempDir.resolve("little-endian.dat");

        try (IntWriter writer = IntWriter.open(path, 4, ByteOrder.LITTLE_ENDIAN, 64)) {
            writer.fill(0);
            writer.put(0, 0x01020304);
            writer.put(3, 0x11223344);
        }

        assertArrayEquals(
            new int[] { 0x01020304, 0, 0, 0x11223344 },
            readAllInts(path, ByteOrder.LITTLE_ENDIAN)
        );
    }

    @Test
    void put_outOfRange_throws() throws IOException
    {
        final Path path = tempDir.resolve("range.dat");

        try (IntWriter writer = IntWriter.open(path, 3, ByteOrder.BIG_ENDIAN, 64)) {
            writer.fill(-1);

            assertThrows(IllegalArgumentException.class, () -> writer.put(-1, 7));
            assertThrows(IllegalArgumentException.class, () -> writer.put(3, 7));
        }
    }

    @Test
    void zeroLength_createsEmptyFile() throws IOException
    {
        final Path path = tempDir.resolve("empty.dat");

        try (IntWriter writer = IntWriter.open(path, 0, ByteOrder.BIG_ENDIAN, 64)) {
            writer.fill(-1);
        }

        assertEquals(0L, Files.size(path));
    }

    private static int[] readAllInts(final Path path, final ByteOrder order) throws IOException
    {
        final byte[] bytes = Files.readAllBytes(path);
        if ((bytes.length & 3) != 0) {
            throw new AssertionError("File size is not a multiple of 4: " + bytes.length);
        }

        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(order);
        final int[] ints = new int[bytes.length >>> 2];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = buf.getInt();
        }
        return ints;
    }
}
