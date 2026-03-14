package com.github.oeuvres.alix.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Lazy, thread-safe holder for a resource that may need to be built
 * before it can be opened.
 *
 * <p>
 * Encapsulates the pattern shared by {@code FieldStats}, {@code TermLexicon},
 * and {@code TermRail}: check if sidecar files exist, build them if not,
 * then open and cache the result. The resolved flag is set only on success;
 * a failed attempt can be retried on the next call.
 * </p>
 *
 * <p>Thread safety: all access must be externally synchronized
 * (typically by the owning {@link FlucText}).</p>
 *
 * @param <T> the resource type
 */
final class LazyResource<T>
{
    /** Tests whether the sidecar file(s) exist. */
    @FunctionalInterface
    interface Existence { boolean test() throws IOException; }

    /** Builds the sidecar file(s) from the index. */
    @FunctionalInterface
    interface Builder { void build() throws IOException; }

    /** Opens and returns the resource from existing sidecar file(s). */
    @FunctionalInterface
    interface Opener<T> { T open() throws IOException; }

    private T value;
    private boolean resolved;

    /**
     * Returns the cached resource, building and opening it on first
     * successful call.
     *
     * <p>
     * If the sidecar does not exist, {@code builder} is invoked first.
     * The resolved flag is set only after successful open — a failure
     * does not prevent a subsequent retry.
     * </p>
     *
     * @param existence tests for sidecar presence
     * @param builder   creates the sidecar if missing
     * @param opener    opens the resource
     * @return the resource, never {@code null}
     * @throws UncheckedIOException if building or opening fails
     */
    T get(
        final Existence existence,
        final Builder builder,
        final Opener<T> opener
    ) {
        if (resolved) {
            return value;
        }
        try {
            if (!existence.test()) {
                builder.build();
            }
            value = opener.open();
            resolved = true;
            return value;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the cached resource without attempting to build.
     * Returns {@code null} if not yet resolved.
     */
    T peek()
    {
        return value;
    }

    /**
     * Returns true if the resource has been successfully loaded.
     */
    boolean isResolved()
    {
        return resolved;
    }

    /**
     * Closes the resource if it implements {@link Closeable},
     * then resets the holder for potential reuse.
     */
    void close()
    {
        if (value instanceof Closeable c) {
            try {
                c.close();
            }
            catch (IOException e) {
                // best-effort cleanup
            }
        }
        value = null;
        resolved = false;
    }
}
