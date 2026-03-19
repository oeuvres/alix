package com.github.oeuvres.alix.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Static file-handling primitives that complement {@link java.nio.file.Files}.
 * <p>
 * Every method addresses a recurring safety gap in the JDK's NIO file API:
 * </p>
 * <ul>
 *   <li><b>Precondition guards</b> — {@link #ensureAbsent(Path)},
 *       {@link #ensureRegularFile(Path)} — fail fast before a write or open begins.</li>
 *   <li><b>Atomic-write-via-temp</b> — {@link #tmpPath(Path)}, {@link #moveTemp(Path, Path)},
 *       {@link #deleteIfExists(Path)} — write to a temporary sibling, rename into place,
 *       clean up on failure.</li>
 *   <li><b>Multi-file coherence</b> — {@link #checkMtimeCoherence(long, Path...)} — cheap
 *       startup guard against files produced by different write operations.</li>
 *   <li><b>Memory-mapping lifecycle</b> — {@link #mapReadOnly(Path)},
 *       {@link #unmap(MappedByteBuffer)} — map a file and release the mapping deterministically
 *       without waiting for garbage collection.</li>
 *   <li><b>Length-prefixed UTF-8 encoding</b> — {@link #readUtf8(DataInputStream)},
 *       {@link #writeUtf8(DataOutputStream, String)} — a compact wire format for embedding
 *       a string field (file header, field name, label) in a binary stream.</li>
 * </ul>
 * <p>
 * All methods are stateless. The class cannot be instantiated.
 * </p>
 *
 * <h2>Unmap strategy</h2>
 * <p>
 * {@link #unmap(MappedByteBuffer)} uses the same reflective
 * {@code sun.misc.Unsafe.invokeCleaner()} path that Lucene's {@code MMapDirectory} relies on.
 * The {@link MethodHandle} is resolved once at class-load time. If the JDK does not expose
 * the entry point (or removes it in a future release), unmap becomes a silent no-op and the
 * buffer is left for garbage collection — safe on Linux, but may delay file-lock release on
 * Windows until GC runs.
 * </p>
 *
 * <h2>Typical write sequence</h2>
 * <pre>{@code
 * Path target = dir.resolve("data.bin");
 * SafeFiles.ensureAbsent(target);
 * Path tmp = SafeFiles.tmpPath(target);
 * SafeFiles.deleteIfExists(tmp);       // clean stale temp from previous crash
 * try {
 *     // ... write to tmp ...
 *     SafeFiles.moveTemp(tmp, target);
 * } catch (IOException | RuntimeException e) {
 *     SafeFiles.deleteIfExists(tmp);
 *     throw e;
 * }
 * }</pre>
 */
public final class SideFiles
{
    /**
     * {@link MethodHandle} for {@code sun.misc.Unsafe.invokeCleaner(ByteBuffer)}.
     * <p>
     * Resolved once at class load; {@code null} if the current JDK does not expose the entry
     * point. This is the same approach Lucene uses in {@code MMapDirectory}.
     * </p>
     */
    private static final MethodHandle INVOKE_CLEANER;

    static {
        MethodHandle mh = null;
        try {
            final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            final java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            final Object unsafe = f.get(null);
            mh = MethodHandles.lookup()
                .findVirtual(unsafeClass, "invokeCleaner", MethodType.methodType(void.class, ByteBuffer.class))
                .bindTo(unsafe);
        }
        catch (Exception ignored) {
            // JDK does not expose invokeCleaner — fall back to GC-based reclaim.
        }
        INVOKE_CLEANER = mh;
    }

    /** Non-instantiable utility class. */
    private SideFiles()
    {
    }

    // -------------------------------------------------------------------------
    //  precondition guards
    // -------------------------------------------------------------------------

    /**
     * Ensures that a path does not already exist.
     * <p>
     * Intended as a precondition check before writing a new file, to prevent silent overwrite
     * of data that was produced by a previous successful build.
     * </p>
     *
     * @param path target path that must not exist
     * @throws FileAlreadyExistsException if the path already exists (as file, directory, or symlink)
     */
    public static void ensureAbsent(final Path path) throws FileAlreadyExistsException
    {
        if (Files.exists(path)) {
            throw new FileAlreadyExistsException(path.toString());
        }
    }

    /**
     * Ensures that a path points to an existing regular file.
     * <p>
     * Intended as a precondition check before opening a file for reading or memory-mapping.
     * Rejects directories, symlinks to directories, and absent paths.
     * </p>
     *
     * @param path path to check
     * @throws NoSuchFileException if the file does not exist or is not a regular file
     */
    public static void ensureRegularFile(final Path path) throws IOException
    {
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(path.toString());
        }
    }

    // -------------------------------------------------------------------------
    //  best-effort cleanup
    // -------------------------------------------------------------------------

    /**
     * Deletes a file if it exists, silently ignoring any failure.
     * <p>
     * Intended for best-effort cleanup of temporary files in {@code catch} or {@code finally}
     * blocks. Callers that need to know whether deletion succeeded should use
     * {@link Files#deleteIfExists(Path)} directly.
     * </p>
     *
     * @param path path to delete; may be {@code null} (no-op)
     */
    public static void deleteIfExists(final Path path)
    {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // best-effort cleanup only
        }
    }

    // -------------------------------------------------------------------------
    //  atomic-write-via-temp
    // -------------------------------------------------------------------------

    /**
     * Returns a temporary sibling path for use during atomic write.
     * <p>
     * The returned path has the same parent and file name as {@code path} with a {@code .tmp}
     * suffix appended. This convention allows stale temporaries from a previous crash to be
     * recognised and cleaned up before a new write begins.
     * </p>
     *
     * @param path final target path
     * @return sibling path with {@code .tmp} suffix
     */
    public static Path tmpPath(final Path path)
    {
        return path.resolveSibling(path.getFileName().toString() + ".tmp");
    }

    /**
     * Atomically moves a temporary file to its final location.
     * <p>
     * Attempts {@link StandardCopyOption#ATOMIC_MOVE} first. If the file system does not
     * support atomic moves (e.g. cross-device, or certain NFS configurations), falls back to
     * a plain move. The caller is responsible for cleanup if the fallback also fails.
     * </p>
     *
     * @param source temporary file path (must exist)
     * @param target final file path (must not exist)
     * @throws IOException if the move fails even with fallback
     */
    public static void moveTemp(final Path source, final Path target) throws IOException
    {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e) {
            Files.move(source, target);
        }
    }

    // -------------------------------------------------------------------------
    //  multi-file coherence
    // -------------------------------------------------------------------------

    /**
     * Checks that the modification times of several files are within a given tolerance.
     * <p>
     * This is a cheap startup guard against opening a set of files that were produced by
     * different write operations, partially copied, or mixed from different snapshots. It does
     * not guarantee logical consistency — the caller's own format validation (magic bytes,
     * version fields, cross-file size checks) provides the hard guarantee.
     * </p>
     *
     * @param toleranceMs maximum tolerated mtime spread, in milliseconds
     * @param paths       files that should have been produced together (at least two)
     * @throws IOException              if the mtime spread exceeds {@code toleranceMs}
     * @throws IllegalArgumentException if {@code toleranceMs} is negative
     */
    public static void checkMtimeCoherence(final long toleranceMs, final Path... paths) throws IOException
    {
        if (toleranceMs < 0) {
            throw new IllegalArgumentException("toleranceMs must be >= 0: " + toleranceMs);
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (final Path path : paths) {
            final long t = Files.getLastModifiedTime(path).toMillis();
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        if ((max - min) > toleranceMs) {
            throw new IOException(
                "File mtimes differ by " + (max - min) + "ms (tolerance " + toleranceMs + "ms);"
                    + " possible partial copy or mixed versions");
        }
    }

    // -------------------------------------------------------------------------
    //  memory mapping
    // -------------------------------------------------------------------------

    /**
     * Memory-maps one file in read-only mode, covering its entire content.
     * <p>
     * The returned buffer should be released via {@link #unmap(MappedByteBuffer)} when no
     * longer needed. If the file is empty, the returned buffer has zero remaining bytes.
     * </p>
     *
     * @param path file path
     * @return read-only memory-mapped byte buffer covering the entire file
     * @throws IOException if the file cannot be opened or mapped
     */
    public static MappedByteBuffer mapReadOnly(final Path path) throws IOException
    {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            return ch.map(FileChannel.MapMode.READ_ONLY, 0L, ch.size());
        }
    }

    /**
     * Attempts to release a {@link MappedByteBuffer} immediately via
     * {@code sun.misc.Unsafe.invokeCleaner()}.
     * <p>
     * This is a best-effort operation. If the reflective call is unavailable or fails at
     * invocation time, the buffer is silently left for garbage collection.
     * </p>
     * <p>
     * After a successful unmap the buffer must not be accessed — any read or write through it
     * will throw an {@link java.lang.InternalError}.
     * </p>
     *
     * @param buf the mapped buffer to unmap, or {@code null} (no-op)
     */
    public static void unmap(final MappedByteBuffer buf)
    {
        if (buf == null || INVOKE_CLEANER == null) {
            return;
        }
        try {
            INVOKE_CLEANER.invokeExact((ByteBuffer) buf);
        }
        catch (Throwable ignored) {
            // best-effort — buffer will be reclaimed by GC
        }
    }

    // -------------------------------------------------------------------------
    //  length-prefixed UTF-8 encoding
    // -------------------------------------------------------------------------

    /**
     * Reads one UTF-8 string preceded by its 4-byte big-endian byte length.
     * <p>
     * Wire format: {@code int32 n} then {@code n} UTF-8 bytes.
     * This is the encoding written by {@link #writeUtf8(DataOutputStream, String)}.
     * </p>
     *
     * @param in source stream, positioned just before the 4-byte length prefix
     * @return decoded string, never {@code null}
     * @throws IOException if reading fails or if the encoded length is negative
     */
    public static String readUtf8(final DataInputStream in) throws IOException
    {
        final int length = in.readInt();
        if (length < 0) {
            throw new IOException("Negative UTF-8 byte length: " + length);
        }
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Writes one UTF-8 string preceded by its 4-byte big-endian byte length.
     * <p>
     * Wire format: {@code int32 n} then {@code n} UTF-8 bytes.
     * Readable by {@link #readUtf8(DataInputStream)}.
     * </p>
     *
     * @param out destination stream
     * @param s   string to write, must not be {@code null}
     * @throws IOException if writing fails
     */
    public static void writeUtf8(final DataOutputStream out, final String s) throws IOException
    {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
