package com.github.oeuvres.alix.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TopSlot}.
 *
 * <p>Uses a minimal mutable {@link Slot} class to exercise the populate-in-place
 * pattern: {@link TopSlot#insert(double)} returns a pre-allocated instance; the caller
 * sets its fields without allocating a new object.</p>
 */
class TopTest {

    /**
     * Minimal mutable value object.
     * Represents what a caller would populate after a successful {@link TopSlot#insert}.
     */
    static class Slot {
        String label = "";

        Slot set(final String label) {
            this.label = label;
            return this;
        }

        @Override
        public String toString() { return label; }
    }

    private TopSlot<Slot> top;

    @BeforeEach
    void setUp() {
        top = new TopSlot<>(() -> new Slot(), 3);
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void zeroSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new TopSlot<>(() -> new Slot(), 0));
    }

    @Test
    void negativeSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new TopSlot<>(() -> new Slot(), -1));
    }

    @Test
    void freshContainerIsEmpty() {
        assertEquals(0, top.length());
        assertTrue(Double.isNaN(top.min()));
        assertTrue(Double.isNaN(top.max()));
    }

    @Test
    void supplierCalledExactlySizeTimes() {
        final int[] count = {0};
        new TopSlot<>(() -> { count[0]++; return new Slot(); }, 5);
        assertEquals(5, count[0]);
    }

    // -------------------------------------------------------------------------
    // Fill phase (container not yet full)
    // -------------------------------------------------------------------------

    @Test
    void insertReturnsSamePreallocatedInstance() {
        final Slot slot = top.insert(1.0);
        assertNotNull(slot);
        // The returned slot must be one of the pre-allocated instances —
        // confirm it appears in the iteration.
        slot.set("a");
        final List<String> labels = labels(top);
        assertTrue(labels.contains("a"));
    }

    @Test
    void fillPhaseAllInsertsAccepted() {
        assertNotNull(top.insert(1.0));
        assertNotNull(top.insert(2.0));
        assertNotNull(top.insert(3.0));
        assertEquals(3, top.length());
    }

    @Test
    void nanRejectedDuringFill() {
        assertNull(top.insert(Double.NaN));
        assertEquals(0, top.length());
    }

    // -------------------------------------------------------------------------
    // Full phase (container at capacity)
    // -------------------------------------------------------------------------

    @Test
    void insertBeyondCapacityWithBetterScore() {
        insert(top, 1.0, "a");
        insert(top, 2.0, "b");
        insert(top, 3.0, "c");          // now full, min = 1.0
        final Slot slot = insert(top, 4.0, "d"); // evicts "a"
        assertNotNull(slot);
        assertEquals(3, top.length());
        assertFalse(labels(top).contains("a"));
        assertTrue(labels(top).contains("d"));
    }

    @Test
    void insertWithScoreBelowMinRejected() {
        insert(top, 2.0, "a");
        insert(top, 3.0, "b");
        insert(top, 4.0, "c");          // full, min = 2.0
        assertNull(top.insert(1.0));    // below min — rejected
        assertEquals(3, top.length());
    }

    @Test
    void insertWithScoreEqualToMinRejected() {
        insert(top, 2.0, "a");
        insert(top, 3.0, "b");
        insert(top, 4.0, "c");          // full, min = 2.0
        assertNull(top.insert(2.0));    // equal to min — first-inserted wins
        assertEquals(3, top.length());
        assertTrue(labels(top).contains("a"));
    }

    @Test
    void nanRejectedWhenFull() {
        insert(top, 1.0, "a");
        insert(top, 2.0, "b");
        insert(top, 3.0, "c");
        assertNull(top.insert(Double.NaN));
        assertEquals(3, top.length());
    }

    // -------------------------------------------------------------------------
    // Iteration order: descending score
    // -------------------------------------------------------------------------

    @Test
    void iterationIsDescendingByScore() {
        insert(top, 1.0, "low");
        insert(top, 3.0, "high");
        insert(top, 2.0, "mid");
        final List<Double> scores = scores(top);
        assertEquals(List.of(3.0, 2.0, 1.0), scores);
    }

    @Test
    void iterationLabelsMatchScores() {
        insert(top, 1.0, "low");
        insert(top, 3.0, "high");
        insert(top, 2.0, "mid");
        final List<String> labs = labels(top);
        assertEquals(List.of("high", "mid", "low"), labs);
    }

    @Test
    void iterationAfterEvictionCorrect() {
        insert(top, 1.0, "a");
        insert(top, 2.0, "b");
        insert(top, 3.0, "c");
        insert(top, 4.0, "d"); // evicts "a"
        final List<String> labs = labels(top);
        assertEquals(List.of("d", "c", "b"), labs);
    }

    // -------------------------------------------------------------------------
    // Populate-in-place — returned slot is the same object in iteration
    // -------------------------------------------------------------------------

    @Test
    void returnedSlotIsIdenticalToIteratedEntry() {
        final Slot returned = top.insert(5.0);
        assertNotNull(returned);
        returned.set("sentinel");
        // The entry visible via iteration must be the same object reference.
        boolean found = false;
        for (TopSlot.Entry<Slot> e : top) {
            if (e.value() == returned) { found = true; }
        }
        assertTrue(found, "Returned slot must appear as the same object in iteration");
    }

    @Test
    void mutatingSlotAfterInsertIsVisibleInIteration() {
        final Slot slot = top.insert(7.0);
        assertNotNull(slot);
        slot.set("before");
        // Mutate after insert but before iteration — the new value must be visible.
        slot.set("after");
        assertEquals("after", top.iterator().next().value().label);
    }

    // -------------------------------------------------------------------------
    // min() and max()
    // -------------------------------------------------------------------------

    @Test
    void minIsLowestKeptScore() {
        insert(top, 3.0, "a");
        insert(top, 1.0, "b");
        insert(top, 2.0, "c");
        assertEquals(1.0, top.min());
    }

    @Test
    void maxIsHighestKeptScore() {
        insert(top, 3.0, "a");
        insert(top, 1.0, "b");
        insert(top, 2.0, "c");
        assertEquals(3.0, top.max());
    }

    @Test
    void minUpdatesAfterEviction() {
        insert(top, 1.0, "a");
        insert(top, 2.0, "b");
        insert(top, 3.0, "c");
        insert(top, 4.0, "d"); // evicts 1.0
        assertEquals(2.0, top.min());
    }

    @Test
    void minNaNWhenEmpty() {
        assertTrue(Double.isNaN(top.min()));
    }

    @Test
    void maxNaNWhenEmpty() {
        assertTrue(Double.isNaN(top.max()));
    }

    // -------------------------------------------------------------------------
    // isInsertable
    // -------------------------------------------------------------------------

    @Test
    void isInsertableTrueWhenNotFull() {
        top.insert(1.0);
        assertTrue(top.isInsertable(0.0));
    }

    @Test
    void isInsertableFalseForNaN() {
        assertFalse(top.isInsertable(Double.NaN));
    }

    @Test
    void isInsertableFalseWhenFullAndScoreTooLow() {
        insert(top, 2.0, "a");
        insert(top, 3.0, "b");
        insert(top, 4.0, "c");
        assertFalse(top.isInsertable(1.0));
    }

    @Test
    void isInsertableFalseForEqualToMin() {
        insert(top, 2.0, "a");
        insert(top, 3.0, "b");
        insert(top, 4.0, "c");
        assertFalse(top.isInsertable(2.0));
    }

    @Test
    void isInsertableDoesNotModify() {
        insert(top, 2.0, "a");
        insert(top, 3.0, "b");
        insert(top, 4.0, "c");
        top.isInsertable(9.0);
        assertEquals(3, top.length());
        assertEquals(4.0, top.max());
    }

    // -------------------------------------------------------------------------
    // clear and reuse
    // -------------------------------------------------------------------------

    @Test
    void clearResetsLength() {
        insert(top, 1.0, "a");
        insert(top, 2.0, "b");
        top.clear();
        assertEquals(0, top.length());
    }

    @Test
    void clearResetsMinMax() {
        insert(top, 1.0, "a");
        top.clear();
        assertTrue(Double.isNaN(top.min()));
        assertTrue(Double.isNaN(top.max()));
    }

    @Test
    void clearRetainsPreallocatedObjects() {
        final Slot first = top.insert(1.0);
        assertNotNull(first);
        first.set("old");
        top.clear();
        // After clear, inserting again returns one of the pre-allocated slots.
        final Slot reused = top.insert(2.0);
        assertNotNull(reused);
        // The slot object is reused — it is the same instance, now with a new label.
        reused.set("new");
        assertEquals("new", top.iterator().next().value().label);
    }

    @Test
    void clearThenReuseAcceptsLowerScoresThanBefore() {
        insert(top, 10.0, "a");
        insert(top, 20.0, "b");
        insert(top, 30.0, "c");
        top.clear();
        // After clear the min resets — low scores must be accepted again.
        assertNotNull(top.insert(1.0));
        assertNotNull(top.insert(2.0));
    }

    // -------------------------------------------------------------------------
    // Iterator contract
    // -------------------------------------------------------------------------

    @Test
    void emptyIteratorHasNoNext() {
        assertFalse(top.iterator().hasNext());
    }

    @Test
    void iteratorNextBeyondEndThrows() {
        insert(top, 1.0, "a");
        final Iterator<TopSlot.Entry<Slot>> it = top.iterator();
        it.next();
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void iteratorCountMatchesLength() {
        insert(top, 1.0, "a");
        insert(top, 2.0, "b");
        assertEquals(2, top.length());
        assertEquals(2, labels(top).size());
    }

    // -------------------------------------------------------------------------
    // Large input correctness
    // -------------------------------------------------------------------------

    @Test
    void top3OfHundredScores() {
        final TopSlot<Slot> big = new TopSlot<>(() -> new Slot(), 3);
        for (int i = 0; i < 100; i++) {
            final Slot s = big.insert(i);
            if (s != null) s.set("s" + i);
        }
        final List<Double> scores = scores(big);
        assertEquals(List.of(99.0, 98.0, 97.0), scores);
        final List<String> labs = labels(big);
        assertEquals(List.of("s99", "s98", "s97"), labs);
    }

    @Test
    void top1OfManyRetainsBestScore() {
        final TopSlot<Slot> single = new TopSlot<>(() -> new Slot(), 1);
        for (int i = 100; i >= 0; i--) {
            final Slot s = single.insert(i);
            if (s != null) s.set("s" + i);
        }
        assertEquals(1, single.length());
        assertEquals(100.0, single.iterator().next().score());
        assertEquals("s100", single.iterator().next().value().label);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Inserts a score and populates the returned slot with a label; returns the slot. */
    private static Slot insert(final TopSlot<Slot> top, final double score, final String label) {
        final Slot slot = top.insert(score);
        if (slot != null) slot.set(label);
        return slot;
    }

    /** Collects all labels in iteration order. */
    private static List<String> labels(final TopSlot<Slot> top) {
        final List<String> result = new ArrayList<>();
        for (TopSlot.Entry<Slot> e : top) result.add(e.value().label);
        return result;
    }

    /** Collects all scores in iteration order. */
    private static List<Double> scores(final TopSlot<Slot> top) {
        final List<Double> result = new ArrayList<>();
        for (TopSlot.Entry<Slot> e : top) result.add(e.score());
        return result;
    }
}
