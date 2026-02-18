package com.github.oeuvres.alix.lucene.analysis;

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.AttributeSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 tests for {@link TokenStateQueue}.
 *
 * These tests validate:
 * - FIFO order (addLast/removeFirst)
 * - addFirst semantics
 * - snapshot isolation (queue stores copies, not live views)
 * - overflow policies: THROW, DROP_OLDEST, DROP_NEWEST, GROW (with maxCapacity cap)
 * - clear/reset behavior
 */
public class TokenStateQueueTest {

    private static AttributeSource newModel()
    {
        final AttributeSource model = new AttributeSource();
        model.addAttribute(CharTermAttribute.class); // minimal attribute for observability
        return model;
    }

    private static void setTerm(final AttributeSource src, final String s)
    {
        final CharTermAttribute term = src.getAttribute(CharTermAttribute.class);
        term.setEmpty().append(s);
    }

    private static String termOf(final AttributeSource src)
    {
        return src.getAttribute(CharTermAttribute.class).toString();
    }

    @Test
    void addLastAndRestoreOrder()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(4, 4, TokenStateQueue.OverflowPolicy.THROW, model);

        setTerm(model, "A"); q.addLast();
        setTerm(model, "B"); q.addLast();
        setTerm(model, "C"); q.addLast();

        assertEquals(3, q.size());

        final AttributeSource sink = model.cloneAttributes();

