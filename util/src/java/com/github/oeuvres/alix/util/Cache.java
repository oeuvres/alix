package com.github.oeuvres.alix.util;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Process-local concurrent cache keyed by a 3-part key:
 * <ul>
 *   <li>{@code namespace}: the cache owner / cacher (typically an object instance)</li>
 *   <li>{@code name}: a logical entry name within that namespace</li>
 *   <li>{@code pars}: optional string parameters refining the entry</li>
 * </ul>
 *
 * <p><strong>Concurrency contract.</strong> For a given key, at most one caller executes the
 * builder at a time. Other concurrent callers wait for the same result. The value is published only
 * when fully built.</p>
 *
 * <p><strong>Failure contract.</strong> If the builder throws, the failure is propagated to all
 * waiters and the entry is removed from the cache (failures are not cached). A later call can retry.</p>
 *
 * <p><strong>Null contract.</strong> Builder results must be non-null. A {@code null} result is
 * treated as a programming error and converted to {@link NullPointerException}.</p>
 *
 * <p><strong>Typing note.</strong> This cache is intentionally typeless at runtime (no class token in
 * the key). Callers must ensure they do not reuse the same key for incompatible value types.</p>
 *
 * <p><strong>Namespace equality.</strong> The {@code namespace} component uses <em>identity</em>
 * equality ({@code ==}), not {@link Object#equals(Object)}. This avoids accidental collisions
 * between distinct cacher instances that happen to be equal.</p>
 *
 * <p>This class is static-only and thread-safe.</p>
 */
public final class Cache {

    /** Placeholder/result storage. Values are completed futures to coordinate concurrent builders. */
    private static final ConcurrentHashMap<Key<?>, CompletableFuture<?>> MAP = new ConcurrentHashMap<>();

    private Cache() {
        // Utility class; no instances.
    }

    /**
     * Immutable cache key.
     *
     * <p>The {@code pars} list is canonicalized to an unmodifiable copy. If {@code pars} is
     * {@code null}, it is normalized to an empty list.</p>
     *
     * <p><strong>Equality semantics:</strong></p>
     * <ul>
     *   <li>{@code namespace}: identity-based ({@code ==})</li>
     *   <li>{@code name}: value-based ({@link String#equals(Object)})</li>
     *   <li>{@code pars}: value-based ({@link List#equals(Object)})</li>
     * </ul>
     *
     * @param <T> expected value type (compile-time only; not enforced at runtime)
     * @param namespace cache owner / cacher; must not be {@code null}
     * @param name entry name within the namespace; must not be {@code null} or blank
     * @param pars optional parameters; may be {@code null}, normalized to an empty immutable list
     */
    public static record Key<T>(Object namespace, String name, List<String> pars) {

        /**
         * Canonical constructor with validation and normalization.
         *
         * @param namespace cache owner / cacher; must not be {@code null}
         * @param name entry name; must not be {@code null} or blank
         * @param pars optional parameters; may be {@code null}
         * @throws NullPointerException if {@code namespace} or {@code name} is {@code null}
         * @throws IllegalArgumentException if {@code name} is blank
         * @throws NullPointerException if {@code pars} contains a {@code null} element
         */
        public Key {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }

            if (pars == null) {
                pars = List.of();
            } else {
                pars = List.copyOf(pars); // immutable defensive copy; rejects null elements
            }
        }

        /**
         * Factory for varargs parameters.
         *
         * <p>The varargs array is cloned before conversion to protect the key from later caller-side
         * mutations. A {@code null} varargs array is normalized to an empty parameter list.</p>
         *
         * @param namespace cache owner / cacher; must not be {@code null}
         * @param name entry name; must not be {@code null} or blank
         * @param pars optional parameter values; may be omitted or {@code null}
         * @param <T> expected value type (compile-time only)
         * @return a canonical immutable key
         * @throws NullPointerException if {@code namespace} or {@code name} is {@code null},
         *         or if {@code pars} contains a {@code null} element
         * @throws IllegalArgumentException if {@code name} is blank
         */
        public static <T> Key<T> of(final Object namespace, final String name, final String... pars) {
            final List<String> parList;
            if (pars == null || pars.length == 0) {
                parList = List.of();
            } else {
                parList = List.copyOf(Arrays.asList(pars.clone())); // defensive copy + immutable
            }
            return new Key<>(namespace, name, parList);
        }

        /**
         * Identity-based equality for {@code namespace}; value equality for other fields.
         *
         * @param o other object
         * @return {@code true} if keys are equivalent
         */
        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof Key<?> other)) return false;
            return this.namespace == other.namespace
                    && this.name.equals(other.name)
                    && this.pars.equals(other.pars);
        }

        /**
         * Hash code consistent with {@link #equals(Object)} using identity hash for namespace.
         *
         * @return hash code
         */
        @Override
        public int hashCode() {
            int result = System.identityHashCode(namespace);
            result = 31 * result + name.hashCode();
            result = 31 * result + pars.hashCode();
            return result;
        }
    }

    /**
     * Removes all entries currently stored in the cache.
     *
     * <p>This is a global operation affecting all namespaces.</p>
     */
    public static void clear() {
        MAP.clear();
    }

    /**
     * Returns the cached value for the given key, computing it exactly once if absent.
     *
     * <p>If multiple threads call this method concurrently with the same key, only one builder
     * invocation occurs; the others wait for the same result.</p>
     *
     * <p>The builder receives the canonical immutable parameter list ({@code key.pars()}).</p>
     *
     * <p>Builder failures are not cached: the entry is removed and the exception is propagated.</p>
     *
     * @param namespace cache owner / cacher; must not be {@code null}
     * @param name entry name within the namespace; must not be {@code null} or blank
     * @param builder value builder; receives the immutable parameter list; must not return {@code null}
     * @param pars optional parameters; may be omitted or {@code null}
     * @param <T> expected result type
     * @return cached or newly built value
     * @throws NullPointerException if {@code namespace}, {@code name}, or {@code builder} is null,
     *         or if the builder returns null
     * @throws IllegalArgumentException if {@code name} is blank
     * @throws RuntimeException wraps checked builder exceptions
     * @throws Error rethrows builder errors unchanged
     */
    public static <T> T get(
            final Object namespace,
            final String name,
            final Function<? super List<String>, ? extends T> builder,
            final String... pars
    ) {
        Objects.requireNonNull(builder, "builder");

        final Key<T> key = Key.of(namespace, name, pars);

        @SuppressWarnings("unchecked")
        final CompletableFuture<T> existing = (CompletableFuture<T>) MAP.get(key);
        if (existing != null) {
            return await(key, existing);
        }

        final CompletableFuture<T> created = new CompletableFuture<>();

        @SuppressWarnings("unchecked")
        final CompletableFuture<T> raced = (CompletableFuture<T>) MAP.putIfAbsent(key, created);

        final CompletableFuture<T> future = (raced != null) ? raced : created;

        if (raced == null) {
            // This caller owns the computation for this key.
            try {
                final T value = builder.apply(key.pars());
                if (value == null) {
                    throw new NullPointerException("builder returned null for key: " + key);
                }
                created.complete(value);
            } catch (Throwable t) {
                created.completeExceptionally(t);
                MAP.remove(key, created); // do not cache failures
            }
        }

        return await(key, future);
    }

    /**
     * Removes a cached entry if present.
     *
     * @param namespace cache owner / cacher; must not be {@code null}
     * @param name entry name; must not be {@code null} or blank
     * @param pars optional parameters; may be omitted or {@code null}
     * @throws NullPointerException if {@code namespace} or {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static void invalidate(final Object namespace, final String name, final String... pars) {
        MAP.remove(Key.of(namespace, name, pars));
    }

    /**
     * Returns the current number of entries (completed or in-progress).
     *
     * <p>This is primarily intended for diagnostics/tests. The value is a snapshot and may change
     * immediately in concurrent use.</p>
     *
     * @return current map size
     */
    public static int size() {
        return MAP.size();
    }

    /**
     * Waits for a future and unwraps exceptions in a cache-friendly way.
     *
     * <p>This method is intentionally typeless at runtime: no {@code Class<T>} token is required
     * because the cache key does not carry a runtime type. The cast is unchecked and relies on the
     * caller using consistent types per key.</p>
     *
     * @param key cache key (used for diagnostics)
     * @param future future to await
     * @param <T> expected value type
     * @return completed value
     * @throws RuntimeException if interrupted (with interrupt status restored), or wrapping checked exceptions
     * @throws Error rethrows builder errors unchanged
     */
    @SuppressWarnings("unchecked")
    private static <T> T await(final Key<T> key, final CompletableFuture<?> future) {
        try {
            return (T) future.get(); // interruptible wait
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for cache value: " + key, e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException("Cache builder failed for key: " + key, cause);
        }
    }
}
