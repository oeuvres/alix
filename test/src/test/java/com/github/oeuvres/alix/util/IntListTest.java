package com.github.oeuvres.alix.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IntList}.
 *
 * <p>
 * Coverage scope:
 * </p>
 * <ul>
 *   <li>All public methods, with happy-path, growth-boundary and error-condition cases.</li>
 *   <li>Documented contracts of {@code toArray}, {@code uniq}, and the equals/hashCode pair.</li>
 *   <li>Three tests at the bottom document known bugs and will fail against the current
 *       implementation; they pass against the contract-correct version discussed in code review.</li>
 * </ul>
 *
 * <p>
 * Out of scope:
 * </p>
 * <ul>
 *   <li>The {@code short} loop variable in {@code equals(Object)} overflows for lists of
 *       32&nbsp;768+ elements; testing this would require an unreasonably large fixture.</li>
 *   <li>Concurrent access to {@code hashCode()} (cache flag and value are non-volatile);
 *       {@link IntList} is documented as single-threaded.</li>
 *   <li>Mutations performed through {@link IntList#data()} are explicitly "unsafe" and not
 *       tested for hash-cache invalidation.</li>
 * </ul>
 */
class IntListTest
{
    /**
     * Two adds at the same in-bounds position accumulate into a single cell without changing
     * size.
     */
    @Test
    void add_accumulatesAtExistingPosition()
    {
        IntList list = new IntList().push(0).push(0).push(0);
        list.add(1, 5);
        list.add(1, 3);
        assertEquals(8, list.get(1));
        assertEquals(3, list.size());
    }

    /**
     * A negative amount subtracts; the method does not assert sign.
     */
    @Test
    void add_canSubtract()
    {
        IntList list = new IntList().push(10);
        list.add(0, -7);
        assertEquals(3, list.get(0));
    }

    /**
     * Adding at a position beyond {@code size} extends the list and leaves intermediate cells
     * at zero.
     */
    @Test
    void add_growsListWhenPositionBeyondSize()
    {
        IntList list = new IntList(2);
        list.add(10, 7);
        assertEquals(11, list.size());
        assertEquals(7, list.get(10));
        assertEquals(0, list.get(0));
        assertEquals(0, list.get(9));
    }

    /**
     * Builder-style chaining: {@code add} returns {@code this}.
     */
    @Test
    void add_returnsThisForChaining()
    {
        IntList list = new IntList();
        assertSame(list, list.add(0, 1));
    }

    /**
     * {@code clear()} is documented as a light reset: it must not zero the underlying buffer.
     */
    @Test
    void clear_doesNotEraseUnderlyingData()
    {
        IntList list = new IntList().push(7).push(8).push(9);
        list.clear();
        int[] raw = list.data();
        assertEquals(7, raw[0]);
        assertEquals(8, raw[1]);
        assertEquals(9, raw[2]);
    }

    /**
     * Logical size drops to zero after {@code clear()}.
     */
    @Test
    void clear_resetsSizeToZero()
    {
        IntList list = new IntList().push(1).push(2).push(3);
        list.clear();
        assertEquals(0, list.size());
        assertTrue(list.isEmpty());
    }

    /**
     * Builder-style chaining: {@code clear} returns {@code this}.
     */
    @Test
    void clear_returnsThisForChaining()
    {
        IntList list = new IntList();
        assertSame(list, list.clear());
    }

    /**
     * The no-arg constructor yields an empty list with a non-null backing buffer.
     */
    @Test
    void constructor_defaultCreatesEmptyList()
    {
        IntList list = new IntList();
        assertEquals(0, list.size());
        assertTrue(list.isEmpty());
        assertNotNullArray(list.data());
    }

    /**
     * Constructing with an explicit capacity still produces an empty list.
     */
    @Test
    void constructor_withCapacityCreatesEmptyList()
    {
        IntList list = new IntList(100);
        assertEquals(0, list.size());
        assertTrue(list.isEmpty());
        assertEquals(100, list.data().length);
    }

    /**
     * The wrap-array constructor exposes the array but does not set {@code size}, so the list
     * appears empty to its public API. This is a surprising but currently documented behaviour;
     * the test pins it down so any change becomes explicit.
     */
    @Test
    void constructor_wrappingArrayHasSizeZero()
    {
        int[] src = { 4, 5, 6 };
        IntList list = new IntList(src);
        assertEquals(0, list.size());
        assertTrue(list.isEmpty());
        assertSame(src, list.data());
    }

    /**
     * {@code data()} exposes the live backing array. Mutations through this reference are
     * visible via {@code get}.
     */
    @Test
    void data_exposesUnderlyingArrayReference()
    {
        IntList list = new IntList().push(1).push(2);
        int[] raw = list.data();
        raw[0] = 99;
        assertEquals(99, list.get(0));
    }

    /**
     * Different content at the same size compares unequal.
     */
    @Test
    void equals_falseForDifferentContent()
    {
        IntList a = new IntList().push(1).push(2).push(3);
        IntList b = new IntList().push(1).push(2).push(4);
        assertNotEquals(a, b);
    }

    /**
     * Different logical sizes compare unequal even if shared prefixes match.
     */
    @Test
    void equals_falseForDifferentSize()
    {
        IntList a = new IntList().push(1).push(2);
        IntList b = new IntList().push(1).push(2).push(3);
        assertNotEquals(a, b);
    }

    /**
     * The {@code equals(null)} contract: never true.
     */
    @Test
    void equals_falseForNull()
    {
        IntList a = new IntList().push(1);
        assertFalse(a.equals(null));
    }

    /**
     * Foreign types compare unequal.
     */
    @Test
    void equals_falseForOtherType()
    {
        IntList a = new IntList().push(1);
        assertFalse(a.equals("foo"));
        assertFalse(a.equals(new int[] { 1 }));
    }

    /**
     * Equality depends only on logical content, not underlying buffer capacity.
     */
    @Test
    void equals_ignoresCapacityDifference()
    {
        IntList small = new IntList(4).push(1).push(2);
        IntList big = new IntList(1024).push(1).push(2);
        assertEquals(small, big);
    }

    /**
     * Same logical content compares equal.
     */
    @Test
    void equals_trueForSameContent()
    {
        IntList a = new IntList().push(1).push(2).push(3);
        IntList b = new IntList().push(1).push(2).push(3);
        assertEquals(a, b);
    }

    /**
     * Reflexivity: a list equals itself.
     */
    @Test
    void equals_trueForSelf()
    {
        IntList a = new IntList().push(42);
        assertEquals(a, a);
    }

    /**
     * {@code first()} returns the leftmost element.
     */
    @Test
    void first_returnsFirstElement()
    {
        IntList list = new IntList().push(7).push(8).push(9);
        assertEquals(7, list.first());
    }

    /**
     * {@code first()} on an empty list throws {@link ArrayIndexOutOfBoundsException}.
     */
    @Test
    void first_throwsOnEmpty()
    {
        IntList list = new IntList();
        assertThrows(ArrayIndexOutOfBoundsException.class, list::first);
    }

    /**
     * {@code get(position)} returns the value at the given index for positions within size.
     */
    @Test
    void get_returnsValueAtPosition()
    {
        IntList list = new IntList().push(10).push(20).push(30);
        assertEquals(10, list.get(0));
        assertEquals(20, list.get(1));
        assertEquals(30, list.get(2));
    }

    /**
     * Repeated invocations return the same value (the cache is consistent).
     */
    @Test
    void hashCode_consistentAcrossInvocations()
    {
        IntList list = new IntList().push(1).push(2).push(3);
        int first = list.hashCode();
        int second = list.hashCode();
        int third = list.hashCode();
        assertEquals(first, second);
        assertEquals(second, third);
    }

    /**
     * Equal lists have equal hash codes (general contract of {@link Object#hashCode()}).
     */
    @Test
    void hashCode_equalForEqualLists()
    {
        IntList a = new IntList().push(1).push(2).push(3);
        IntList b = new IntList().push(1).push(2).push(3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    /**
     * Mutations through public write methods invalidate the cached hash.
     */
    @Test
    void hashCode_invalidatedAfterMutation()
    {
        IntList list = new IntList().push(1).push(2);
        int before = list.hashCode();
        list.push(3);
        int after = list.hashCode();
        assertNotEquals(before, after);
    }

    /**
     * Increment at an existing position adds one without resizing.
     */
    @Test
    void inc_incrementsExistingValue()
    {
        IntList list = new IntList().push(5);
        list.inc(0);
        list.inc(0);
        assertEquals(7, list.get(0));
        assertEquals(1, list.size());
    }

    /**
     * Incrementing beyond {@code size} extends the list; intermediate cells are zero.
     */
    @Test
    void inc_growsListWhenPositionBeyondSize()
    {
        IntList list = new IntList(2);
        list.inc(5);
        assertEquals(6, list.size());
        assertEquals(1, list.get(5));
        assertEquals(0, list.get(0));
    }

    /**
     * Builder-style chaining: {@code inc} returns {@code this}.
     */
    @Test
    void inc_returnsThisForChaining()
    {
        IntList list = new IntList();
        assertSame(list, list.inc(0));
    }

    /**
     * {@code isEmpty()} flips to false once any element is added.
     */
    @Test
    void isEmpty_falseAfterPush()
    {
        IntList list = new IntList().push(0);
        assertFalse(list.isEmpty());
    }

    /**
     * {@code clear()} restores empty state.
     */
    @Test
    void isEmpty_trueAfterClear()
    {
        IntList list = new IntList().push(1).push(2);
        list.clear();
        assertTrue(list.isEmpty());
    }

    /**
     * A freshly constructed list reports empty.
     */
    @Test
    void isEmpty_trueOnNewList()
    {
        assertTrue(new IntList().isEmpty());
        assertTrue(new IntList(100).isEmpty());
    }

    /**
     * {@code last()} returns the rightmost element.
     */
    @Test
    void last_returnsLastElement()
    {
        IntList list = new IntList().push(7).push(8).push(9);
        assertEquals(9, list.last());
    }

    /**
     * {@code last()} on an empty list throws {@link ArrayIndexOutOfBoundsException}.
     */
    @Test
    void last_throwsOnEmpty()
    {
        IntList list = new IntList();
        assertThrows(ArrayIndexOutOfBoundsException.class, list::last);
    }

    /**
     * {@code push(int)} appends and increments size.
     */
    @Test
    void push_appendsValue()
    {
        IntList list = new IntList();
        list.push(42);
        assertEquals(1, list.size());
        assertEquals(42, list.get(0));
    }

    /**
     * {@code push(int[])} appends all elements of the source array.
     */
    @Test
    void push_arrayAppendsAllValues()
    {
        IntList list = new IntList().push(1);
        list.push(new int[] { 2, 3, 4 });
        assertArrayEquals(new int[] { 1, 2, 3, 4 }, list.toArray());
    }

    /**
     * Pushing an empty array is a valid no-op on logical content.
     */
    @Test
    void push_arrayDoesNothingForEmptyInput()
    {
        IntList list = new IntList().push(1).push(2);
        list.push(new int[0]);
        assertArrayEquals(new int[] { 1, 2 }, list.toArray());
    }

    /**
     * Repeated pushes beyond the initial capacity trigger growth without loss of data.
     */
    @Test
    void push_growsBeyondInitialCapacity()
    {
        IntList list = new IntList(2);
        for (int i = 0; i < 100; i++) {
            list.push(i);
        }
        assertEquals(100, list.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(i, list.get(i));
        }
    }

    /**
     * Builder-style chaining: {@code push(int)} returns {@code this}.
     */
    @Test
    void push_returnsThisForChaining()
    {
        IntList list = new IntList();
        assertSame(list, list.push(1));
        assertSame(list, list.push(new int[] { 2, 3 }));
    }

    /**
     * {@code set} beyond {@code size} extends the list; intermediate cells are zero.
     */
    @Test
    void set_growsListWhenPositionBeyondSize()
    {
        IntList list = new IntList(2);
        list.set(5, 99);
        assertEquals(6, list.size());
        assertEquals(99, list.get(5));
        assertEquals(0, list.get(0));
    }

    /**
     * {@code set} at an in-bounds position overwrites in place.
     */
    @Test
    void set_overwritesExistingValue()
    {
        IntList list = new IntList().push(10).push(20).push(30);
        list.set(1, 99);
        assertArrayEquals(new int[] { 10, 99, 30 }, list.toArray());
    }

    /**
     * Builder-style chaining: {@code set} returns {@code this}.
     */
    @Test
    void set_returnsThisForChaining()
    {
        IntList list = new IntList();
        assertSame(list, list.set(0, 1));
    }

    /**
     * The package-private {@code shuffle(int[])} preserves the multiset of values.
     * Order randomness is not directly assertable, but the value histogram is invariant.
     */
    @Test
    void shuffle_preservesElementMultiset()
    {
        int[] arr = { 1, 2, 2, 3, 3, 3, 4, 5 };
        Map<Integer, Integer> before = histogram(arr);
        IntList.shuffle(arr);
        Map<Integer, Integer> after = histogram(arr);
        assertEquals(before, after);
    }

    /**
     * {@code size()} reflects the number of pushed elements.
     */
    @Test
    void size_reflectsNumberOfElements()
    {
        IntList list = new IntList();
        assertEquals(0, list.size());
        list.push(1);
        assertEquals(1, list.size());
        list.push(2).push(3);
        assertEquals(3, list.size());
    }

    /**
     * {@code toArray()} returns a fresh copy of length {@code size}, not the backing buffer.
     */
    @Test
    void toArray_returnsFreshCopy()
    {
        IntList list = new IntList(10).push(1).push(2).push(3);
        int[] copy = list.toArray();
        assertEquals(3, copy.length);
        assertArrayEquals(new int[] { 1, 2, 3 }, copy);
        assertNotSame(list.data(), copy);
        copy[0] = 999;
        assertEquals(1, list.get(0));
    }

    /**
     * {@code toArray(size)} returns a copy of exactly the requested length. Cells beyond the
     * list's logical size come from the (zero-initialised) backing buffer.
     */
    @Test
    void toArray_withSizeReturnsRequestedLength()
    {
        IntList list = new IntList(10).push(1).push(2).push(3);
        int[] truncated = list.toArray(2);
        assertArrayEquals(new int[] { 1, 2 }, truncated);
        int[] padded = list.toArray(5);
        assertArrayEquals(new int[] { 1, 2, 3, 0, 0 }, padded);
    }

    /**
     * {@code toSet()} on an empty list returns an empty array.
     */
    @Test
    void toSet_emptyListReturnsEmpty()
    {
        int[] result = new IntList().toSet();
        assertEquals(0, result.length);
    }

    /**
     * {@code toSet()} preserves the order in which each distinct value first appears.
     */
    @Test
    void toSet_preservesFirstOccurrenceOrder()
    {
        IntList list = new IntList().push(3).push(1).push(2).push(1).push(3).push(4);
        assertArrayEquals(new int[] { 3, 1, 2, 4 }, list.toSet());
    }

    /**
     * {@code toSet()} removes every duplicate.
     */
    @Test
    void toSet_removesDuplicates()
    {
        IntList list = new IntList().push(5).push(5).push(5);
        assertArrayEquals(new int[] { 5 }, list.toSet());
    }

    /**
     * {@code toSet()} returns a fresh array decoupled from the list.
     */
    @Test
    void toSet_returnsFreshArray()
    {
        IntList list = new IntList().push(1).push(2);
        int[] result = list.toSet();
        assertNotSame(list.data(), result);
        result[0] = 999;
        assertEquals(1, list.get(0));
    }

    /**
     * Empty list renders as {@code "()"}.
     */
    @Test
    void toString_emptyListFormat()
    {
        assertEquals("()", new IntList().toString());
    }

    /**
     * Single and multi-element lists render as parenthesised comma-separated values.
     */
    @Test
    void toString_singleAndMultipleElementFormat()
    {
        assertEquals("(42)", new IntList().push(42).toString());
        assertEquals("(1, 2, 3)", new IntList().push(1).push(2).push(3).toString());
    }

    /**
     * {@code uniq()} on a populated list returns a sorted array without duplicates.
     */
    @Test
    void uniq_returnsFreshSortedArrayWithoutDuplicates()
    {
        IntList list = new IntList().push(5).push(2).push(5).push(1).push(3).push(2);
        int[] result = list.uniq();
        assertArrayEquals(new int[] { 1, 2, 3, 5 }, result);
        assertNotSame(list.data(), result);
    }

    /**
     * The static {@code uniq(int[])} must not mutate its input.
     */
    @Test
    void uniqStatic_doesNotMutateSource()
    {
        int[] src = { 5, 2, 5, 1, 3, 2 };
        int[] snapshot = src.clone();
        IntList.uniq(src);
        assertArrayEquals(snapshot, src);
    }

    /**
     * The static {@code uniq(int[])} on an empty array returns an empty array.
     */
    @Test
    void uniqStatic_emptyArrayReturnsEmpty()
    {
        int[] result = IntList.uniq(new int[0]);
        assertEquals(0, result.length);
    }

    /**
     * The static {@code uniq(int[])} returns {@code null} when given {@code null}.
     */
    @Test
    void uniqStatic_nullReturnsNull()
    {
        assertNull(IntList.uniq(null));
    }

    /**
     * The static {@code uniq(int[])} sorts and deduplicates in one pass.
     */
    @Test
    void uniqStatic_sortsAndDedups()
    {
        int[] result = IntList.uniq(new int[] { 5, 2, 5, 1, 3, 2 });
        assertArrayEquals(new int[] { 1, 2, 3, 5 }, result);
    }

    /**
     * BUG: {@code clear()} does not invalidate the cached hash. After clear, the hash should
     * match that of a fresh empty list; currently it returns the pre-clear value.
     */
    @Test
    void hashCode_invalidatedAfterClear()
    {
        IntList list = new IntList().push(1).push(2).push(3);
        list.hashCode();
        list.clear();
        IntList empty = new IntList();
        assertEquals(empty.hashCode(), list.hashCode(),
                "clear() must invalidate the hash cache");
    }

    /**
     * BUG: {@code uniq()} on an empty list throws {@link ArrayIndexOutOfBoundsException}
     * because of an unguarded {@code work[0]} access. Expected: return an empty array.
     */
    @Test
    void uniq_emptyListReturnsEmpty()
    {
        IntList list = new IntList();
        int[] result = list.uniq();
        assertEquals(0, result.length, "uniq() on empty list must return empty array, not throw");
    }

    /**
     * BUG: The static {@code uniq(int[])} returns the source array directly when length is
     * 0 or 1, violating the "fresh array" contract every other branch follows. A caller that
     * mutates the result will silently mutate the input.
     */
    @Test
    void uniqStatic_singleElementReturnsFreshCopy()
    {
        int[] src = { 42 };
        int[] result = IntList.uniq(src);
        assertArrayEquals(new int[] { 42 }, result);
        assertNotSame(src, result, "uniq(int[]) must always return a freshly allocated array");
    }

    /**
     * Builds a value-to-count histogram. Used by {@link #shuffle_preservesElementMultiset()}.
     *
     * @param arr source array
     * @return map from value to occurrence count
     */
    private static Map<Integer, Integer> histogram(final int[] arr)
    {
        Map<Integer, Integer> h = new HashMap<>();
        for (int v : arr) {
            h.merge(v, 1, Integer::sum);
        }
        return h;
    }

    /**
     * Convenience assertion: backing array is non-null. Kept private to avoid leaking into
     * shared test infrastructure.
     *
     * @param arr array under test
     */
    private static void assertNotNullArray(final int[] arr)
    {
        if (arr == null) {
            throw new AssertionError("backing array must not be null");
        }
    }
}
