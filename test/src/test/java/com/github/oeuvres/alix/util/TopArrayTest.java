package com.github.oeuvres.alix.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TopArray}.
 *
 * <p>Each method covers one documented behaviour. Tests are grouped by concern:
 * construction, basic push, capacity eviction, tie-breaking, score filters,
 * reverse mode, bulk push, min/max tracking, sort, iteration, and clear/reuse.</p>
 */
class TopArrayTest {

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void zeroCapacityAllowed() {
        final TopArray top = new TopArray(0);
        assertEquals(0, top.capacity());
        assertEquals(0, top.size());
        assertTrue(top.isEmpty());
    }

    @Test
    void negativeCapacityThrows() {
        assertThrows(IllegalArgumentException.class, () -> new TopArray(-1));
    }

    @Test
    void freshInstanceIsEmpty() {
        final TopArray top = new TopArray(5);
        assertTrue(top.isEmpty());
        assertEquals(0, top.size());
        assertFalse(top.isFull());
    }

    // -------------------------------------------------------------------------
    // Basic push and ranking
    // -------------------------------------------------------------------------

    @Test
    void singlePushRecorded() {
        final TopArray top = new TopArray(3);
        top.push(7, 1.5);
        assertEquals(1, top.size());
        assertEquals(7, top.id(0));
        assertEquals(1.5, top.score(0));
    }

    @Test
    void rankingIsDescendingByScore() {
        final TopArray top = new TopArray(3);
        top.push(0, 1.0).push(1, 3.0).push(2, 2.0);
        assertEquals(3, top.size());
        assertEquals(1, top.id(0));   // score 3.0
        assertEquals(2, top.id(1));   // score 2.0
        assertEquals(0, top.id(2));   // score 1.0
    }

    @Test
    void pushBeyondCapacityKeepsTopK() {
        final TopArray top = new TopArray(2);
        top.push(0, 1.0).push(1, 3.0).push(2, 2.0).push(3, 0.5);
        assertEquals(2, top.size());
        assertTrue(top.isFull());
        assertEquals(1, top.id(0));   // score 3.0
        assertEquals(2, top.id(1));   // score 2.0
    }

    @Test
    void pushOnFullWithLowerScoreIgnored() {
        final TopArray top = new TopArray(2);
        top.push(0, 5.0).push(1, 4.0);
        top.push(2, 3.0); // below current minimum 4.0 — ignored
        assertEquals(2, top.size());
        assertEquals(0, top.id(0));
        assertEquals(1, top.id(1));
    }

    @Test
    void zeroCapacityPushIgnored() {
        final TopArray top = new TopArray(0);
        top.push(0, 1.0);
        assertEquals(0, top.size());
    }

    // -------------------------------------------------------------------------
    // Tie-breaking: lower id ranks first
    // -------------------------------------------------------------------------

    @Test
    void tieBreakLowerIdFirst() {
        final TopArray top = new TopArray(3);
        top.push(5, 1.0).push(2, 1.0).push(8, 1.0);
        assertEquals(2, top.id(0));
        assertEquals(5, top.id(1));
        assertEquals(8, top.id(2));
    }

    @Test
    void tieBreakOnEviction_higherIdEvicted() {
        final TopArray top = new TopArray(2);
        top.push(5, 1.0).push(9, 1.0); // both score 1.0; id 9 is worst
        top.push(3, 1.0);               // score 1.0, id 3 < id 9 → evicts id 9
        assertEquals(2, top.size());
        assertEquals(3, top.id(0));
        assertEquals(5, top.id(1));
    }

    @Test
    void sameIdSameScoreNotEvicted() {
        final TopArray top = new TopArray(2);
        top.push(0, 5.0).push(1, 4.0);
        top.push(1, 4.0); // exact duplicate of current worst — not better, ignored
        assertEquals(2, top.size());
    }

    // -------------------------------------------------------------------------
    // Score filters: NaN and NO_ZERO
    // -------------------------------------------------------------------------

    @Test
    void nanScoreAlwaysIgnored() {
        final TopArray top = new TopArray(3);
        top.push(0, Double.NaN);
        assertEquals(0, top.size());
    }

    @Test
    void zeroScoreAcceptedByDefault() {
        final TopArray top = new TopArray(3);
        top.push(0, 0.0);
        assertEquals(1, top.size());
    }

    @Test
    void zeroScoreIgnoredWithNoZeroFlag() {
        final TopArray top = new TopArray(3, TopArray.NO_ZERO);
        top.push(0, 0.0);
        assertEquals(0, top.size());
    }

