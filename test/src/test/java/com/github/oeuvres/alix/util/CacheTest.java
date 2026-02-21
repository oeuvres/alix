package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CacheTest {

    @BeforeEach
    void setUp() {
        Cache.clear();
    }

    @AfterEach
    void tearDown() {
        Cache.clear();
    }

    @Test
    void get_shouldCacheValue_andCallBuilderOnlyOnce_forSameKey() {
        Object ns = new Object();
        AtomicInteger calls = new AtomicInteger();

        String v1 = Cache.get(ns, "greeting", pars -> {
            calls.incrementAndGet();
            return "hello";
        });

        String v2 = Cache.get(ns, "greeting", pars -> {
            calls.incrementAndGet();
            return "world"; // must not be used
        });

        assertEquals("hello", v1);
        assertEquals("hello", v2);
        assertEquals(1, calls.get(), "Builder must run once for same key");
        assertEquals(1, Cache.size());
    }

    @Test
    void get_shouldUseNamespaceIdentity_notEquals() {
        EqNamespace ns1 = new EqNamespace("same");
        EqNamespace ns2 = new EqNamespace("same");
        assertNotSame(ns1, ns2);
        assertEquals(ns1, ns2, "Precondition: equals() says same");

        AtomicInteger calls = new AtomicInteger();

        String a = Cache.get(ns1, "entry", pars -> {
            calls.incrementAndGet();
            return "A";
        });

        String b = Cache.get(ns2, "entry", pars -> {
            calls.incrementAndGet();
            return "B";
        });

        assertEquals("A", a);
        assertEquals("B", b);
        assertEquals(2, calls.get(), "Distinct namespace instances must not collide");
        assertEquals(2, Cache.size());
    }

    @Test
    void get_shouldDistinguishParameterLists() {
        Object ns = new Object();
        AtomicInteger calls = new AtomicInteger();

        String a = Cache.get(ns, "sum", pars -> {
            calls.incrementAndGet();
            return String.join(",", pars);
        }, "1", "2");

        String b = Cache.get(ns, "sum", pars -> {
            calls.incrementAndGet();
            return String.join(",", pars);
        }, "1", "3");

        assertEquals("1,2", a);
        assertEquals("1,3", b);
        assertEquals(2, calls.get());
        assertEquals(2, Cache.size());
    }

    @Test
    void get_shouldPassImmutableCanonicalParsListToBuilder() {
        Object ns = new Object();

        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> Cache.get(ns, "immutablePars", pars -> {
                    // The cache contract says pars is immutable.
                    pars.add("x");
                    return "unreachable";
                }, "a", "b")
        );

        assertNotNull(ex);
        assertEquals(0, Cache.size(), "Failure must not be cached");
    }

    @Test
    void keyFactory_shouldDefensivelyCopyVarargs() {
        Object ns = new Object();
        String[] arr = {"x", "y"};

        Cache.Key<String> key = Cache.Key.of(ns, "k", arr);
        arr[0] = "MUTATED";

        assertEquals(List.of("x", "y"), key.pars(), "Key must not reflect caller array mutation");
    }

    @Test
    void keyFactory_shouldNormalizeNullParsToEmptyList() {
        Object ns = new Object();

        Cache.Key<String> key = Cache.Key.of(ns, "k", (String[]) null);

        assertNotNull(key.pars());
        assertTrue(key.pars().isEmpty());
    }

    @Test
    void get_shouldRejectNullNamespace() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> Cache.get(null, "name", pars -> "x")
        );
        assertEquals("namespace", ex.getMessage());
    }

    @Test
    void get_shouldRejectNullName() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> Cache.get(new Object(), null, pars -> "x")
        );
        assertEquals("name", ex.getMessage());
    }

    @Test
    void get_shouldRejectBlankName() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> Cache.get(new Object(), "   ", pars -> "x")
        );
        assertTrue(ex.getMessage().contains("blank"));
    }

    @Test
    void get_shouldRejectNullBuilder() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> Cache.get(new Object(), "name", null)
        );
        assertEquals("builder", ex.getMessage());
    }

    @Test
    void get_shouldRejectNullBuilderResult_andNotCacheFailure() {
        Object ns = new Object();
        AtomicInteger calls = new AtomicInteger();

        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> Cache.get(ns, "nullResult", pars -> {
                    calls.incrementAndGet();
                    return null;
                })
        );

        assertTrue(ex.getMessage().contains("builder returned null"));
        assertEquals(1, calls.get());
        assertEquals(0, Cache.size(), "Null result failure must not be cached");

        // Retry should execute builder again (failure was removed).
        String ok = Cache.get(ns, "nullResult", pars -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", ok);
        assertEquals(2, calls.get());
        assertEquals(1, Cache.size());
    }

    @Test
    void get_shouldNotCacheFailures_retryShouldRecompute() {
        Object ns = new Object();
        AtomicInteger calls = new AtomicInteger();

        IllegalStateException ex1 = assertThrows(
                IllegalStateException.class,
                () -> Cache.get(ns, "fragile", pars -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) throw new IllegalStateException("boom");
                    return "ok";
                })
        );
        assertEquals("boom", ex1.getMessage());
        assertEquals(0, Cache.size(), "Failed computation must be removed");

        String v = Cache.get(ns, "fragile", pars -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new IllegalStateException("boom");
            return "ok";
        });

        assertEquals("ok", v);
        assertEquals(2, calls.get(), "Second call must recompute after failure");
        assertEquals(1, Cache.size());
    }

    @Test
    void invalidate_shouldRemoveOnlyTargetEntry() {
        Object ns = new Object();

        Cache.get(ns, "a", pars -> "A");
        Cache.get(ns, "b", pars -> "B");
        Cache.get(ns, "c", pars -> "C", "p1");

        assertEquals(3, Cache.size());

        Cache.invalidate(ns, "b");
        assertEquals(2, Cache.size());

        // Recompute only invalidated entry
        AtomicInteger calls = new AtomicInteger();
        String b = Cache.get(ns, "b", pars -> {
            calls.incrementAndGet();
            return "B2";
        });

        assertEquals("B2", b);
        assertEquals(1, calls.get());
        assertEquals(3, Cache.size());
    }

    @Test
    void clear_shouldRemoveAllEntries() {
        Object ns1 = new Object();
        Object ns2 = new Object();

        Cache.get(ns1, "a", pars -> "A");
        Cache.get(ns2, "b", pars -> "B", "x");

        assertEquals(2, Cache.size());

        Cache.clear();

        assertEquals(0, Cache.size());
    }

    @Test
    void get_shouldComputeOnceUnderConcurrency_forSameKey() throws Exception {
        final Object ns = new Object();
        final int threads = 16;
        final AtomicInteger builderCalls = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    start.await();
                    return Cache.get(ns, "concurrent", pars -> {
                        builderCalls.incrementAndGet();
                        try {
                            // Keep the builder busy long enough so other threads contend on same key.
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return "VALUE";
                    }, "p");
                });
            }

            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) {
                futures.add(pool.submit(task));
            }

            start.countDown();

            for (Future<String> f : futures) {
                assertEquals("VALUE", f.get(2, TimeUnit.SECONDS));
            }

            assertEquals(1, builderCalls.get(), "Only one builder invocation expected");
            assertEquals(1, Cache.size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    /**
     * Two distinct instances are equal by value, to verify Cache uses namespace identity (==).
     */
    private static final class EqNamespace {
        private final String id;

        private EqNamespace(String id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EqNamespace other)) return false;
            return Objects.equals(this.id, other.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }
}
