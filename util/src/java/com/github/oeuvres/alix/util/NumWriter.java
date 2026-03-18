package com.github.oeuvres.alix.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Random-access writer for a flat file of {@code int} values,
 * supporting files larger than 2&nbsp;GB.
 *
 * <p>Uses positioned {@link FileChannel} I/O with a single direct
 * {@link ByteBuffer} page, avoiding {@code MappedByteBuffer} and its
 * platform-specific unmap issues. The file is fully released on
 * {@link #close()}.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * try (IntWriter w = IntWriter.open(path, totalInts, ByteOrder.LITTLE_ENDIAN, 1 << 20)) {
 *     w.fill(0);
 *     w.put(42L, 7);
 *     int[] block = {10, 20, 30};
 *     w.put(100L, block, 0, block.length);
 * }
 * }</pre>
 */
public final class NumWriter implements Closeable
{
    private final FileChannel channel;
    private final long totalInts;
    private final long totalBytes;
    private final ByteOrder byteOrder;
    private final int pageBytes;
    private final ByteBuffer page;

    private long pageStart = -1L;
    private int pageLen = 0;
    private boolean dirty = false;

    private NumWriter(
        final FileChannel channel,
        final long totalInts,
        final ByteOrder byteOrder,
        final int pageBytes
    ) {
        this.channel = channel;
        this.totalInts = totalInts;
        this.totalBytes = Math.multiplyExact(totalInts, (long) Integer.BYTES);
        this.byteOrder = byteOrder;
        this.pageBytes = pageBytes;
        this.page = ByteBuffer.allocateDirect(pageBytes).order(byteOrder);
    }

    /**
     * Open (create or overwrite) a file for random int writes.
     *
     * <p>The file is pre-allocated to {@code totalInts * 4} bytes.
     * The returned writer must be {@link #close() closed} to flush pending
     * writes and release the underlying channel.</p>
     *
     * @param path      destination file.
     * @param totalInts number of {@code int} slots; the file size will be
     *                  {@code totalInts * Integer.BYTES}.
     * @param byteOrder byte order for stored ints.
     * @param pageSize  hint for the internal page buffer size in bytes;
     *                  will be rounded up to an {@code Integer.BYTES} boundary
     *                  (minimum {@code Integer.BYTES}).
     * @return a new writer; caller must close it.
     * @throws IOException              if the file cannot be opened or sized.
     * @throws IllegalArgumentException if {@code totalInts < 0}.
     * @throws ArithmeticException      if {@code totalInts * 4} overflows {@code long}.
     */
    public static NumWriter open(
        final Path path,
        final long totalInts,
        final ByteOrder byteOrder,
        int pageSize
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(byteOrder, "byteOrder");

        if (totalInts < 0L) {
            throw new IllegalArgumentException("totalInts < 0: " + totalInts);
        }

        pageSize = normalizePageSize(pageSize);

        final FileChannel channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        );

