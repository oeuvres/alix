package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LongIntMapTest {

    @Test
    void emptyMapReturnsMissing() {
        LongIntMap m = new LongIntMap(16, LongIntMap.DEFAULT_LOAD_FACTOR, -1);
        assertEquals(-1, m.get(123L));
        assertFalse(m.containsKey(123L));
        assertEquals(0, m.size());
    }

    @Test
    void putAndGetPackedIntPair() {
        LongIntMap m = new LongIntMap(4, LongIntMap.DEFAULT_LOAD_FACTOR, -1);

        long k = LongIntMap.packIntPair(12, 34);
        assertEquals(-1, m.put(k, 777));
        assertEquals(777, m.get(k));
        assertTrue(m.containsKey(k));
        assertEquals(1, m.size());
    }

    @Test
    void putIfAbsentKeepsFirst() {
        LongIntMap m = new LongIntMap(4, LongIntMap.DEFAULT_LOAD_FACTOR, -1);

        long k = LongIntMap.packIntPair(1, 2);
        assertEquals(-1, m.putIfAbsent(k, 10));
        assertEquals(10, m.get(k));

        // should keep first
        assertEquals(10, m.putIfAbsent(k, 99));
        assertEquals(10, m.get(k));
        assertEquals(1, m.size());
    }

    @Test
    void supportsZeroKey() {
        LongIntMap m = new LongIntMap(4, LongIntMap.DEFAULT_LOAD_FACTOR, -1);

        long zero = 0L; // packIntPair(0,0) == 0
        assertEquals(-1, m.get(zero));
        assertEquals(-1, m.put(zero, 42));
        assertEquals(42, m.get(zero));
        assertTrue(m.containsKey(zero));
        assertEquals(1, m.size());

        // overwrite
        assertEquals(42, m.put(zero, 43));
        assertEquals(43, m.get(zero));
        assertEquals(1, m.size());
    }

    @Test
    void rehashPreservesEntries() {
        LongIntMap m = new LongIntMap(2, 0.60f, -1);

        final int n = 50_000;
        for (int i = 0; i < n; i++) {
            long k = LongIntMap.packIntPair(i, i ^ 0x5a5a5a5a);
            m.put(k, i);
        }
        assertEquals(n, m.size());

        for (int i = 0; i < n; i++) {
            long k = LongIntMap.packIntPair(i, i ^ 0x5a5a5a5a);
            assertEquals(i, m.get(k));
        }
        assertEquals(-1, m.get(LongIntMap.packIntPair(999_999, 1)));
    }

    @Test
    void forbidsStoringMissingValue() {
        LongIntMap m = new LongIntMap(4, LongIntMap.DEFAULT_LOAD_FACTOR, -1);
        long k = LongIntMap.packIntPair(1, 1);
        assertThrows(IllegalArgumentException.class, () -> m.put(k, -1));
        assertThrows(IllegalArgumentException.class, () -> m.putIfAbsent(k, -1));
    }
}
