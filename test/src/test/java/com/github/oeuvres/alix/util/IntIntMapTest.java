package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link IntIntMap}.
 */
class IntIntMapTest
{
    /**
     * Tests that {@link IntIntMap#addTo(int, int)} inserts an absent key and returns the inserted
     * value.
     */
    @Test
    void addToInsertsAbsentKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertEquals(5, map.addTo(42, 5));
        assertEquals(5, map.get(42));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#addTo(int, int)} accepts negative deltas when the resulting value
     * is not the missing-value sentinel.
     */
    @Test
    void addToPermitsNegativeDelta()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.put(42, 10);
        
        assertEquals(7, map.addTo(42, -3));
        assertEquals(7, map.get(42));
    }
    
    /**
     * Tests that {@link IntIntMap#addTo(int, int)} rejects a resulting value equal to the
     * configured missing-value sentinel.
     */
    @Test
    void addToRejectsMissingValueResult()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.put(42, 1);
        
        assertThrows(IllegalArgumentException.class, () -> map.addTo(42, -2));
        assertEquals(1, map.get(42));
    }
    
    /**
     * Tests that {@link IntIntMap#addTo(int, int)} updates an existing key.
     */
    @Test
    void addToUpdatesExistingKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.put(42, 5);
        
        assertEquals(12, map.addTo(42, 7));
        assertEquals(12, map.get(42));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#addTo(int, int)} handles key {@code 0}.
     */
    @Test
    void addToUpdatesZeroKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertEquals(3, map.addTo(0, 3));
        assertEquals(8, map.addTo(0, 5));
        assertEquals(8, map.get(0));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#clear()} removes all mappings while retaining map usability.
     */
    @Test
    void clearRemovesAllMappings()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.put(0, 10);
        map.put(1, 20);
        map.put(2, 30);
        map.clear();
        
        assertTrue(map.isEmpty());
        assertEquals(-1, map.get(0));
        assertEquals(-1, map.get(1));
        assertEquals(-1, map.get(2));
        
        map.put(3, 40);
        
        assertEquals(40, map.get(3));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#containsKey(int)} reports presence and absence for regular keys
     * and key {@code 0}.
     */
    @Test
    void containsKeyReportsPresence()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertFalse(map.containsKey(0));
        assertFalse(map.containsKey(42));
        
        map.put(0, 7);
        map.put(42, 9);
        
        assertTrue(map.containsKey(0));
        assertTrue(map.containsKey(42));
        assertFalse(map.containsKey(43));
    }
    
    /**
     * Tests constructor validation for expected size.
     */
    @Test
    void constructorRejectsNegativeExpectedSize()
    {
        assertThrows(IllegalArgumentException.class, () -> new IntIntMap(-1));
    }
    
    /**
     * Tests constructor validation for load factor.
     */
    @Test
    void constructorRejectsInvalidLoadFactor()
    {
        assertThrows(IllegalArgumentException.class, () -> new IntIntMap(4, 0.0f, -1));
        assertThrows(IllegalArgumentException.class, () -> new IntIntMap(4, 1.0f, -1));
        assertThrows(IllegalArgumentException.class, () -> new IntIntMap(4, Float.NaN, -1));
    }
    
    /**
     * Tests that the default missing value is {@code -1}.
     */
    @Test
    void defaultMissingValueIsMinusOne()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertEquals(-1, map.missingValue());
        assertEquals(-1, map.get(123));
    }
    
    /**
     * Tests that {@link IntIntMap#ensureCapacity(int)} pre-grows the table so that subsequent
     * insertions fit without rehashing.
     */
    @Test
    void ensureCapacityPreGrowsTable()
    {
        final IntIntMap map = new IntIntMap(2);
        
        map.ensureCapacity(1_000);
        
        for (int key = 1; key <= 1_000; key++) {
            map.put(key, key * 3);
        }
        
        assertEquals(1_000, map.size());
        for (int key = 1; key <= 1_000; key++) {
            assertEquals(key * 3, map.get(key));
        }
    }
    
    /**
     * Tests that {@link IntIntMap#ensureCapacity(int)} rejects negative requests.
     */
    @Test
    void ensureCapacityRejectsNegative()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertThrows(IllegalArgumentException.class, () -> map.ensureCapacity(-1));
    }
    
    /**
     * Tests that {@link IntIntMap#ensureCapacity(int)} is a no-op for requests below the current
     * threshold.
     */
    @Test
    void ensureCapacityIsNoOpBelowThreshold()
    {
        final IntIntMap map = new IntIntMap(1_000);
        
        map.put(1, 10);
        map.put(2, 20);
        
        assertDoesNotThrow(() -> map.ensureCapacity(4));
        
        assertEquals(2, map.size());
        assertEquals(10, map.get(1));
        assertEquals(20, map.get(2));
    }
    
    /**
     * Tests that a map sized for the expected number of entries accepts that number of entries
     * without observable failure.
     */
    @Test
    void expectedSizeAcceptsExpectedNumberOfEntries()
    {
        final int expected = 1_000;
        final IntIntMap map = new IntIntMap(expected);
        
        assertDoesNotThrow(() -> {
            for (int key = 1; key <= expected; key++) {
                map.put(key, key);
            }
        });
        
        assertEquals(expected, map.size());
        
        for (int key = 1; key <= expected; key++) {
            assertEquals(key, map.get(key));
        }
    }
    
    /**
     * Tests that {@link IntIntMap#forEach(IntIntMap.IntIntConsumer)} visits every entry exactly
     * once, including the zero key.
     */
    @Test
    void forEachVisitsEveryEntry()
    {
        final IntIntMap map = new IntIntMap(8);
        
        map.put(0, 100);
        map.put(1, 101);
        map.put(2, 102);
        map.put(-3, -103);
        
        final Map<Integer, Integer> seen = new HashMap<>();
        map.forEach((k, v) -> {
            final Integer previous = seen.put(k, v);
            assertEquals(null, previous, "key visited twice: " + k);
        });
        
        assertEquals(4, seen.size());
        assertEquals(100, seen.get(0));
        assertEquals(101, seen.get(1));
        assertEquals(102, seen.get(2));
        assertEquals(-103, seen.get(-3));
    }
    
    /**
     * Tests that {@link IntIntMap#forEach(IntIntMap.IntIntConsumer)} on an empty map does not
     * invoke the consumer.
     */
    @Test
    void forEachOnEmptyMapDoesNothing()
    {
        final IntIntMap map = new IntIntMap(4);
        final int[] callCount = { 0 };
        
        map.forEach((k, v) -> callCount[0]++);
        
        assertEquals(0, callCount[0]);
    }
    
    /**
     * Tests that {@link IntIntMap#forEach(IntIntMap.IntIntConsumer)} rejects a {@code null}
     * consumer.
     */
    @Test
    void forEachRejectsNullConsumer()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertThrows(NullPointerException.class, () -> map.forEach(null));
    }
    
    /**
     * Tests that {@link IntIntMap#get(int)} returns the missing-value sentinel for absent keys.
     */
    @Test
    void getReturnsMissingValueForAbsentKey()
    {
        final IntIntMap map = new IntIntMap(4, IntIntMap.DEFAULT_LOAD_FACTOR, -99);
        
        assertEquals(-99, map.get(42));
        assertEquals(-99, map.get(0));
    }
    
    /**
     * Tests the single-probe lookup-then-insert pattern enabled by {@link IntIntMap#indexOf(int)} /
     * {@link IntIntMap#indexInsert(int, int, int)}.
     */
    @Test
    void indexOfInsertWhenAbsent()
    {
        final IntIntMap map = new IntIntMap(4);
        
        final int idx = map.indexOf(42);
        
        assertFalse(map.indexExists(idx));
        map.indexInsert(idx, 42, 7);
        
        assertEquals(7, map.get(42));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests the single-probe lookup-then-replace pattern enabled by {@link IntIntMap#indexOf(int)}
     * / {@link IntIntMap#indexReplace(int, int)}.
     */
    @Test
    void indexOfReplaceWhenPresent()
    {
        final IntIntMap map = new IntIntMap(4);
        map.put(42, 7);
        
        final int idx = map.indexOf(42);
        
        assertTrue(map.indexExists(idx));
        assertEquals(7, map.indexGet(idx));
        
        final int prev = map.indexReplace(idx, 9);
        
        assertEquals(7, prev);
        assertEquals(9, map.get(42));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that the index API handles key {@code 0} symmetrically with regular keys.
     */
    @Test
    void indexOfHandlesZeroKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        final int absentIdx = map.indexOf(0);
        assertFalse(map.indexExists(absentIdx));
        
        map.indexInsert(absentIdx, 0, 11);
        assertEquals(11, map.get(0));
        
        final int presentIdx = map.indexOf(0);
        assertTrue(map.indexExists(presentIdx));
        assertEquals(11, map.indexGet(presentIdx));
        
        final int prev = map.indexReplace(presentIdx, 13);
        assertEquals(11, prev);
        assertEquals(13, map.get(0));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#indexInsert(int, int, int)} rejects a positive index (which
     * denotes an existing key).
     */
    @Test
    void indexInsertRejectsPositiveIndex()
    {
        final IntIntMap map = new IntIntMap(4);
        map.put(42, 7);
        
        final int idx = map.indexOf(42);
        
        assertTrue(map.indexExists(idx));
        assertThrows(IllegalArgumentException.class, () -> map.indexInsert(idx, 42, 9));
        assertEquals(7, map.get(42));
    }
    
    /**
     * Tests that {@link IntIntMap#indexInsert(int, int, int)} rejects values equal to the
     * missing-value sentinel.
     */
    @Test
    void indexInsertRejectsMissingValue()
    {
        final IntIntMap map = new IntIntMap(4);
        
        final int idx = map.indexOf(42);
        
        assertThrows(IllegalArgumentException.class, () -> map.indexInsert(idx, 42, -1));
        assertFalse(map.containsKey(42));
    }
    
    /**
     * Tests that {@link IntIntMap#indexGet(int)} rejects a negative index.
     */
    @Test
    void indexGetRejectsAbsentIndex()
    {
        final IntIntMap map = new IntIntMap(4);
        
        final int idx = map.indexOf(42);
        
        assertFalse(map.indexExists(idx));
        assertThrows(IllegalArgumentException.class, () -> map.indexGet(idx));
    }
    
    /**
     * Tests that {@link IntIntMap#indexReplace(int, int)} rejects a negative index and rejects the
     * missing-value sentinel.
     */
    @Test
    void indexReplaceRejectsAbsentIndexOrMissingValue()
    {
        final IntIntMap map = new IntIntMap(4);
        
        final int absentIdx = map.indexOf(42);
        assertThrows(IllegalArgumentException.class, () -> map.indexReplace(absentIdx, 5));
        
        map.put(42, 7);
        final int presentIdx = map.indexOf(42);
        assertThrows(IllegalArgumentException.class, () -> map.indexReplace(presentIdx, -1));
        assertEquals(7, map.get(42));
    }
    
    /**
     * Tests that {@link IntIntMap#isEmpty()} tracks whether mappings exist.
     */
    @Test
    void isEmptyTracksMappings()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertTrue(map.isEmpty());
        
        map.put(42, 7);
        
        assertFalse(map.isEmpty());
        
        map.clear();
        
        assertTrue(map.isEmpty());
    }
    
    /**
     * Tests that {@link IntIntMap#missingValue()} returns the configured sentinel.
     */
    @Test
    void missingValueReturnsConfiguredSentinel()
    {
        final IntIntMap map = new IntIntMap(4, IntIntMap.DEFAULT_LOAD_FACTOR, Integer.MIN_VALUE);
        
        assertEquals(Integer.MIN_VALUE, map.missingValue());
        assertEquals(Integer.MIN_VALUE, map.get(42));
    }
    
    /**
     * Tests that {@link IntIntMap#mix32(int)} is deterministic and disperses simple neighbouring
     * inputs.
     */
    @Test
    void mix32IsDeterministic()
    {
        assertEquals(IntIntMap.mix32(123456), IntIntMap.mix32(123456));
        assertNotEquals(IntIntMap.mix32(123456), IntIntMap.mix32(123457));
    }
    
    /**
     * Tests that {@link IntIntMap#mixPhi(int)} is deterministic and disperses simple neighbouring
     * inputs.
     */
    @Test
    void mixPhiIsDeterministic()
    {
        assertEquals(IntIntMap.mixPhi(123456), IntIntMap.mixPhi(123456));
        assertNotEquals(IntIntMap.mixPhi(123456), IntIntMap.mixPhi(123457));
    }
    
    /**
     * Tests that {@link IntIntMap#put(int, int)} inserts and replaces regular keys.
     */
    @Test
    void putInsertsAndReplacesRegularKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertEquals(-1, map.put(42, 10));
        assertEquals(10, map.put(42, 20));
        assertEquals(20, map.get(42));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#put(int, int)} handles negative keys and negative values distinct
     * from the missing-value sentinel.
     */
    @Test
    void putHandlesNegativeKeysAndValues()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.put(-42, -2);
        
        assertTrue(map.containsKey(-42));
        assertEquals(-2, map.get(-42));
    }
    
    /**
     * Tests that {@link IntIntMap#put(int, int)} handles key {@code 0}.
     */
    @Test
    void putHandlesZeroKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertEquals(-1, map.put(0, 10));
        assertEquals(10, map.put(0, 20));
        assertEquals(20, map.get(0));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#put(int, int)} rejects the missing-value sentinel and leaves the
     * previous mapping unchanged.
     */
    @Test
    void putRejectsMissingValue()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.put(42, 10);
        
        assertThrows(IllegalArgumentException.class, () -> map.put(42, -1));
        assertEquals(10, map.get(42));
    }
    
    /**
     * Tests that {@link IntIntMap#putIfAbsent(int, int)} inserts an absent key.
     */
    @Test
    void putIfAbsentInsertsAbsentKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertEquals(-1, map.putIfAbsent(42, 10));
        assertEquals(10, map.get(42));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#putIfAbsent(int, int)} does not replace an existing key.
     */
    @Test
    void putIfAbsentKeepsExistingKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.put(42, 10);
        
        assertEquals(10, map.putIfAbsent(42, 20));
        assertEquals(10, map.get(42));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#putIfAbsent(int, int)} handles key {@code 0}.
     */
    @Test
    void putIfAbsentHandlesZeroKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertEquals(-1, map.putIfAbsent(0, 10));
        assertEquals(10, map.putIfAbsent(0, 20));
        assertEquals(10, map.get(0));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#putIfAbsent(int, int)} rejects the missing-value sentinel.
     */
    @Test
    void putIfAbsentRejectsMissingValue()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertThrows(IllegalArgumentException.class, () -> map.putIfAbsent(42, -1));
        assertFalse(map.containsKey(42));
    }
    
    /**
     * Tests that {@link IntIntMap#putNew(int, int)} inserts a new key.
     */
    @Test
    void putNewInsertsNewKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.putNew(42, 10);
        
        assertEquals(10, map.get(42));
        assertEquals(1, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#putNew(int, int)} rejects duplicate regular keys.
     */
    @Test
    void putNewRejectsDuplicateRegularKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.putNew(42, 10);
        
        assertThrows(IllegalStateException.class, () -> map.putNew(42, 20));
        assertEquals(10, map.get(42));
    }
    
    /**
     * Tests that {@link IntIntMap#putNew(int, int)} rejects duplicate key {@code 0}.
     */
    @Test
    void putNewRejectsDuplicateZeroKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.putNew(0, 10);
        
        assertThrows(IllegalStateException.class, () -> map.putNew(0, 20));
        assertEquals(10, map.get(0));
    }
    
    /**
     * Tests that {@link IntIntMap#putNew(int, int)} rejects the missing-value sentinel.
     */
    @Test
    void putNewRejectsMissingValue()
    {
        final IntIntMap map = new IntIntMap(4);
        
        assertThrows(IllegalArgumentException.class, () -> map.putNew(42, -1));
        assertFalse(map.containsKey(42));
    }
    
    /**
     * Tests that many insertions survive rehashing.
     */
    @Test
    void rehashPreservesMappings()
    {
        final IntIntMap map = new IntIntMap(1);
        
        map.put(0, 1000);
        
        for (int key = 1; key <= 10_000; key++) {
            map.put(key, key * 2);
        }
        
        assertEquals(10_001, map.size());
        assertEquals(1000, map.get(0));
        
        for (int key = 1; key <= 10_000; key++) {
            assertEquals(key * 2, map.get(key));
        }
    }
    
    /**
     * Tests that repeated insertions and replacements keep the logical size stable for existing
     * keys.
     */
    @Test
    void replacementsDoNotIncreaseSize()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.put(1, 10);
        map.put(2, 20);
        map.put(1, 30);
        map.put(2, 40);
        
        assertEquals(2, map.size());
        assertEquals(30, map.get(1));
        assertEquals(40, map.get(2));
    }
    
    /**
     * Tests that {@link IntIntMap#size()} includes key {@code 0}.
     */
    @Test
    void sizeIncludesZeroKey()
    {
        final IntIntMap map = new IntIntMap(4);
        
        map.put(0, 10);
        map.put(1, 20);
        
        assertEquals(2, map.size());
    }
    
    /**
     * Tests that {@link IntIntMap#tableSize(int, float)} returns a power-of-two capacity large
     * enough for the requested load factor.
     */
    @Test
    void tableSizeReturnsPowerOfTwoCapacity()
    {
        final int capacity = IntIntMap.tableSize(100, 0.70f);
        
        assertTrue(capacity > 0);
        assertEquals(0, capacity & (capacity - 1));
        assertTrue(capacity * 0.70f >= 100.0f);
    }
    
    /**
     * Tests that {@link IntIntMap#tableSize(int, float)} rejects impossible capacities.
     */
    @Test
    void tableSizeRejectsTooLargeCapacity()
    {
        assertThrows(
                IllegalArgumentException.class,
                () -> IntIntMap.tableSize(Integer.MAX_VALUE, 0.70f));
    }
}