    @Test
    void noZeroFlagDoesNotAffectNonZero() {
        final TopArray top = new TopArray(3, TopArray.NO_ZERO);
        top.push(0, 0.001);
        assertEquals(1, top.size());
    }

    @Test
    void negativeScoreAccepted() {
        final TopArray top = new TopArray(3);
        top.push(0, -1.0).push(1, -2.0);
        assertEquals(2, top.size());
        assertEquals(0, top.id(0)); // -1.0 > -2.0
    }

    // -------------------------------------------------------------------------
    // Reverse mode: keep lowest scores
    // -------------------------------------------------------------------------

    @Test
    void reverseModeKeepsLowestScores() {
        final TopArray top = new TopArray(2, TopArray.REVERSE);
        top.push(0, 1.0).push(1, 3.0).push(2, 2.0);
        assertEquals(2, top.size());
        assertEquals(0, top.id(0));  // score 1.0
        assertEquals(2, top.id(1)); // score 2.0
    }

    @Test
    void reverseModeHigherScoreEvicted() {
        final TopArray top = new TopArray(2, TopArray.REVERSE);
        top.push(0, 1.0).push(1, 2.0);
        top.push(2, 0.5); // 0.5 < max(2.0) → evicts id 1; kept: 0.5 and 1.0
        assertEquals(2, top.id(0)); // score 0.5 ranks first in reverse
        assertEquals(0, top.id(1)); // score 1.0
    }

    @Test
    void reverseTieBreakLowerIdFirst() {
        final TopArray top = new TopArray(2, TopArray.REVERSE);
        top.push(5, 1.0).push(3, 1.0);
        assertEquals(3, top.id(0));
        assertEquals(5, top.id(1));
    }

    // -------------------------------------------------------------------------
    // min() and max()
    // -------------------------------------------------------------------------

    @Test
    void minMaxNaNWhenEmpty() {
        final TopArray top = new TopArray(3);
        assertTrue(Double.isNaN(top.min()));
        assertTrue(Double.isNaN(top.max()));
    }

    @Test
    void minMaxCorrectAfterPush() {
        final TopArray top = new TopArray(5);
        top.push(0, 3.0).push(1, 1.0).push(2, 2.0);
        assertEquals(1.0, top.min());
        assertEquals(3.0, top.max());
    }

    @Test
    void minMaxCorrectAfterEviction() {
        final TopArray top = new TopArray(2);
        top.push(0, 1.0).push(1, 2.0); // min=1.0, max=2.0
        top.push(2, 3.0);               // evicts 1.0; min=2.0, max=3.0
        assertEquals(2.0, top.min());
        assertEquals(3.0, top.max());
    }

    @Test
    void minMaxCorrectWhenBestIsEvicted() {
        // Evicting the root when it equals best forces a rescan.
        final TopArray top = new TopArray(2);
        top.push(0, 3.0).push(1, 3.0); // both 3.0; best=3.0, root=worst=id1
        top.push(2, 4.0);               // evicts root (3.0/id1); best must rescan
        assertEquals(3.0, top.min());
        assertEquals(4.0, top.max());
    }

    // -------------------------------------------------------------------------
    // isInsertable
    // -------------------------------------------------------------------------

    @Test
    void isInsertableReturnsTrueWhenNotFull() {
        final TopArray top = new TopArray(3);
        top.push(0, 1.0);
        assertTrue(top.isInsertable(1, 0.5));
    }

    @Test
    void isInsertableReturnsFalseWhenScoreTooLow() {
        final TopArray top = new TopArray(2);
        top.push(0, 5.0).push(1, 4.0);
        assertFalse(top.isInsertable(2, 3.0));
    }

    @Test
    void isInsertableDoesNotModify() {
        final TopArray top = new TopArray(2);
        top.push(0, 5.0).push(1, 4.0);
        top.isInsertable(2, 6.0);
        assertEquals(2, top.size());
        assertEquals(0, top.id(0));
    }

    // -------------------------------------------------------------------------
    // Bulk push
    // -------------------------------------------------------------------------

    @Test
    void pushDoubleArrayUsesIndexAsId() {
        final TopArray top = new TopArray(2);
        top.push(new double[]{1.0, 3.0, 2.0});
        assertEquals(1, top.id(0)); // index 1, score 3.0
        assertEquals(2, top.id(1)); // index 2, score 2.0
    }

    @Test
    void pushIntArrayUsesIndexAsId() {
        final TopArray top = new TopArray(2);
        top.push(new int[]{10, 30, 20});
        assertEquals(1, top.id(0)); // index 1, score 30
        assertEquals(2, top.id(1)); // index 2, score 20
    }

