package com.github.oeuvres.alix.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

public final class Cache
{
  private Cache() {}

    private static final ConcurrentHashMap<Key<?>, CompletableFuture<?>> map = new ConcurrentHashMap<>();

    public record Key<T>(Class<T> type, Object namespace, List<String> pars)
    {
        public Key {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(pars, "pars");
            pars = List.copyOf(pars);
        }

        static <T> Key<T> of(Class<T> type, Object namespace, String... pars)
        {
            Objects.requireNonNull(pars, "pars");
            // Clone varargs array to avoid later caller mutation affecting the key.
            return new Key<>(type, namespace, Arrays.asList(pars.clone()));
        }
    }

    /**
     * Get or build a value exactly once per key. All concurrent callers wait until
     * the value is fully built. If build fails, the entry is removed (retry next
     * time).
     */
    public static <T> T get(Class<T> type, Object namespace, Function<List<String>, ? extends T> builder,
            String... pars)
    {
        Key<T> key = Key.of(type, namespace, pars);

        @SuppressWarnings("unchecked")
        CompletableFuture<T> existing = (CompletableFuture<T>) map.get(key);
        if (existing != null)
            return await(type, key, existing);

        CompletableFuture<T> created = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<T> raced = (CompletableFuture<T>) map.putIfAbsent(key, created);
        CompletableFuture<T> f = (raced != null) ? raced : created;

        if (raced == null) {
            // We won: compute outside the map; complete the placeholder.
            try {
                T v = builder.apply(key.pars()); // builder sees the canonical params
                if (v == null)
                    throw new NullPointerException("builder returned null for key=" + key);
                created.complete(v);
            } catch (Throwable t) {
                created.completeExceptionally(t);
                map.remove(key, created); // do not cache failures
            }
        }

        return await(type, key, f);
    }

    private static <T> T await(Class<T> type, Key<T> key, CompletableFuture<T> f)
    {
        try {
            return type.cast(f.get()); // interruptible wait; blocks until complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for cache value: " + key, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re)
                throw re;
            if (cause instanceof Error err)
                throw err;
            throw new RuntimeException(cause);
        }
    }


    public static void invalidate(Class<?> type, Object namespace, String... pars)
    {
        map.remove(Key.of(type, namespace, pars));
    }
}
