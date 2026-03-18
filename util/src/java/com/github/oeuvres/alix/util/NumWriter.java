package com.github.oeuvres.alix.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Random-access writer for flat binary files of primitive values,
 * supporting files larger than 2&nbsp;GB.
 *
 * <p>All positions are <em>byte offsets</em>. The caller is responsible
 * for computing byte positions from logical element indices
 * (e.g.&nbsp;{@code intIndex * Integer.BYTES}).
 * Bulk writes require positions aligned to the element size.</p>
 *
 * <p>Uses positioned {@link FileChannel} I/O with a single direct
 * {@link ByteBuffer} page. No {@code MappedByteBuffer} is used,
 * so the file is fully released on {@link #close()}
 * (safe on Windows).</p>
 *
 * <p>Defaults: {@link ByteOrder#LITTLE_ENDIAN}, page size 1&nbsp;MB.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * long totalInts = 600_000_000L;
 * long totalBytes = totalInts * Integer.BYTES;
 * try (NumWriter w = NumWriter.open(path, totalBytes)) {
 *     w.fill((byte) 0);
 *     w.putInt(42L * Integer.BYTES, 7);
 *     int[] block = {10, 20, 30};
 *     w.put(100L * Integer.BYTES, block, 0, block.length);
 * }
 * }</pre>
 */
public final class NumWriter implements Closeable
{
    /** Default page size: 1 MB. */
    public static final int DEFAULT_PAGE_SIZE = 1 << 20;

    /** Default byte order: little-endian (x86-64, ARM). */
    public static final ByteOrder DEFAULT_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    private final FileChannel channel;
    private final long totalBytes;
    private final ByteOrder byteOrder;
    private final int pageBytes;
    private final ByteBuffer page;

    private long pageStart = -1L;
    private int pageLen = 0;
    private boolean dirty = false;

    private NumWriter(
        final FileChannel channel,
        final long totalBytes,
        final ByteOrder byteOrder,
        final int pageBytes
    ) {
        this.channel = channel;
        this.totalBytes = totalBytes;
        this.byteOrder = byteOrder;
        this.pageBytes = pageBytes;
        this.page = ByteBuffer.allocateDirect(pageBytes).order(byteOrder);
    }

    /**
     * Open (create or overwrite) a file for random primitive writes,
     * with default byte order and page size.
     *
     * @param path       destination file.
     * @param totalBytes exact file size in bytes.
     * @return a new writer; caller must close it.
     * @throws IOException              if the file cannot be opened or sized.
     * @throws IllegalArgumentException if {@code totalBytes < 0}.
     */
    public static NumWriter open(
        final Path path,
        final long totalBytes
    ) throws IOException {
        return open(path, totalBytes, DEFAULT_BYTE_ORDER, DEFAULT_PAGE_SIZE);
    }

    /**
     * Open (create or overwrite) a file for random primitive writes,
     * with explicit byte order and default page size.
     *
     * @param path       destination file.
     * @param totalBytes exact file size in bytes.
     * @param byteOrder  byte order for stored values.
     * @return a new writer; caller must close it.
     * @throws IOException              if the file cannot be opened or sized.
     * @throws IllegalArgumentException if {@code totalBytes < 0}.
     */
    public static NumWriter open(
        final Path path,
        final long totalBytes,
        final ByteOrder byteOrder
    ) throws IOException {
        return open(path, totalBytes, byteOrder, DEFAULT_PAGE_SIZE);
    }

    /**
     * Open (create or overwrite) a file for random primitive writes.
     *
     * <p>The file is pre-allocated to {@code totalBytes}.
     * The returned writer must be {@link #close() closed} to flush pending
     * writes and release the underlying channel.</p>
     *
     * @param path       destination file.
     * @param totalBytes exact file size in bytes.
     * @param byteOrder  byte order for stored values.
     * @param pageSize   internal page buffer size in bytes;
     *                   rounded up to a {@link Long#BYTES} boundary
     *                   (minimum {@code Long.BYTES}).
     * @return a new writer; caller must close it.
     * @throws IOException              if the file cannot be opened or sized.
     * @throws IllegalArgumentException if {@code totalBytes < 0}.
     */
    public static NumWriter open(
        final Path path,
        final long totalBytes,
        final ByteOrder byteOrder,
        int pageSize
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(byteOrder, "byteOrder");

        if (totalBytes < 0L) {
            throw new IllegalArgumentException("totalBytes < 0: " + totalBytes);
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
            ensureFileSize(channel, totalBytes);
            final NumWriter writer = new NumWriter(channel, totalBytes, byteOrder, pageSize);
            ok = true;
            return writer;
        }
        finally {
            if (!ok) {
                channel.close();
            }
        }
    }

    // ---- fill -------------------------------------------------------------

    /**
     * Fill the entire file with a constant byte value.
     * Typical use: {@code fill((byte) 0)} to zero the file.
     * Invalidates any cached page.
     *
     * @param value the byte to write at every position.
     * @throws IOException if a write fails.
     */
    public void fill(final byte value) throws IOException
    {
        flush();

        if (totalBytes == 0L) {
            return;
        }

        final ByteBuffer fillPage = ByteBuffer.allocateDirect(pageBytes);
        while (fillPage.hasRemaining()) {
            fillPage.put(value);
        }

        long writtenBytes = 0L;
        while (writtenBytes < totalBytes) {
            final int len = (int) Math.min((long) pageBytes, totalBytes - writtenBytes);

            final ByteBuffer src = fillPage.duplicate();
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

        pageStart = -1L;
        pageLen = 0;
        dirty = false;
    }

    // ---- single-element puts ----------------------------------------------

    /**
     * Write a single {@code int} at the given byte position.
     *
     * @param bytePos byte offset, must be in {@code [0, totalBytes - 4)}.
     * @param value   the value to write.
     * @throws IOException              if a read or write fails.
     * @throws IllegalArgumentException if {@code bytePos} is out of range.
     */
    public void put(final long bytePos, final int value) throws IOException
    {
        checkRange(bytePos, Integer.BYTES);
        ensurePage(bytePos);
        page.putInt((int) (bytePos - pageStart), value);
        dirty = true;
    }

    /**
     * Write a single {@code long} at the given byte position.
     *
     * @param bytePos byte offset, must be in {@code [0, totalBytes - 8)}.
     * @param value   the value to write.
     * @throws IOException              if a read or write fails.
     * @throws IllegalArgumentException if {@code bytePos} is out of range.
     */
    public void put(final long bytePos, final long value) throws IOException
    {
        checkRange(bytePos, Long.BYTES);
        ensurePage(bytePos);
        page.putLong((int) (bytePos - pageStart), value);
        dirty = true;
    }

    /**
     * Write a single {@code double} at the given byte position.
     *
     * @param bytePos byte offset, must be in {@code [0, totalBytes - 8)}.
     * @param value   the value to write.
     * @throws IOException              if a read or write fails.
     * @throws IllegalArgumentException if {@code bytePos} is out of range.
     */
    public void put(final long bytePos, final double value) throws IOException
    {
        checkRange(bytePos, Double.BYTES);
        ensurePage(bytePos);
        page.putDouble((int) (bytePos - pageStart), value);
        dirty = true;
    }

    /**
     * Write a single {@code char} at the given byte position.
     *
     * @param bytePos byte offset, must be in {@code [0, totalBytes - 2)}.
     * @param value   the value to write.
     * @throws IOException              if a read or write fails.
     * @throws IllegalArgumentException if {@code bytePos} is out of range.
     */
    public void put(final long bytePos, final char value) throws IOException
    {
        checkRange(bytePos, Character.BYTES);
        ensurePage(bytePos);
        page.putChar((int) (bytePos - pageStart), value);
        dirty = true;
    }

    // ---- bulk puts --------------------------------------------------------

    /**
     * Bulk-write {@code len} ints starting at the given byte position.
     *
     * <p>Writes directly to the channel in page-sized chunks.
     * No read-before-write. The single-element page cache is
     * flushed and invalidated.</p>
     *
     * @param bytePos byte offset (must be {@code int}-aligned).
     * @param src     source array.
     * @param off     offset into {@code src}.
     * @param len     number of ints to write.
     * @throws IOException               if a write fails.
     * @throws IllegalArgumentException  if byte range exceeds file bounds.
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} exceed {@code src}.
     */
    public void put(final long bytePos, final int[] src, final int off, final int len) throws IOException
    {
        Objects.checkFromIndexSize(off, len, src.length);
        if (len == 0) {
            return;
        }
        checkRange(bytePos, (long) len * Integer.BYTES);
        invalidate();

        final int intsPerChunk = pageBytes / Integer.BYTES;
        long pos = bytePos;
        int srcPos = off;
        int remaining = len;

        while (remaining > 0) {
            final int count = Math.min(remaining, intsPerChunk);
            final int chunkBytes = count * Integer.BYTES;

            page.clear();
            page.limit(chunkBytes);
            page.asIntBuffer().put(src, srcPos, count);

            writePage(pos, chunkBytes);

            pos += chunkBytes;
            srcPos += count;
            remaining -= count;
        }
    }

    /**
     * Bulk-write {@code len} longs starting at the given byte position.
     *
     * @param bytePos byte offset (must be {@code long}-aligned).
     * @param src     source array.
     * @param off     offset into {@code src}.
     * @param len     number of longs to write.
     * @throws IOException               if a write fails.
     * @throws IllegalArgumentException  if byte range exceeds file bounds.
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} exceed {@code src}.
     */
    public void put(final long bytePos, final long[] src, final int off, final int len) throws IOException
    {
        Objects.checkFromIndexSize(off, len, src.length);
        if (len == 0) {
            return;
        }
        checkRange(bytePos, (long) len * Long.BYTES);
        invalidate();

        final int longsPerChunk = pageBytes / Long.BYTES;
        long pos = bytePos;
        int srcPos = off;
        int remaining = len;

        while (remaining > 0) {
            final int count = Math.min(remaining, longsPerChunk);
            final int chunkBytes = count * Long.BYTES;

            page.clear();
            page.limit(chunkBytes);
            page.asLongBuffer().put(src, srcPos, count);

            writePage(pos, chunkBytes);

            pos += chunkBytes;
            srcPos += count;
            remaining -= count;
        }
    }

    /**
     * Bulk-write {@code len} doubles starting at the given byte position.
     *
     * @param bytePos byte offset (must be {@code double}-aligned).
     * @param src     source array.
     * @param off     offset into {@code src}.
     * @param len     number of doubles to write.
     * @throws IOException               if a write fails.
     * @throws IllegalArgumentException  if byte range exceeds file bounds.
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} exceed {@code src}.
     */
    public void put(final long bytePos, final double[] src, final int off, final int len) throws IOException
    {
        Objects.checkFromIndexSize(off, len, src.length);
        if (len == 0) {
            return;
        }
        checkRange(bytePos, (long) len * Double.BYTES);
        invalidate();

        final int doublesPerChunk = pageBytes / Double.BYTES;
        long pos = bytePos;
        int srcPos = off;
        int remaining = len;

        while (remaining > 0) {
            final int count = Math.min(remaining, doublesPerChunk);
            final int chunkBytes = count * Double.BYTES;

            page.clear();
            page.limit(chunkBytes);
            page.asDoubleBuffer().put(src, srcPos, count);

            writePage(pos, chunkBytes);

            pos += chunkBytes;
            srcPos += count;
            remaining -= count;
        }
    }

    /**
     * Bulk-write {@code len} chars starting at the given byte position.
     *
     * @param bytePos byte offset (must be {@code char}-aligned).
     * @param src     source array.
     * @param off     offset into {@code src}.
     * @param len     number of chars to write.
     * @throws IOException               if a write fails.
     * @throws IllegalArgumentException  if byte range exceeds file bounds.
     * @throws IndexOutOfBoundsException if {@code off}/{@code len} exceed {@code src}.
     */
    public void put(final long bytePos, final char[] src, final int off, final int len) throws IOException
    {
        Objects.checkFromIndexSize(off, len, src.length);
        if (len == 0) {
            return;
        }
        checkRange(bytePos, (long) len * Character.BYTES);
        invalidate();

        final int charsPerChunk = pageBytes / Character.BYTES;
        long pos = bytePos;
        int srcPos = off;
        int remaining = len;

        while (remaining > 0) {
            final int count = Math.min(remaining, charsPerChunk);
            final int chunkBytes = count * Character.BYTES;

            page.clear();
            page.limit(chunkBytes);
            page.asCharBuffer().put(src, srcPos, count);

            writePage(pos, chunkBytes);

            pos += chunkBytes;
            srcPos += count;
            remaining -= count;
        }
    }

    // ---- internal I/O -----------------------------------------------------

    /** Check that {@code [bytePos, bytePos + size)} fits in the file. */
    private void checkRange(final long bytePos, final long size)
    {
        if (bytePos < 0L || bytePos + size > totalBytes) {
            throw new IllegalArgumentException(
                "bytePos=" + bytePos + " size=" + size + " totalBytes=" + totalBytes
            );
        }
    }

    /** Ensure the page covers {@code bytePos}; flush and reload if needed. */
    private void ensurePage(final long bytePos) throws IOException
    {
        final long wanted = pageStart(bytePos);
        if (wanted != pageStart) {
            flush();
            loadPage(wanted);
        }
    }

    /** Flush dirty page, then invalidate it (for bulk writes). */
    private void invalidate() throws IOException
    {
        flush();
        pageStart = -1L;
        pageLen = 0;
        dirty = false;
    }

    /** Write {@code len} bytes from the page buffer to the channel at {@code pos}. */
    private void writePage(long pos, final int len) throws IOException
    {
        page.clear();
        page.limit(len);
        while (page.hasRemaining()) {
            final int n = channel.write(page, pos);
            if (n <= 0) {
                throw new IOException("Failed to write at byte position " + pos);
            }
            pos += n;
        }
    }

    /** Align a byte position down to the start of its page. */
    private long pageStart(final long bytePos)
    {
        return (bytePos / pageBytes) * (long) pageBytes;
    }

    /**
     * Load the page starting at {@code newPageStart} from the channel.
     * Bytes beyond EOF are zero-filled.
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
     * Round {@code pageSize} up to the next {@link Long#BYTES} boundary,
     * with a minimum of {@code Long.BYTES} (8). This guarantees alignment
     * for all supported primitive types.
     */
    static int normalizePageSize(int pageSize)
    {
        if (pageSize < Long.BYTES) {
            pageSize = Long.BYTES;
        }
        final int rem = pageSize & (Long.BYTES - 1);
        if (rem != 0) {
            pageSize += Long.BYTES - rem;
        }
        return pageSize;
    }

    /**
     * Set the file to exactly {@code size} bytes.
     *
     * <p>Pre-allocates the file so that positioned writes do not
     * individually extend it (avoiding repeated metadata updates
     * and fragmentation).</p>
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