    @Test
    void pushLongArrayUsesIndexAsId() {
        final TopArray top = new TopArray(2);
        top.push(new long[]{10L, 30L, 20L});
        assertEquals(1, top.id(0));
        assertEquals(2, top.id(1));
    }

    // -------------------------------------------------------------------------
    // toArray
    // -------------------------------------------------------------------------

    @Test
    void toArrayReturnsIdsInRankOrder() {
        final TopArray top = new TopArray(3);
        top.push(0, 1.0).push(1, 3.0).push(2, 2.0);
        assertArrayEquals(new int[]{1, 2, 0}, top.toArray());
    }

    @Test
    void toArrayLengthEqualsSize() {
        final TopArray top = new TopArray(5);
        top.push(0, 1.0).push(1, 2.0);
        assertEquals(2, top.toArray().length);
    }

    // -------------------------------------------------------------------------
    // id() and score() out-of-bounds
    // -------------------------------------------------------------------------

    @Test
    void idOutOfBoundsThrows() {
        final TopArray top = new TopArray(3);
        top.push(0, 1.0);
        assertThrows(IndexOutOfBoundsException.class, () -> top.id(1));
        assertThrows(IndexOutOfBoundsException.class, () -> top.id(-1));
    }

    @Test
    void scoreOutOfBoundsThrows() {
        final TopArray top = new TopArray(3);
        top.push(0, 1.0);
        assertThrows(IndexOutOfBoundsException.class, () -> top.score(1));
    }

    // -------------------------------------------------------------------------
    // Iterator — classical contract
    // -------------------------------------------------------------------------

    @Test
    void iteratorVisitsAllInRankOrder() {
        final TopArray top = new TopArray(3);
        top.push(0, 1.0).push(1, 3.0).push(2, 2.0);
        final List<Integer> ids = new ArrayList<>();
        for (TopArray.TopEntry entry : top) ids.add(entry.id());
        assertEquals(List.of(1, 2, 0), ids);
    }

    @Test
    void iteratorIdScoreIsRecord() {
        final TopArray top = new TopArray(2);
        top.push(7, 9.5);
        final TopArray.TopEntry entry = top.iterator().next();
        assertEquals(7,   entry.id());
        assertEquals(9.5, entry.score());
    }

    @Test
    void iteratorNextBeyondEndThrows() {
        final TopArray top = new TopArray(1);
        top.push(0, 1.0);
        final Iterator<TopArray.TopEntry> it = top.iterator();
        it.next();
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void iteratorOnEmptyNeverHasNext() {
        final TopArray top = new TopArray(3);
        assertFalse(top.iterator().hasNext());
    }

    @Test
    void consecutiveIteratorsAreIndependent() {
        final TopArray top = new TopArray(3);
        top.push(0, 1.0).push(1, 2.0);
        final Iterator<TopArray.TopEntry> it1 = top.iterator();
        final Iterator<TopArray.TopEntry> it2 = top.iterator();
        assertEquals(it1.next().id(), it2.next().id());
        it1.next();
        // it2 still at rank 1
        assertEquals(0, it2.next().id());
    }

    // -------------------------------------------------------------------------
    // clear and reuse
    // -------------------------------------------------------------------------

    @Test
    void clearResetsToEmpty() {
        final TopArray top = new TopArray(3);
        top.push(0, 1.0).push(1, 2.0);
        top.clear();
        assertTrue(top.isEmpty());
        assertEquals(0, top.size());
        assertTrue(Double.isNaN(top.min()));
        assertTrue(Double.isNaN(top.max()));
    }

    @Test
    void clearThenReusePushesCorrectly() {
        final TopArray top = new TopArray(2);
        top.push(0, 10.0).push(1, 9.0);
        top.clear();
        top.push(2, 1.0).push(3, 2.0);
        assertEquals(3, top.id(0)); // score 2.0
        assertEquals(2, top.id(1)); // score 1.0
    }

    // -------------------------------------------------------------------------
    // Large input — correctness of heap on many pushes
    // -------------------------------------------------------------------------

    @Test
    void top3OfHundredDescending() {
        final TopArray top = new TopArray(3);
        for (int i = 0; i < 100; i++) top.push(i, i);
        assertEquals(3, top.size());
        assertEquals(99, top.id(0));
        assertEquals(98, top.id(1));
        assertEquals(97, top.id(2));
    }

    @Test
    void top3OfHundredAscendingReverseMode() {
        final TopArray top = new TopArray(3, TopArray.REVERSE);
        for (int i = 0; i < 100; i++) top.push(i, i);
        assertEquals(3, top.size());
        assertEquals(0, top.id(0));
        assertEquals(1, top.id(1));
        assertEquals(2, top.id(2));
    }
}
