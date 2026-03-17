package com.github.oeuvres.alix.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public interface IntWriter extends Closeable
{

    void fill(int value) throws IOException;

    void put(long intIndex, int value) throws IOException;

    static IntWriter open(
        final Path path,
        final long totalInts,
        final ByteOrder byteOrder,
        int pageSize
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(byteOrder, "order");
        if (totalInts < 0L) {
            throw new IllegalArgumentException("totalInts < 0: " + totalInts);
        }
        final long totalBytes = Math.multiplyExact(totalInts, (long) Integer.BYTES);

        final FileChannel channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );
        
        // if (pageSize < 1 << 28) pageSize = 1 << 28;
        pageSize = Math.min(Calcul.nextSquare(pageSize), Integer.MAX_VALUE);
        
        boolean ok = false;
        try {
            ensureFileSize(channel, totalBytes);

            final IntWriter writer;
            if (totalBytes <= pageSize) {
                writer = new SingleMapIntWriter(channel, totalInts, byteOrder);
            }
            else {
                writer = new PagedIntWriter(channel, totalInts, byteOrder, pageSize);
            }

            ok = true;
            return writer;
        }
        finally {
            if (!ok) {
                channel.close();
            }
        }
    }
    
    final class PagedIntWriter implements IntWriter
    {
        private final FileChannel ch;
        private final long totalInts;
        private final long totalBytes;
        private final ByteOrder byteOrder;
        private final int pageBytes;

        private long mapStart = -1L;
        private long mapSize = 0L;
        private MappedByteBuffer map;

        PagedIntWriter(
            final FileChannel ch,
            final long totalInts,
            final ByteOrder byteOrder,
            final int pageBytes
        ) {
            this.ch = ch;
            this.totalInts = totalInts;
            this.totalBytes = Math.multiplyExact(totalInts, (long) Integer.BYTES);
            this.byteOrder = byteOrder;
            this.pageBytes = pageBytes;
        }

        @Override
        public void fill(final int value) throws IOException
        {
            if (totalInts == 0L) {
                return;
            }

            long doneBytes = 0L;
            while (doneBytes < totalBytes) {
                remap(doneBytes);

                final long remainBytes = totalBytes - doneBytes;
                final int bytesThisPage = (int) Math.min(mapSize, remainBytes);
                final int intsThisPage = bytesThisPage >>> 2;

                map.position(0);
                for (int i = 0; i < intsThisPage; i++) {
                    map.putInt(value);
                }

                doneBytes += bytesThisPage;
            }
        }

        @Override
        public void put(final long intIndex, final int value) throws IOException
        {
            if (intIndex < 0L || intIndex >= totalInts) {
                throw new IllegalArgumentException("intIndex out of range: " + intIndex);
            }

            final long bytePos = intIndex << 2;
            if (map == null || bytePos < mapStart || bytePos + Integer.BYTES > mapStart + mapSize) {
                remap(bytePos);
            }

            final int rel = (int) (bytePos - mapStart);
            map.putInt(rel, value);
        }

        private void remap(final long bytePos) throws IOException
        {
            final long start = (bytePos / pageBytes) * pageBytes;
            final long remain = totalBytes - start;
            final long size = Math.min((long) pageBytes, remain);

            if (map != null) {
                map.force();
            }

            this.mapStart = start;
            this.mapSize = size;
            this.map = ch.map(FileChannel.MapMode.READ_WRITE, start, size);
            this.map.order(byteOrder);
        }

        @Override
        public void close() throws IOException
        {
            if (map != null) {
                map.force();
            }
        }
    }
    
    final class SingleMapIntWriter implements IntWriter
    {
        private final long totalInts;
        private final MappedByteBuffer map;

        SingleMapIntWriter(
            final FileChannel ch,
            final long totalInts,
            final ByteOrder byteOrder
        ) throws IOException {
            this.totalInts = totalInts;
            final long totalBytes = Math.multiplyExact(totalInts, (long) Integer.BYTES);

            if (totalBytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                    "SingleMapIntWriter cannot map more than Integer.MAX_VALUE bytes: " + totalBytes
                );
            }

            this.map = ch.map(FileChannel.MapMode.READ_WRITE, 0L, totalBytes);
            this.map.order(byteOrder);
        }

        @Override
        public void fill(final int value)
        {
            map.position(0);
            for (long i = 0; i < totalInts; i++) {
                map.putInt(value);
            }
        }

        @Override
        public void put(final long intIndex, final int value)
        {
            if (intIndex < 0L || intIndex >= totalInts) {
                throw new IllegalArgumentException("intIndex out of range: " + intIndex);
            }
            map.putInt((int) (intIndex << 2), value);
        }

        @Override
        public void close() throws IOException
        {
            map.force();
        }
    }
    
    /**
     * Ensure file size before mmap.
     * truncate() does not grow, so extend explicitly if needed.
     */
    static void ensureFileSize(final FileChannel channel, final long size) throws IOException
    {
        if (size == 0L) {
            channel.truncate(0L);
            return;
        }

        final long current = channel.size();
        if (current == size) {
            return;
        }
        if (current > size) {
            channel.truncate(size);
            return;
        }

        final ByteBuffer one = ByteBuffer.allocate(1);
        channel.position(size - 1);
        one.put((byte) 0);
        one.flip();
        while (one.hasRemaining()) {
            channel.write(one);
        }
    }
}
