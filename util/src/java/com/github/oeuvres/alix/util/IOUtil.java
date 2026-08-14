package com.github.oeuvres.alix.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Static file-handling primitives that complement {@link java.nio.file.Files}.
 * <p>
 * The class provides recurring guards and I/O patterns used by Alix: precondition checks,
 * atomic-write-via-temp helpers, multi-file modification-time coherence checks, arena-owned
 * read-only memory mappings, and a compact length-prefixed UTF-8 encoding.
 * </p>
 * <h2>Memory mapping</h2>
 * <p>
 * {@link #mapReadOnly(Path, Arena)} uses the supported foreign-memory mapping API introduced in
 * Java 22. The returned {@link MemorySegment} is owned by the supplied {@link Arena}; closing a
 * closeable arena deterministically unmaps all segments mapped through it. No reflective
 * {@code sun.misc.Unsafe} cleaner is used.
 * </p>
 * <h2>Typical write sequence</h2>
 * <pre>{@code
 * Path target = dir.resolve("data.bin");
 * IOUtil.ensureAbsent(target);
 * Path tmp = IOUtil.tmpPath(target);
 * IOUtil.deleteIfExists(tmp);
 * try {
 *     // ... write to tmp ...
 *     IOUtil.moveTemp(tmp, target);
 * } catch (IOException | RuntimeException e) {
 *     IOUtil.deleteIfExists(tmp);
 *     throw e;
 * }
 * }</pre>
 */
public final class IOUtil
{
    /**
     * Non-instantiable utility class.
     */
    private IOUtil()
    {
    }

    /**
     * Checks that the modification times of several files are within a given tolerance.
     * <p>
     * This is a cheap startup guard against opening a set of files that were produced by
     * different write operations, partially copied, or mixed from different snapshots. It does
     * not guarantee logical consistency; callers must still validate their own file formats.
     * </p>
     *
     * @param toleranceMs maximum tolerated mtime spread, in milliseconds
     * @param paths files that should have been produced together
     * @throws IOException if reading a modification time fails or the spread exceeds the tolerance
     * @throws IllegalArgumentException if {@code toleranceMs} is negative
     * @throws NullPointerException if {@code paths} or one of its elements is {@code null}
     */
    public static void checkMtimeCoherence(
        final long toleranceMs,
        final Path... paths
    ) throws IOException {
        if (toleranceMs < 0) {
            throw new IllegalArgumentException("toleranceMs must be >= 0: " + toleranceMs);
        }
        Objects.requireNonNull(paths, "paths");
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (final Path path : paths) {
            Objects.requireNonNull(path, "path");
            final long time = Files.getLastModifiedTime(path).toMillis();
            min = Math.min(min, time);
            max = Math.max(max, time);
        }
        if ((max - min) > toleranceMs) {
            throw new IOException(
                "File mtimes differ by " + (max - min) + "ms (tolerance " + toleranceMs + "ms);"
                    + " possible partial copy or mixed versions");
        }
    }

    /**
     * Deletes a file if it exists, silently ignoring any failure.
     * <p>
     * Intended for best-effort cleanup of temporary files in {@code catch} or {@code finally}
     * blocks. Callers that need to know whether deletion succeeded should use
     * {@link Files#deleteIfExists(Path)} directly.
     * </p>
     *
     * @param path path to delete; {@code null} is accepted as a no-op
     */
    public static void deleteIfExists(
        final Path path
    ) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    /**
     * Ensures that a path does not already exist.
     *
     * @param path target path that must not exist
     * @throws FileAlreadyExistsException if the path already exists
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static void ensureAbsent(
        final Path path
    ) throws FileAlreadyExistsException {
        Objects.requireNonNull(path, "path");
        if (Files.exists(path)) {
            throw new FileAlreadyExistsException(path.toString());
        }
    }

    /**
     * Ensures that a path points to an existing regular file.
     *
     * @param path path to check
     * @throws IOException if the file does not exist or is not a regular file
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static void ensureRegularFile(
        final Path path
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(path.toString());
        }
    }

    /**
     * Memory-maps an entire file read-only into the supplied arena.
     * <p>
     * The returned segment remains valid while the arena is alive. If the arena is closeable,
     * closing it unmaps the segment deterministically. The channel itself is closed before this
     * method returns; that does not invalidate the mapping.
     * </p>
     *
     * @param path file to map
     * @param arena arena that owns the mapping lifetime
     * @return read-only memory segment covering the complete file
     * @throws IOException if the file cannot be opened or mapped
     * @throws IllegalStateException if the arena is not alive
     * @throws NullPointerException if {@code path} or {@code arena} is {@code null}
     */
    public static MemorySegment mapReadOnly(
        final Path path,
        final Arena arena
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(arena, "arena");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size(), arena);
        }
    }

    /**
     * Atomically moves a temporary file to its final location when supported by the file system.
     * <p>
     * If {@link StandardCopyOption#ATOMIC_MOVE} is not supported, the method falls back to a
     * regular move. The target is never replaced explicitly.
     * </p>
     *
     * @param source temporary file path
     * @param target final file path
     * @throws IOException if both the atomic and fallback moves fail
     * @throws NullPointerException if {@code source} or {@code target} is {@code null}
     */
    public static void moveTemp(
        final Path source,
        final Path target
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e) {
            Files.move(source, target);
        }
    }

    /**
     * Resolves and opens a classpath resource as an input stream.
     *
     * @param anchor anchor class used to resolve the resource
     * @param resourcePath classpath resource path
     * @return open input stream for the resource
     * @throws IOException if the resource cannot be found
     * @throws NullPointerException if {@code anchor} or {@code resourcePath} is {@code null}
     */
    public static InputStream openResource(
        final Class<?> anchor,
        final String resourcePath
    ) throws IOException {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        final InputStream is = anchor.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        return is;
    }

    /**
     * Reads one UTF-8 string preceded by its 4-byte big-endian byte length.
     *
     * @param in source stream positioned before the length prefix
     * @return decoded string
     * @throws IOException if reading fails or the encoded length is negative
     * @throws NullPointerException if {@code in} is {@code null}
     */
    public static String readUtf8(
        final DataInputStream in
    ) throws IOException {
        Objects.requireNonNull(in, "in");
        final int length = in.readInt();
        if (length < 0) {
            throw new IOException("Negative UTF-8 byte length: " + length);
        }
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns a temporary sibling path with a {@code .tmp} suffix.
     *
     * @param path final target path
     * @return sibling path whose file name is the target file name plus {@code .tmp}
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static Path tmpPath(
        final Path path
    ) {
        Objects.requireNonNull(path, "path");
        return path.resolveSibling(path.getFileName().toString() + ".tmp");
    }

    /**
     * Writes one UTF-8 string preceded by its 4-byte big-endian byte length.
     *
     * @param out destination stream
     * @param s string to write
     * @throws IOException if writing fails
     * @throws NullPointerException if {@code out} or {@code s} is {@code null}
     */
    public static void writeUtf8(
        final DataOutputStream out,
        final String s
    ) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(s, "s");
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
