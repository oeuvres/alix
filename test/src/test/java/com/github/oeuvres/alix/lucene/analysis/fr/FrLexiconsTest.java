package com.github.oeuvres.alix.lucene.analysis.fr;

import org.apache.lucene.analysis.CharArrayMap;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class FrLexiconsTest {

    private static String value(CharArrayMap<char[]> map, String key) {
        char[] v = map.get(key.toCharArray());
        return (v == null) ? null : new String(v);
    }

    private static Path writeTsvOverlay(String from, String to) throws Exception {
        Path f = Files.createTempFile("frlexicons-", ".tsv");
        // header required
        Files.writeString(f, "FROM,TO\n" + from + "," + to + "\n", StandardCharsets.UTF_8);
        f.toFile().deleteOnExit();
        return f;
    }

    @Test
    void baseResourceLoads_nonEmpty() {
        CharArrayMap<char[]> base = FrLexicons.getTermMapping();
        assertNotNull(base);
        assertTrue(base.size() > 0, "Expected norm.tsv  to load at least one entry");
    }
    
    @Test
    void defaultNormalizerIsLoaded() {
        CharArrayMap<char[]> map = FrLexicons.getTermMapping();
        String k = "boeuf";
        String v = "bœuf";
        assertEquals(v, value(map, k), "Default norm.tsv resources not loaded");
    }

    @Test
    void overlayIsLoaded_injectedPairVisible() throws Exception {
        String k = "__frlexicons_test_key__";
        String v = "__frlexicons_test_value__";

        Path overlay = writeTsvOverlay(k, v);

        CharArrayMap<char[]> map = FrLexicons.getTermMapping(overlay.toString());
        assertEquals(v, value(map, k), "Overlay TSV pair should be visible in the normalizer map");
    }

    @Test
    void overlayOverridesBaseEntry() throws Exception {
        // Get a base map and pick any existing key to override.
        CharArrayMap<char[]> base = FrLexicons.getTermMapping();
        assertTrue(base.size() > 0, "Base map must not be empty for this test");

        Map.Entry<Object, char[]> any = null;
        for (var e : base.entrySet()) {
            any = e;
            break;
        }
        assertNotNull(any);

        String existingKey = new String((char[]) any.getKey());
        String original = value(base, existingKey);
        assertNotNull(original, "Picked key should be retrievable via String lookup");

        String overridden = "__override__";
        Path overlay = writeTsvOverlay(existingKey, overridden);

        CharArrayMap<char[]> map2 = FrLexicons.getTermMapping(overlay.toString());
        assertEquals(overridden, value(map2, existingKey),
                "Overlay should override the base mapping for the same key");
    }

    @Test
    void cacheReturnsSameInstanceForSameParams() throws Exception {
        Path overlay = writeTsvOverlay("__k__", "__v__");

        CharArrayMap<char[]> a = FrLexicons.getTermMapping(overlay.toString());
        CharArrayMap<char[]> b = FrLexicons.getTermMapping(overlay.toString());

        assertSame(a, b, "Same parameters should hit cache and return identical instance");
    }

    @Test
    void concurrentCallsWaitAndReturnSameInstance() throws Exception {
        Path overlay = writeTsvOverlay("__k2__", "__v2__");

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<CharArrayMap<char[]>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return FrLexicons.getTermMapping(overlay.toString());
            }));
        }

        start.countDown();

        CharArrayMap<char[]> first = futures.get(0).get(10, TimeUnit.SECONDS);
        for (int i = 1; i < futures.size(); i++) {
            CharArrayMap<char[]> other = futures.get(i).get(10, TimeUnit.SECONDS);
            assertSame(first, other, "All threads should observe the same cached instance");
        }

        pool.shutdownNow();
    }

    @Test
    void missingOverlayFileFailsAndDoesNotPoisonCache() throws Exception {
        // This assumes your FrLexicons wraps IOException into UncheckedIOException.
        // If you wrap differently, adjust the expected exception.
        String missing = Path.of("does-not-exist-" + System.nanoTime() + ".tsv").toString();

        assertThrows(RuntimeException.class, () -> FrLexicons.getTermMapping(missing));

        // Now create a real overlay for the same (string) parameter to verify retry works.
        // This requires using the exact same parameter string; easiest is to reuse `missing` as a real file.
        // We can't create a file with that name portably across directories, so we just ensure
        // "a failing key does not prevent other keys from working":
        Path ok = writeTsvOverlay("__k3__", "__v3__");
        assertDoesNotThrow(() -> FrLexicons.getTermMapping(ok.toString()));
    }
}