        q.restoreTo(sink, 0); assertEquals("A", termOf(sink));
        q.restoreTo(sink, 1); assertEquals("B", termOf(sink));
        q.restoreTo(sink, 2); assertEquals("C", termOf(sink));
    }

    @Test
    void snapshotIsolation()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(2, 2, TokenStateQueue.OverflowPolicy.THROW, model);

        setTerm(model, "A");
        q.addLast();               // snapshot "A"

        setTerm(model, "B");       // mutate producer after snapshot

        final AttributeSource sink = model.cloneAttributes();
        q.restoreTo(sink, 0);

        // must still be "A" (queue stores a copy)
        assertEquals("A", termOf(sink));
    }

    @Test
    void removeFirstAndPeek()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(4, 4, TokenStateQueue.OverflowPolicy.THROW, model);

        setTerm(model, "A"); q.addLast();
        setTerm(model, "B"); q.addLast();
        setTerm(model, "C"); q.addLast();

        final AttributeSource sink = model.cloneAttributes();

        q.removeFirst(sink);
        assertEquals("A", termOf(sink));
        assertEquals(2, q.size());

        // peekFirst / peekLast are internal views; validate via restoreTo (stable API)
        q.restoreTo(sink, 0); assertEquals("B", termOf(sink));
        q.restoreTo(sink, 1); assertEquals("C", termOf(sink));
    }

    @Test
    void addFirstWorks()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(4, 4, TokenStateQueue.OverflowPolicy.THROW, model);

        setTerm(model, "A"); q.addLast();
        setTerm(model, "B"); q.addLast();
        setTerm(model, "X"); q.addFirst(); // now: X A B

        final AttributeSource sink = model.cloneAttributes();
        q.restoreTo(sink, 0); assertEquals("X", termOf(sink));
        q.restoreTo(sink, 1); assertEquals("A", termOf(sink));
        q.restoreTo(sink, 2); assertEquals("B", termOf(sink));
    }

    @Test
    void overflowThrow()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(2, 2, TokenStateQueue.OverflowPolicy.THROW, model);

        setTerm(model, "A"); q.addLast();
        setTerm(model, "B"); q.addLast();

        setTerm(model, "C");
        assertThrows(IllegalStateException.class, q::addLast);
        assertEquals(2, q.size());
    }

    @Test
    void overflowDropOldest()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(2, 2, TokenStateQueue.OverflowPolicy.DROP_OLDEST, model);

        setTerm(model, "A"); q.addLast();
        setTerm(model, "B"); q.addLast();
        setTerm(model, "C"); q.addLast(); // drops A => keeps B C

        assertEquals(2, q.size());

        final AttributeSource sink = model.cloneAttributes();
        q.restoreTo(sink, 0); assertEquals("B", termOf(sink));
        q.restoreTo(sink, 1); assertEquals("C", termOf(sink));
    }

    @Test
    void overflowDropNewest()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(2, 2, TokenStateQueue.OverflowPolicy.DROP_NEWEST, model);

        setTerm(model, "A"); q.addLast();
        setTerm(model, "B"); q.addLast();
        setTerm(model, "C"); q.addLast(); // ignored => keeps A B

        assertEquals(2, q.size());

        final AttributeSource sink = model.cloneAttributes();
        q.restoreTo(sink, 0); assertEquals("A", termOf(sink));
        q.restoreTo(sink, 1); assertEquals("B", termOf(sink));
    }

    @Test
    void overflowGrowUpToMaxCapacity()
    {
        final AttributeSource model = newModel();
        // start with 2, allow growth up to 5
        final TokenStateQueue q = new TokenStateQueue(2, 5, TokenStateQueue.OverflowPolicy.GROW, model);

        setTerm(model, "A"); q.addLast();
        setTerm(model, "B"); q.addLast();
        assertEquals(2, q.size());
        assertEquals(2, q.capacity());

        setTerm(model, "C"); q.addLast(); // triggers grow to 4
        assertEquals(3, q.size());
        assertTrue(q.capacity() >= 3);
        assertTrue(q.capacity() <= 5);

        setTerm(model, "D"); q.addLast();
        setTerm(model, "E"); q.addLast(); // may trigger grow to 5
        assertEquals(5, q.size());
        assertTrue(q.capacity() <= 5);

        final AttributeSource sink = model.cloneAttributes();
        q.restoreTo(sink, 0); assertEquals("A", termOf(sink));
        q.restoreTo(sink, 1); assertEquals("B", termOf(sink));
        q.restoreTo(sink, 2); assertEquals("C", termOf(sink));
        q.restoreTo(sink, 3); assertEquals("D", termOf(sink));
        q.restoreTo(sink, 4); assertEquals("E", termOf(sink));

        // One more should fail because maxCapacity reached
        setTerm(model, "F");
        assertThrows(IllegalStateException.class, q::addLast);
        assertEquals(5, q.size());
    }

    @Test
    void clearResetsQueue()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(3, 3, TokenStateQueue.OverflowPolicy.THROW, model);

        setTerm(model, "A"); q.addLast();
        setTerm(model, "B"); q.addLast();
        assertEquals(2, q.size());

        q.clear();
        assertEquals(0, q.size());
        assertTrue(q.isEmpty());

        // After clear, should accept new items and behave as fresh.
        setTerm(model, "X"); q.addLast();
        final AttributeSource sink = model.cloneAttributes();
        q.restoreTo(sink, 0);
        assertEquals("X", termOf(sink));
    }

    @Test
    void removeOnEmptyThrows()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(2, 2, TokenStateQueue.OverflowPolicy.THROW, model);

        assertThrows(java.util.NoSuchElementException.class, q::removeFirst);
        assertThrows(java.util.NoSuchElementException.class, q::removeLast);

        final AttributeSource sink = model.cloneAttributes();
        assertThrows(java.util.NoSuchElementException.class, () -> q.removeFirst(sink));
        assertThrows(java.util.NoSuchElementException.class, () -> q.removeLast(sink));
    }

    @Test
    void getIndexBounds()
    {
        final AttributeSource model = newModel();
        final TokenStateQueue q = new TokenStateQueue(2, 2, TokenStateQueue.OverflowPolicy.THROW, model);

        setTerm(model, "A"); q.addLast();

        assertThrows(IndexOutOfBoundsException.class, () -> q.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> q.get(1));
    }
}