        boolean ok = false;
        try {
            final long totalBytes = Math.multiplyExact(totalInts, (long) Integer.BYTES);
            ensureFileSize(channel, totalBytes);
            final NumWriter writer = new NumWriter(channel, totalInts, byteOrder, pageSize);
            ok = true;
            return writer;
        }
        finally {
            if (!ok) {
                channel.close();
            }
        }
    }

    /**
     * Fill the entire file with a constant {@code int} value.
     * Invalidates any cached page; the next {@link #put} will reload from disk.
     *
     * @param value the value to write at every position.
     * @throws IOException if a write fails.
     */
    public void fill(final int value) throws IOException
    {
        flush();

        if (totalInts == 0L) {
            return;
        }

        final ByteBuffer fillPage = ByteBuffer.allocateDirect(pageBytes).order(byteOrder);
        while (fillPage.remaining() >= Integer.BYTES) {
            fillPage.putInt(value);
        }

        long writtenBytes = 0L;
        while (writtenBytes < totalBytes) {
            final int len = (int) Math.min((long) pageBytes, totalBytes - writtenBytes);

            final ByteBuffer src = fillPage.duplicate().order(byteOrder);
            src.clear();
            src.limit(len);

            long pos = writtenBytes;
            while (src.hasRemaining()) {
                final int n = channel.write(src, pos);
                if (n <= 0) {
                    throw new IOException("Failed to write at byte position " + pos);
                }
                pos += n;
            }

            writtenBytes += len;
        }

        // invalidate cached page
        pageStart = -1L;
        pageLen = 0;
        dirty = false;
    }

    /**
     * Write a single {@code int} at the given logical index.
     *
     * <p>Uses the internal page buffer; if {@code intIndex} falls outside
     * the currently cached page, the dirty page is flushed and the target
     * page is loaded from disk.</p>
     *
     * @param intIndex zero-based index, must be in {@code [0, totalInts)}.
     * @param value    the value to write.
     * @throws IOException              if a read or write fails.
     * @throws IllegalArgumentException if {@code intIndex} is out of range.
     */
    public void put(final long intIndex, final int value) throws IOException
    {
        if (intIndex < 0L || intIndex >= totalInts) {
            throw new IllegalArgumentException("intIndex out of range: " + intIndex);
        }

        final long bytePos = Math.multiplyExact(intIndex, (long) Integer.BYTES);
        final long wantedPageStart = pageStart(bytePos);

        if (wantedPageStart != pageStart) {
            flush();
            loadPage(wantedPageStart);
        }

        final int rel = (int) (bytePos - pageStart);
        page.putInt(rel, value);
        dirty = true;
    }

    /**
     * Bulk-write {@code len} ints from {@code src} starting at file position
     * {@code intIndex}.
     *
     * <p>Writes directly to the channel in page-sized chunks, bypassing
     * the single-int page cache (which is flushed and invalidated first).
     * No read-before-write: the source array is the sole data source,
     * making this the fastest path for sequential block writes.</p>
     *
     * @param intIndex first file index to write, in {@code [0, totalInts - len]}.
     * @param src      source array.
     * @param off      offset into {@code src}.
     * @param len      number of ints to write.
     * @throws IOException               if a write fails.
     * @throws IllegalArgumentException  if file indices are out of range.
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} are out of
     *                                   bounds for {@code src}.
     */
    public void put(final long intIndex, final int[] src, final int off, final int len) throws IOException
    {
        Objects.checkFromIndexSize(off, len, src.length);
        if (len == 0) {
            return;
        }
        if (intIndex < 0L || intIndex + (long) len > totalInts) {
            throw new IllegalArgumentException(
                "intIndex=" + intIndex + " len=" + len + " totalInts=" + totalInts
            );
        }

        // flush and invalidate the single-int page cache
        flush();
        pageStart = -1L;
        pageLen = 0;
        dirty = false;

        final int intsPerChunk = pageBytes / Integer.BYTES;
        long bytePos = Math.multiplyExact(intIndex, (long) Integer.BYTES);
        int srcPos = off;
        int remaining = len;

        while (remaining > 0) {
            final int count = Math.min(remaining, intsPerChunk);
            final int chunkBytes = count * Integer.BYTES;

            page.clear();
            page.limit(chunkBytes);

            final IntBuffer ib = page.asIntBuffer();
            ib.put(src, srcPos, count);

            long pos = bytePos;
            while (page.hasRemaining()) {
                final int n = channel.write(page, pos);
                if (n <= 0) {
                    throw new IOException("Failed to write at byte position " + pos);
                }
                pos += n;
            }

            bytePos += chunkBytes;
            srcPos += count;
            remaining -= count;
        }
    }

    /** Align a byte position down to the start of its page. */
    private long pageStart(final long bytePos)
    {
        return (bytePos / pageBytes) * (long) pageBytes;
    }

    /**
     * Load the page starting at {@code newPageStart} from the channel.
     * Bytes beyond EOF (if any) are zero-filled.
     */
    private void loadPage(final long newPageStart) throws IOException
    {
        final long remaining = totalBytes - newPageStart;
        final int len = (int) Math.min((long) pageBytes, remaining);

        page.clear();
        page.limit(len);

        long pos = newPageStart;
        while (page.hasRemaining()) {
            final int n = channel.read(page, pos);
            if (n <= 0) {
                break;
            }
            pos += n;
        }

        // zero-fill any bytes not read (sparse / newly extended region)
        while (page.hasRemaining()) {
            page.put((byte) 0);
        }

        pageStart = newPageStart;
        pageLen = len;
        dirty = false;
    }

    /** Write the current page back to the channel if dirty. */
    private void flush() throws IOException
    {
        if (!dirty || pageStart < 0L) {
            return;
        }

        final ByteBuffer src = page.duplicate().order(byteOrder);
        src.clear();
        src.limit(pageLen);

        long pos = pageStart;
        while (src.hasRemaining()) {
            final int n = channel.write(src, pos);
            if (n <= 0) {
                throw new IOException("Failed to flush page at byte position " + pos);
            }
            pos += n;
        }

        dirty = false;
    }

    /**
     * Flush pending writes, force channel to disk, and close the channel.
     * After this call the file is fully released (deletable, renamable).
     * Idempotent: subsequent calls are no-ops.
     */
    @Override
    public void close() throws IOException
    {
        if (!channel.isOpen()) {
            return;
        }
        try {
            flush();
            channel.force(true);
        }
        finally {
            channel.close();
        }
    }

    /**
     * Round {@code pageSize} up to the next {@link Integer#BYTES} boundary,
     * with a minimum of {@code Integer.BYTES}.
     */
    static int normalizePageSize(int pageSize)
    {
        if (pageSize < Integer.BYTES) {
            pageSize = Integer.BYTES;
        }
        final int rem = pageSize & (Integer.BYTES - 1);
        if (rem != 0) {
            pageSize += Integer.BYTES - rem;
        }
        return pageSize;
    }

    /**
     * Set the file to exactly {@code size} bytes.
     *
     * <p>Pre-allocates the file before any positioned writes so that
     * each write does not individually extend the file (which would cause
     * repeated metadata updates and potential fragmentation).
     * {@link FileChannel#truncate} does not grow, so an explicit
     * single-byte write at {@code size - 1} extends when needed.</p>
     *
     * @param channel an open read/write channel.
     * @param size    desired file size in bytes (&ge; 0).
     * @throws IOException              on I/O failure.
     * @throws IllegalArgumentException if {@code size < 0}.
     */
    static void ensureFileSize(final FileChannel channel, final long size) throws IOException
    {
        if (size < 0L) {
            throw new IllegalArgumentException("size < 0: " + size);
        }
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
        one.put((byte) 0);
        one.flip();

        channel.position(size - 1L);
        while (one.hasRemaining()) {
            channel.write(one);
        }
    }
}
